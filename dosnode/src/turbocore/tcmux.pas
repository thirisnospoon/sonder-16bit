{ ===================================================================
  TurboCore · мультиплексор.

  Одна линия обслуживает шестнадцать команд одновременно. Мультиплексор
  разводит их по каналам и, что важнее, позволяет не ждать: файбер
  отправляет запрос и засыпает, а линия тем временем несёт запрос
  следующего.

  ЗАЧЕМ КОНВЕЙЕРИЗАЦИЯ. S2 измерил 13 мс накладных на КРУГОВОЙ обмен, не
  зависящих от скорости линии. Шестнадцать команд, каждая со своим
  ожиданием ответа, стоили бы 208 мс чистых накладных; отправленные
  подряд — одни. Это и есть главная работа этого модуля.

  ИСХОДЯЩАЯ ОЧЕРЕДЬ — КОЛЬЦО БАЙТ, а не кадров. Порту всё равно нужны
  байты, кодировать дважды незачем, а кольцо естественным образом даёт
  предел: когда оно заполнено, отправитель получает отказ, а не растущую
  очередь. Обратное давление лучше видеть сразу, чем узнать о нём по
  исчерпанию памяти.

  ВЛАДЕНИЕ БУФЕРОМ ОТВЕТА остаётся у вызывающего. Файбер, открывая
  канал, передаёт указатель на место, куда положить ответ. Мультиплексор
  ничего не выделяет: шестнадцать кадров по 516 байт заняли бы восемь
  килобайт сегмента данных, а так они лежат в аренах команд, где им и
  место.
  =================================================================== }
unit TcMux;

{$MODE TP}
{$R-}

interface

uses
  TcResult, TcFrame, TcSched;

const
  { Кольцо исходящих байт.

    Чтобы шестнадцать команд никогда не упирались в предел, нужно
    16 × 520 = 8320 байт. Это заметная доля сегмента данных, а
    заполненное кольцо — не авария: отправитель получает отказ и
    повторяет после того, как линия разгрузится. Две тысячи байт держат
    в полёте примерно четыре предельных кадра или два десятка мелких,
    чего достаточно, чтобы линия не простаивала.

    Число подлежит уточнению бенчмарком Ф10: если окажется, что файберы
    заметное время проводят в отказах, кольцо переедет в дальнюю кучу. }
  OutRingBytes = 2048;

  { Канал на команду в работе, плюс служебные. Номера каналов совпадают
    с номерами файберов: команда обслуживается одним файбером, и
    отдельная таблица соответствия была бы лишней сущностью. }
  FirstDataChan = 1;
  LastDataChan  = MaxFibers;

type
  PFrame = ^TFrame;

  TChanState = (
    csFree,
    csWaiting,   { запрос отправлен, ответ не пришёл }
    csDone       { ответ доставлен в буфер владельца }
  );

  TMuxStats = record
    Sent:          LongInt;   { кадров положено в кольцо }
    Received:      LongInt;   { кадров разобрано из линии }
    Delivered:     LongInt;   { кадров доставлено владельцу канала }
    Unrouted:      LongInt;   { кадр пришёл на канал, которого никто не ждёт }
    Backpressure:  LongInt;   { отказов из-за заполненного кольца }
    IdleResets:    LongInt;   { сигналов о паузе, бросивших недособранное }
    OutHighMark:   Word;      { пик занятости кольца }
  end;

procedure MuxReset;

{ Занять канал. Reply указывает, куда положить ответ; память принадлежит
  вызывающему и обязана пережить ожидание. }
function MuxOpen(Owner: TFiberId; Reply: PFrame; var Chan: Byte): TResult;

procedure MuxClose(Chan: Byte);

{ Поставить кадр в очередь на отправку. Не ждёт и не блокирует: при
  заполненном кольце возвращает отказ, и это нормальный исход. }
function MuxSend(const F: TFrame): TResult;

{ Забрать байт для передачи в линию. False — отправлять нечего. }
function MuxOutByte(var B: Byte): Boolean;
function MuxOutPending: Word;

{ Скормить байт, пришедший из линии. Собранный кадр маршрутизируется
  владельцу канала, и владелец будится. }
procedure MuxFeedByte(B: Byte);

{ Сообщить о паузе в линии: недособранный кадр бросается (см. TcFrame). }
procedure MuxIdle;

function MuxChanState(Chan: Byte): TChanState;
function MuxGetStats: TMuxStats;

implementation

type
  TChannel = record
    State: TChanState;
    Owner: TFiberId;
    Reply: PFrame;
  end;

var
  Chans: array[0..255] of TChannel;
  Ring:  array[0..OutRingBytes - 1] of Byte;
  Head, Tail, Used: Word;      { Head — откуда читать, Tail — куда писать }
  { Имя Rx, а не Dec: Dec — встроенная процедура уменьшения, и
    переменная с таким именем её перекрывает. Компилятор сообщает об
    этом не там, где объявление, а там, где вызов. }
  Rx:    TDecoder;
  Stats: TMuxStats;

procedure MuxReset;
var
  I: Integer;
begin
  for I := 0 to 255 do
  begin
    Chans[I].State := csFree;
    Chans[I].Owner := SchedulerId;
    Chans[I].Reply := nil;
  end;
  Head := 0;
  Tail := 0;
  Used := 0;
  DecoderReset(Rx);
  FillChar(Stats, SizeOf(Stats), 0);
end;

function MuxOpen(Owner: TFiberId; Reply: PFrame; var Chan: Byte): TResult;
var
  I: Integer;
begin
  Chan := 0;

  { Без буфера ответ класть некуда, и обнаружится это только когда ответ
    придёт. Отказываем сразу. }
  if Reply = nil then
  begin
    MuxOpen := Err('DECIDER_PANIC');
    Exit;
  end;

  for I := FirstDataChan to LastDataChan do
    if Chans[I].State = csFree then
    begin
      Chans[I].State := csWaiting;
      Chans[I].Owner := Owner;
      Chans[I].Reply := Reply;
      Chan := Byte(I);
      MuxOpen := Ok;
      Exit;
    end;

  MuxOpen := Err('DECIDER_UNAVAILABLE');
end;

procedure MuxClose(Chan: Byte);
begin
  Chans[Chan].State := csFree;
  Chans[Chan].Owner := SchedulerId;
  Chans[Chan].Reply := nil;
end;

function MuxOutPending: Word;
begin
  MuxOutPending := Used;
end;

function MuxSend(const F: TFrame): TResult;
var
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  N, I: Word;
  R: TResult;
begin
  R := FrameEncode(F, Buf, SizeOf(Buf), N);
  if not R.Ok then
  begin
    MuxSend := R;
    Exit;
  end;

  { Кадр кладётся в кольцо целиком или не кладётся вовсе. Половина кадра
    в линии — это мусор для приёмника и потерянная синхронизация. }
  if Word(OutRingBytes) - Used < N then
  begin
    Inc(Stats.Backpressure);
    MuxSend := Err('DECIDER_UNAVAILABLE');
    Exit;
  end;

  for I := 0 to N - 1 do
  begin
    Ring[Tail] := Buf[I];
    Inc(Tail);
    if Tail >= OutRingBytes then
      Tail := 0;
  end;
  Inc(Used, N);
  if Used > Stats.OutHighMark then
    Stats.OutHighMark := Used;

  Inc(Stats.Sent);
  MuxSend := Ok;
end;

function MuxOutByte(var B: Byte): Boolean;
begin
  if Used = 0 then
  begin
    MuxOutByte := False;
    Exit;
  end;
  B := Ring[Head];
  Inc(Head);
  if Head >= OutRingBytes then
    Head := 0;
  Dec(Used);
  MuxOutByte := True;
end;

{ Разобранный кадр отдаётся владельцу канала. }
procedure Route(const F: TFrame);
var
  C: Byte;
begin
  Inc(Stats.Received);
  C := F.Channel;

  if Chans[C].State <> csWaiting then
  begin
    { Ответ на канал, которого никто не ждёт. Это не авария: запрос мог
      быть отменён по сроку, а ответ прийти позже. Но считать надо —
      растущий счётчик означает, что сроки выставлены слишком туго. }
    Inc(Stats.Unrouted);
    Exit;
  end;

  if Chans[C].Reply <> nil then
    Chans[C].Reply^ := F;

  Chans[C].State := csDone;
  Inc(Stats.Delivered);

  { Владелец спит на ожидании ответа по этому каналу. }
  SchedWake(wrReply, C);
end;

procedure MuxFeedByte(B: Byte);
begin
  if DecoderFeed(Rx, B) then
    Route(Rx.Frame);
end;

procedure MuxIdle;
begin
  if DecoderIdle(Rx) then
    Inc(Stats.IdleResets);
end;

function MuxChanState(Chan: Byte): TChanState;
begin
  MuxChanState := Chans[Chan].State;
end;

function MuxGetStats: TMuxStats;
begin
  MuxGetStats := Stats;
end;

begin
  MuxReset;
end.
