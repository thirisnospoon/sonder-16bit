{ ===================================================================
  TurboCore · драйвер последовательного порта.

  Работа с 16550 напрямую через регистры, без INT 14h: прерывание BIOS
  блокирует и не даёт неблокирующего чтения, а нам нужно именно оно —
  цикл событий не имеет права ждать на линии.

  ДВЕ РЕАЛИЗАЦИИ, как и у переключения контекста.

    i8086-msdos — настоящие порты;
    нативный    — петля с управляемым содержимым. Логика перекачки,
                  рукопожатия и обнаружения паузы одинакова, и именно
                  она проверяется тестами.

  РУКОПОЖАТИЕ ОБЯЗАТЕЛЬНО, и это вывод из измерения, а не осторожность.
  DOSBox открывает сокет на старте эмулятора — задолго до того, как
  запустится программа. Всё, что гейтвей отправит до инициализации UART,
  будет потеряно молча: приёмник переполнится, а биты ошибок сбросятся
  первым же чтением LSR. В спайке S2 на этом сгорел целый прогон.

  Готовность объявляется УПРАВЛЯЮЩИМ КАДРОМ, а не голым байтом. Байт со
  специальным смыслом в потоке пришлось бы экранировать и учитывать
  везде; кадр обходится в восемь байт и разбирается уже существующим
  декодером.

  ПАУЗА В ЛИНИИ означает обрыв. Кадр передаётся непрерывно, поэтому
  молчание дольше времени передачи нескольких байт — признак того, что
  отправитель оборвался. Драйвер сообщает об этом мультиплексору, иначе
  недособранный кадр съел бы начало следующего (см. TcFrame).
  =================================================================== }
unit TcPort;

{$MODE TP}
{$R-}

interface

uses
  TcResult, TcFrame, TcMux;

const
  Com1Base = $3F8;
  Com2Base = $2F8;

  { Делитель 1 даёт 115200 бод — предел стандартного 16550 и измеренный
    в S2 потолок линии: 11 503 Б/с на направление. }
  Divisor115200 = 1;
  Divisor9600   = 12;

  { Сколько тиков молчания считать обрывом. При 18.2 Гц один тик это
    55 мс — заведомо больше времени передачи кадра, но заведомо меньше
    любого разумного срока команды. }
  IdleTicks = 2;

  { Как часто повторять приветствие, пока другая сторона молчит. }
  HelloEveryTicks = 4;

  { Сколько байт перекачивать за один заход, в каждую сторону отдельно.
    Предел нужен, чтобы цикл событий не застревал на линии: при быстром
    отправителе перекачка без предела не вернула бы управление файберам
    вовсе. Следствие видно снаружи — кадр крупнее предела уходит за
    несколько заходов, — поэтому число объявлено здесь, а не спрятано
    в реализации. }
  MaxPumpBytes = 64;

type
  TPortState = (
    psClosed,
    psAnnouncing,   { мы объявили готовность, ответа нет }
    psReady         { обе стороны готовы }
  );

  TPortStats = record
    RxBytes:   LongInt;
    TxBytes:   LongInt;
    Overruns:  LongInt;   { приёмник не успел, байты потеряны }
    LineErrs:  LongInt;   { чётность, кадр, разрыв }
    Idles:     LongInt;   { сигналов о паузе, отданных мультиплексору }
    HellosOut: LongInt;
    HellosIn:  LongInt;
  end;

function PortOpen(Base: Word; Divisor: Word): TResult;
procedure PortClose;

function PortState: TPortState;
function PortReady: Boolean;

{ Перекачать байты между линией и мультиплексором. Вызывается циклом
  событий каждый тик. Now — текущий тик источника времени.
  Возвращает True, если было движение в любую сторону. }
function PortPump(Now: LongInt): Boolean;

function PortGetStats: TPortStats;

{$IFNDEF CPU16}
{ Только на нативном таргете: управление петлёй.

  PortInject кладёт байт так, будто он пришёл из линии.
  PortTaken забирает байт, который драйвер отправил.
  Петля позволяет проверить перекачку и рукопожатие без DOSBox. }
procedure PortInject(B: Byte);
function PortTaken(var B: Byte): Boolean;
function PortInjectFrame(const F: TFrame): TResult;
{$ENDIF}

implementation

{$IFDEF CPU16}
uses
  Ports;
{$ENDIF}

const
  { Смещения регистров 16550. }
  RegData = 0;   { RBR/THR, либо младший байт делителя при DLAB=1 }
  RegIer  = 1;   { либо старший байт делителя }
  RegFcr  = 2;
  RegLcr  = 3;
  RegMcr  = 4;
  RegLsr  = 5;

  LsrRxReady  = $01;
  LsrOverrun  = $02;
  LsrTxEmpty  = $20;
  LsrAnyError = $1E;   { переполнение, чётность, кадр, разрыв }

var
  { Имя PortBase, а не Base: параметр PortOpen называется Base, и
    одноимённая переменная модуля его перекрыла бы. Имена параметров
    в интерфейсе и реализации при этом обязаны совпадать. }
  PortBase:  Word;
  State:     TPortState;
  Stats:     TPortStats;
  LastRx:    LongInt;    { когда последний раз что-то пришло }
  LastHello: LongInt;
  IdleSent:  Boolean;    { сигнал о паузе уже отдан, повторять незачем }

{$IFNDEF CPU16}
const
  LoopCap = 4096;
var
  { Петля нативного таргета: то, что «пришло» и то, что «ушло». }
  InBuf:  array[0..LoopCap - 1] of Byte;
  InHead, InTail: Word;
  OutBuf: array[0..LoopCap - 1] of Byte;
  OutHead, OutTail: Word;
{$ENDIF}

{ ------------------------------------------------------------------
  Низкий уровень: чтение и запись одного байта
  ------------------------------------------------------------------ }

{$IFDEF CPU16}

function RawStatus: Byte;
begin
  RawStatus := Port[PortBase + RegLsr];
end;

function RawRead(var B: Byte): Boolean;
var
  S: Byte;
begin
  S := Port[PortBase + RegLsr];

  if (S and LsrOverrun) <> 0 then
    Inc(Stats.Overruns);
  if (S and LsrAnyError) <> 0 then
    Inc(Stats.LineErrs);

  if (S and LsrRxReady) = 0 then
  begin
    RawRead := False;
    Exit;
  end;
  B := Port[PortBase + RegData];
  RawRead := True;
end;

function RawTxReady: Boolean;
begin
  RawTxReady := (Port[PortBase + RegLsr] and LsrTxEmpty) <> 0;
end;

function RawWrite(B: Byte): Boolean;
begin
  if not RawTxReady then
  begin
    RawWrite := False;
    Exit;
  end;
  Port[PortBase + RegData] := B;
  RawWrite := True;
end;

procedure RawOpen(Divisor: Word);
begin
  Port[PortBase + RegLcr] := $80;           { DLAB = 1 }
  Port[PortBase + RegData] := Lo(Divisor);
  Port[PortBase + RegIer]  := Hi(Divisor);
  Port[PortBase + RegLcr] := $03;           { DLAB = 0, 8 бит, без чётности, 1 стоп }
  Port[PortBase + RegIer] := $00;           { прерывания выключены: работаем опросом }
  Port[PortBase + RegFcr] := $C7;           { FIFO включён, очищен, порог 14 байт }
  Port[PortBase + RegMcr] := $03;           { DTR и RTS подняты }
end;

procedure RawClose;
begin
  Port[PortBase + RegMcr] := $00;           { снять DTR и RTS }
end;

{$ELSE}

function RawRead(var B: Byte): Boolean;
begin
  if InHead = InTail then
  begin
    RawRead := False;
    Exit;
  end;
  B := InBuf[InHead];
  Inc(InHead);
  if InHead >= LoopCap then InHead := 0;
  RawRead := True;
end;

function RawTxReady: Boolean;
var
  Next: Word;
begin
  { В петле передатчик не готов ровно тогда, когда её буфер полон:
    так и проверяется путь «передатчик занят», которого на нативном
    таргете иначе не увидеть, а на настоящем UART он обычное дело. }
  Next := OutTail + 1;
  if Next >= LoopCap then Next := 0;
  RawTxReady := Next <> OutHead;
end;

function RawWrite(B: Byte): Boolean;
var
  Next: Word;
begin
  Next := OutTail + 1;
  if Next >= LoopCap then Next := 0;
  if Next = OutHead then
  begin
    { Петля заполнена: тест не забирает отправленное. Это дефект теста,
      а не драйвера, но вести себя надо как занятый передатчик. }
    RawWrite := False;
    Exit;
  end;
  OutBuf[OutTail] := B;
  OutTail := Next;
  RawWrite := True;
end;

procedure RawOpen(Divisor: Word);
begin
  InHead := 0; InTail := 0;
  OutHead := 0; OutTail := 0;
end;

procedure RawClose;
begin
end;

procedure PortInject(B: Byte);
var
  Next: Word;
begin
  Next := InTail + 1;
  if Next >= LoopCap then Next := 0;
  if Next = InHead then Exit;   { петля полна, байт теряется как на линии }
  InBuf[InTail] := B;
  InTail := Next;
end;

function PortTaken(var B: Byte): Boolean;
begin
  if OutHead = OutTail then
  begin
    PortTaken := False;
    Exit;
  end;
  B := OutBuf[OutHead];
  Inc(OutHead);
  if OutHead >= LoopCap then OutHead := 0;
  PortTaken := True;
end;

function PortInjectFrame(const F: TFrame): TResult;
var
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  N, I: Word;
  R: TResult;
begin
  R := FrameEncode(F, Buf, SizeOf(Buf), N);
  if R.Ok then
    for I := 0 to N - 1 do
      PortInject(Buf[I]);
  PortInjectFrame := R;
end;

{$ENDIF}

{ ------------------------------------------------------------------
  Рукопожатие
  ------------------------------------------------------------------ }

{ Приветствие кладётся в кольцо мультиплексора как обычный кадр: так
  оно проходит через тот же кодировщик и тот же декодер, что и всё
  остальное, и не требует особого случая нигде. }
procedure SendHello;
var
  F: TFrame;
  R: TResult;
begin
  FillChar(F, SizeOf(F), 0);
  F.Channel := ChanControl;
  F.Flags := FlagHello;
  F.Len := 0;
  R := MuxSend(F);
  if R.Ok then
    Inc(Stats.HellosOut);
end;

{ Обработчик управляющего канала. Приветствие от другой стороны означает,
  что она проинициализировалась и готова принимать. }
procedure OnControl(const F: TFrame); far;
begin
  if (F.Flags and FlagHello) <> 0 then
  begin
    Inc(Stats.HellosIn);
    if State = psAnnouncing then
    begin
      State := psReady;
      { Отвечаем ещё раз: другая сторона могла не увидеть наше
        приветствие, если оно ушло до её готовности. }
      SendHello;
    end;
  end;
end;

{ ------------------------------------------------------------------ }

function PortOpen(Base: Word; Divisor: Word): TResult;
begin
  if Divisor = 0 then
  begin
    PortOpen := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  PortBase := Base;
  FillChar(Stats, SizeOf(Stats), 0);
  LastRx := 0;
  LastHello := 0;
  IdleSent := True;

  RawOpen(Divisor);

  MuxSetControlHandler(OnControl);

  State := psAnnouncing;
  SendHello;

  PortOpen := Ok;
end;

procedure PortClose;
begin
  RawClose;
  State := psClosed;
end;

function PortState: TPortState;
begin
  PortState := State;
end;

function PortReady: Boolean;
begin
  PortReady := State = psReady;
end;

function PortPump(Now: LongInt): Boolean;
var
  B: Byte;
  N: Integer;
  Moved: Boolean;
begin
  Moved := False;

  if State = psClosed then
  begin
    PortPump := False;
    Exit;
  end;

  { Приём. Предел на заход обязателен: без него быстрый отправитель
    не дал бы циклу событий вернуть управление файберам. }
  N := 0;
  while (N < MaxPumpBytes) and RawRead(B) do
  begin
    MuxFeedByte(B);
    Inc(Stats.RxBytes);
    Inc(N);
    Moved := True;
  end;

  if Moved then
  begin
    LastRx := Now;
    IdleSent := False;
  end
  else if (not IdleSent) and (Now - LastRx >= IdleTicks) then
  begin
    { Молчание дольше порога посреди кадра означает обрыв. Сообщаем
      один раз: повторять на каждом тике незачем. }
    MuxIdle;
    Inc(Stats.Idles);
    IdleSent := True;
  end;

  { Передача.

    ГОТОВНОСТЬ ПЕРЕДАТЧИКА ПРОВЕРЯЕТСЯ ДО ТОГО, КАК БАЙТ ЗАБРАН ИЗ
    КОЛЬЦА. Прежде порядок был обратным, и это стоило всей линии:
    забранный байт вернуть некуда — кольцо однонаправленное, — поэтому
    каждый отказ передатчика рвал кадр посередине. Первый же сквозной
    прогон показал 74 отправленных байта против 150 потерянных: целым не
    доезжал ни один кадр, а выглядело это как «нода молчит».

    Прежний комментарий уверял, что попасть в эту ветку трудно.
    Уверенность была основана на нативной петле, которая принимает байт
    всегда; на настоящем UART при 115200 цикл опроса крутится много
    быстрее, чем уходит байт, и «трудно» оказалось нормой. }
  N := 0;
  while N < MaxPumpBytes do
  begin
    if MuxOutPending = 0 then Break;
    if not RawTxReady then Break;
    if not MuxOutByte(B) then Break;
    if not RawWrite(B) then
    begin
      { Сюда попасть можно только если готовность пропала между
        проверкой и записью — на опросе такого не бывает, но считать
        всё равно надо: замолчавший счётчик хуже растущего. }
      Inc(Stats.LineErrs);
      Break;
    end;
    Inc(Stats.TxBytes);
    Inc(N);
    Moved := True;
  end;

  { Пока другая сторона молчит, повторяем приветствие. }
  if (State = psAnnouncing) and (Now - LastHello >= HelloEveryTicks) then
  begin
    SendHello;
    LastHello := Now;
  end;

  PortPump := Moved;
end;

function PortGetStats: TPortStats;
begin
  PortGetStats := Stats;
end;

begin
  State := psClosed;
  PortBase := Com1Base;
  FillChar(Stats, SizeOf(Stats), 0);
end.
