{ ===================================================================
  TurboCore · мультиплексор.

  Одна линия обслуживает шестнадцать команд одновременно. Мультиплексор
  разводит входящие команды по каналам и возвращает ответы в ту же линию,
  не давая медленному ответу задержать остальные.

  НОМЕРА КАНАЛОВ НАЗНАЧАЕТ ГЕЙТВЕЙ. Клиент SOAP — Java, сервер — NODE-7
  (ADR-0011, ADR-0015). Нода не открывает каналов: кадр, пришедший на
  свободный канал, и ЕСТЬ новая команда. Раньше модуль выбирал номера
  сам, и это столкнуло бы две нумерации на одной линии — ошибка, которая
  выглядела бы как перепутанные ответы, то есть как правдоподобный
  неверный результат.

  ПРИЛОЖЕНИЕ ПОЛУЧАЕТ КАДРЫ В КОНТЕКСТЕ ЦИКЛА СОБЫТИЙ, а не своего
  файбера. Обработчик обязан быть быстрым и не имеет права переключать
  контекст: его дело — скормить байты разборщику. Решение принимается
  позже, в файбере, когда сообщение закончилось.

  СООБЩЕНИЕ МОЖЕТ НЕ ПОМЕСТИТЬСЯ В КАДР. Конверт с телом поста на тысячу
  символов — около четырёх с половиной килобайт при полезной нагрузке в
  512 байт. Кадры выдаются приложению по мере прихода, а не копятся:
  разбор потоковый, и держать конверт в памяти целиком незачем. Флаг
  FlagMore означает «сообщение продолжается».

  ИСХОДЯЩАЯ ОЧЕРЕДЬ — КОЛЬЦО БАЙТ, а не кадров. Порту всё равно нужны
  байты, кодировать дважды незачем, а кольцо естественным образом даёт
  предел: когда оно заполнено, отправитель получает отказ, а не растущую
  очередь. Обратное давление лучше видеть сразу, чем узнать о нём по
  исчерпанию памяти.
  =================================================================== }
unit TcMux;

{$MODE TP}
{$R-}

interface

uses
  TcResult, TcFrame, TcSched;

const
  { Кольцо исходящих байт.

    Чтобы шестнадцать ответов никогда не упирались в предел, нужно
    16 × 520 = 8320 байт. Это заметная доля сегмента данных, а
    заполненное кольцо — не авария: отправитель получает отказ и
    повторяет после того, как линия разгрузится. Две тысячи байт держат
    в полёте примерно четыре предельных кадра или два десятка мелких,
    чего достаточно, чтобы линия не простаивала.

    Число подлежит уточнению бенчмарком Ф10: если окажется, что файберы
    заметное время проводят в отказах, кольцо переедет в дальнюю кучу. }
  OutRingBytes = 2048;

  { Канал на команду в работе, плюс служебные. Диапазон согласован с
    гейтвеем: он назначает номера, нода их принимает. }
  FirstDataChan = 1;
  LastDataChan  = MaxFibers;

type
  { Обработчик управляющего канала.

    Кадры нулевого канала не адресованы командам: это служебный обмен —
    приветствие, метрики, отзыв. Без отдельного обработчика они считались
    бы неприкаянными, и полезный счётчик превратился бы в шум. }
  TControlHandler = procedure(const F: TFrame);

  { Обработчик входящей команды.

    First — первый кадр сообщения: приложению пора завести файбер и
    сбросить разборщик. Last — сообщение закончилось (не было FlagMore):
    пора будить файбер на решение.

    Вызывается в контексте цикла событий. Переключать контекст отсюда
    нельзя. }
  TCommandHandler = procedure(Chan: Byte; const F: TFrame;
                              First, Last: Boolean);

  TChanState = (
    csFree,
    csServing,   { команда пришла и обрабатывается }
    csAnswered   { ответ отправлен, ждём освобождения приложением }
  );

  TMuxStats = record
    Sent:          LongInt;   { кадров положено в кольцо }
    Received:      LongInt;   { кадров разобрано из линии }
    Commands:      LongInt;   { начатых команд }
    Continued:     LongInt;   { кадров-продолжений }
    Completed:     LongInt;   { сообщений, дошедших до последнего кадра }
    Control:       LongInt;   { кадров управляющего канала }
    Unrouted:      LongInt;   { кадр на канал вне диапазона данных }
    Refused:       LongInt;   { команда не принята: канал занят или нет места }
    Backpressure:  LongInt;   { отказов из-за заполненного кольца }
    IdleResets:    LongInt;   { сигналов о паузе, бросивших недособранное }
    OutHighMark:   Word;      { пик занятости кольца }
  end;

procedure MuxReset;

{ Назначить обработчик управляющего канала. Без него служебные кадры
  просто отбрасываются, но считаются отдельно от неприкаянных. }
procedure MuxSetControlHandler(H: TControlHandler);

{ Назначить обработчик входящих команд. Без него команды принимаются и
  отбрасываются: считать их неприкаянными было бы неверно — канал в
  порядке, просто некому обслуживать. }
procedure MuxSetCommandHandler(H: TCommandHandler);

{ Записать, какой файбер обслуживает канал. Мультиплексор этим не
  пользуется — он не будит и не ждёт, — но по каналу должно быть видно,
  кто за него отвечает: без этого не разобрать ни лога, ни метрик. }
procedure MuxSetOwner(Chan: Byte; Owner: TFiberId);
function MuxOwner(Chan: Byte): TFiberId;

{ Освободить канал. Вызывает приложение, когда ответ отправлен целиком и
  файбер завершён. }
procedure MuxRelease(Chan: Byte);

{ Поставить кадр в очередь на отправку. Не ждёт и не блокирует: при
  заполненном кольце возвращает отказ, и это нормальный исход. }
function MuxSend(const F: TFrame): TResult;

{ Ответить на канал. Отличается от MuxSend только проверкой, что канал
  действительно обслуживается: ответ на чужой или свободный канал — это
  дефект приложения, и молчать о нём нельзя. }
function MuxReply(Chan: Byte; const F: TFrame; More: Boolean): TResult;

{ Забрать байт для передачи в линию. False — отправлять нечего. }
function MuxOutByte(var B: Byte): Boolean;
function MuxOutPending: Word;

{ Скормить байт, пришедший из линии. }
procedure MuxFeedByte(B: Byte);

{ Сообщить о паузе в линии: недособранный кадр бросается (см. TcFrame). }
procedure MuxIdle;

function MuxChanState(Chan: Byte): TChanState;
function MuxActive: Integer;
function MuxGetStats: TMuxStats;

implementation

type
  TChannel = record
    State: TChanState;
    Owner: TFiberId;
  end;

var
  Chans: array[0..255] of TChannel;
  Ring:  array[0..OutRingBytes - 1] of Byte;
  Head, Tail, Used: Word;      { Head — откуда читать, Tail — куда писать }
  { Имя Rx, а не Dec: Dec — встроенная процедура уменьшения, и
    переменная с таким именем её перекрывает. Компилятор сообщает об
    этом не там, где объявление, а там, где вызов. }
  Rx:      TDecoder;
  Stats:   TMuxStats;
  Control: TControlHandler;
  Command: TCommandHandler;

procedure MuxReset;
var
  I: Integer;
begin
  for I := 0 to 255 do
  begin
    Chans[I].State := csFree;
    Chans[I].Owner := SchedulerId;
  end;
  Head := 0;
  Tail := 0;
  Used := 0;
  DecoderReset(Rx);
  FillChar(Stats, SizeOf(Stats), 0);
  Control := nil;
  Command := nil;
end;

procedure MuxSetControlHandler(H: TControlHandler);
begin
  Control := H;
end;

procedure MuxSetCommandHandler(H: TCommandHandler);
begin
  Command := H;
end;

procedure MuxSetOwner(Chan: Byte; Owner: TFiberId);
begin
  Chans[Chan].Owner := Owner;
end;

function MuxOwner(Chan: Byte): TFiberId;
begin
  MuxOwner := Chans[Chan].Owner;
end;

procedure MuxRelease(Chan: Byte);
begin
  Chans[Chan].State := csFree;
  Chans[Chan].Owner := SchedulerId;
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

function MuxReply(Chan: Byte; const F: TFrame; More: Boolean): TResult;
var
  G: TFrame;
  Res: TResult;
begin
  if (Chan < FirstDataChan) or (Chan > LastDataChan) then
  begin
    MuxReply := Err('DECIDER_PANIC');
    Exit;
  end;
  if Chans[Chan].State = csFree then
  begin
    { Ответ на канал, которого нода не обслуживает. Это не порча на
      линии, а дефект приложения: оно отвечает после освобождения канала
      или на чужой номер. }
    MuxReply := Err('DECIDER_PANIC');
    Exit;
  end;

  G := F;
  G.Channel := Chan;
  if More then
    G.Flags := G.Flags or FlagMore
  else
    { Маска, а не not: not над байтовой константой в диалекте TP даёт
      знаковое целое, и результат and с байтом становится неочевиден. }
    G.Flags := G.Flags and (255 - FlagMore);

  { Результат через промежуточную переменную: обращение к полю функции по
    её собственному имени компилятор понимает как рекурсивный вызов. }
  Res := MuxSend(G);
  if Res.Ok and (not More) then
    Chans[Chan].State := csAnswered;
  MuxReply := Res;
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

{ Разобранный кадр разводится по назначению. }
procedure Route(const F: TFrame);
var
  C: Byte;
  First, Last: Boolean;
begin
  Inc(Stats.Received);
  C := F.Channel;

  { Управляющий канал обслуживается отдельно: его кадры не адресованы
    ни одной команде. }
  if C = ChanControl then
  begin
    Inc(Stats.Control);
    if @Control <> nil then
      Control(F);
    Exit;
  end;

  if (C < FirstDataChan) or (C > LastDataChan) then
  begin
    { Номер вне диапазона данных: либо порча, пережившая CRC, либо
      гейтвей нумерует не так, как договорились. И то и другое надо
      видеть, а не молча глотать. }
    Inc(Stats.Unrouted);
    Exit;
  end;

  Last := (F.Flags and FlagMore) = 0;
  First := Chans[C].State = csFree;

  if First then
  begin
    Inc(Stats.Commands);
    Chans[C].State := csServing;
    Chans[C].Owner := SchedulerId;
  end
  else if Chans[C].State = csAnswered then
  begin
    { Кадр на канал, ответ по которому уже отправлен, а приложение его
      ещё не освободило. Принять его как продолжение значило бы
      приписать новую команду закончившейся. }
    Inc(Stats.Refused);
    Exit;
  end
  else
    Inc(Stats.Continued);

  if Last then
    Inc(Stats.Completed);

  if @Command <> nil then
    Command(C, F, First, Last);
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

function MuxActive: Integer;
var
  I, N: Integer;
begin
  N := 0;
  for I := FirstDataChan to LastDataChan do
    if Chans[I].State <> csFree then
      Inc(N);
  MuxActive := N;
end;

function MuxGetStats: TMuxStats;
begin
  MuxGetStats := Stats;
end;

begin
  MuxReset;
end.
