{ ===================================================================
  Спайк S2 — последовательный транспорт: DOS-сторона.

  Эта программа ничего не измеряет. Она работает эхо-ответчиком на COM1,
  а все замеры делает хост: у него есть микросекундные часы, а у DOS —
  тик BIOS с разрешением 55 мс, чего для латентности недостаточно.

  Порт программируется напрямую через регистры 16550, без INT 14h: именно
  так будет работать tcport в TurboCore, и мерить надо то, что поедет
  в бой.

  Аргумент командной строки — делитель скорости UART:
    1   = 115200 бод
    12  = 9600 бод
  Прогон с разными делителями отвечает на главный вопрос спайка:
  ограничивает ли DOSBox скорость по-настоящему.

  Собственные счётчики пишутся в S2.TAP, чтобы сверить их с тем, что
  насчитал хост: расхождение означало бы потерю байтов.

  Одноразовый код.
  =================================================================== }
program S2Serial;

{$MODE TP}
{$S-}
{$R-}
{$Q-}

uses
  Ports;

const
  COM1_BASE = $3F8;

  { Смещения регистров 16550 }
  REG_DATA = 0;   { RBR/THR при DLAB=0, делитель младший при DLAB=1 }
  REG_IER  = 1;   { делитель старший при DLAB=1 }
  REG_FCR  = 2;
  REG_LCR  = 3;
  REG_MCR  = 4;
  REG_LSR  = 5;

  LSR_RX_READY  = $01;
  LSR_TX_EMPTY  = $20;
  LSR_OVERRUN   = $02;
  LSR_ANY_ERROR = $1E;   { overrun, parity, framing, break }

  BYTE_EOT   = 4;        { хост завершает прогон этим байтом }
  BYTE_READY = 6;        { DOS сообщает, что порт настроен и приём начат }

  IDLE_TICKS = 182;      { 10 секунд тишины — считаем, что хост отвалился }

var
  Report:   Text;
  Echoed:   LongInt;
  Overruns: LongInt;
  Errors:   LongInt;
  Divisor:  Word;
  gTmp:     string;

function Ticks: LongInt;
begin
  Ticks := MemL[$0040:$006C];
end;

procedure InitUart(D: Word);
begin
  Port[COM1_BASE + REG_LCR] := $80;          { DLAB = 1 }
  Port[COM1_BASE + REG_DATA] := Lo(D);
  Port[COM1_BASE + REG_IER]  := Hi(D);
  Port[COM1_BASE + REG_LCR] := $03;          { DLAB = 0, 8 бит, без чётности, 1 стоп }
  Port[COM1_BASE + REG_IER] := $00;          { прерывания выключены: работаем опросом }
  Port[COM1_BASE + REG_FCR] := $C7;          { FIFO включён, очищен, порог 14 байт }
  Port[COM1_BASE + REG_MCR] := $03;          { DTR и RTS подняты }
end;

function RxReady: Boolean;
begin
  RxReady := (Port[COM1_BASE + REG_LSR] and LSR_RX_READY) <> 0;
end;

function TxReady: Boolean;
begin
  TxReady := (Port[COM1_BASE + REG_LSR] and LSR_TX_EMPTY) <> 0;
end;

procedure Emit(const S: string);
begin
  WriteLn(Report, S);
  Flush(Report);
end;

procedure DiagNum(const S: string; N: LongInt);
var
  T: string;
begin
  Str(N, T);
  Emit('# ' + S + ' ' + T);
end;

{ Эхо-цикл. Возвращает False, если вышли по таймауту, а не по EOT. }
function EchoLoop: Boolean;
var
  B, Status: Byte;
  Deadline: LongInt;
begin
  EchoLoop := False;
  Deadline := Ticks + IDLE_TICKS;

  while Ticks < Deadline do
  begin
    Status := Port[COM1_BASE + REG_LSR];

    if (Status and LSR_OVERRUN) <> 0 then
      Inc(Overruns);
    if (Status and LSR_ANY_ERROR) <> 0 then
      Inc(Errors);

    if (Status and LSR_RX_READY) <> 0 then
    begin
      B := Port[COM1_BASE + REG_DATA];

      if B = BYTE_EOT then
      begin
        EchoLoop := True;
        Exit;
      end;

      while not TxReady do
        ;
      Port[COM1_BASE + REG_DATA] := B;
      Inc(Echoed);

      { Пока идёт обмен, таймаут не наступает. }
      Deadline := Ticks + IDLE_TICKS;
    end;
  end;
end;

var
  CleanExit: Boolean;

begin
  Assign(Report, 'S2.TAP');
  Rewrite(Report);

  Echoed   := 0;
  Overruns := 0;
  Errors   := 0;

  Val(ParamStr(1), Divisor, Errors);
  if (Divisor = 0) then
    Divisor := 1;
  Errors := 0;

  Emit('1..3');
  Emit('# spike S2 - serial echo responder on COM1');
  DiagNum('divisor', Divisor);
  DiagNum('baud', 115200 div Divisor);

  InitUart(Divisor);

  { Рукопожатие. DOSBox подключает сокет ещё на старте эмулятора, задолго до
    запуска программы, а InitUart очищает FIFO. Всё, что хост успел прислать
    до этой точки, потеряно. Поэтому обмен начинается только после того, как
    DOS объявит готовность — иначе первые килобайты уходят в никуда.
    Это не костыль спайка: гейтвей столкнётся ровно с тем же. }
  while not TxReady do
    ;
  Port[COM1_BASE + REG_DATA] := BYTE_READY;
  Emit('# uart initialised, READY sent');

  CleanExit := EchoLoop;

  DiagNum('echoed bytes', Echoed);
  DiagNum('overruns', Overruns);
  DiagNum('line errors', Errors);

  if CleanExit then
    Emit('ok 1 - завершение по EOT от хоста')
  else
    Emit('not ok 1 - таймаут: хост замолчал');

  if Overruns = 0 then
    Emit('ok 2 - переполнений приёмника нет')
  else
    Emit('not ok 2 - приёмник переполнялся, байты потеряны');

  if Errors = 0 then
    Emit('ok 3 - ошибок линии нет')
  else
    Emit('not ok 3 - ошибки линии');

  Str(Echoed, gTmp);
  Emit('# ИТОГ: отражено байт ' + gTmp);

  Close(Report);
  Halt(0);
end.
