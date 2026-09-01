{ ===================================================================
  S2, диагностический прогон.

  Отвечает на три вопроса по порядку, потому что «не пришло ни байта»
  может означать что угодно:

    1. Читаются ли вообще порты? Проверяется записью делителя и его
       обратным чтением: если Port[] не работает, вернётся не то, что
       записали.
    2. Жив ли UART? У исправного порта LSR в покое равен $60 —
       передатчик и сдвиговый регистр пусты. Ноль означает, что порта нет.
    3. Что показывают линии модема? MSR скажет, считает ли DOSBox
       соединение установленным.

  Затем программа десять секунд шлёт байты и записывает всё, что
  приходит в ответ.
  =================================================================== }
program S2Probe;

{$MODE TP}
{$S-}
{$R-}
{$Q-}

uses
  Ports;

const
  COM1_BASE = $3F8;
  REG_DATA  = 0;
  REG_IER   = 1;
  REG_IIR   = 2;
  REG_LCR   = 3;
  REG_MCR   = 4;
  REG_LSR   = 5;
  REG_MSR   = 6;

var
  Report: Text;
  Got:    LongInt;
  Sent:   LongInt;

function Ticks: LongInt;
begin
  Ticks := MemL[$0040:$006C];
end;

procedure Emit(const S: string);
begin
  WriteLn(Report, S);
  Flush(Report);
end;

procedure DiagHex(const S: string; V: Byte);
const
  Digits: array[0..15] of Char = '0123456789ABCDEF';
begin
  Emit('# ' + S + ' $' + Digits[V shr 4] + Digits[V and $0F]);
end;

procedure DiagNum(const S: string; N: LongInt);
var
  T: string;
begin
  Str(N, T);
  Emit('# ' + S + ' ' + T);
end;

var
  Back, LsrIdle, Msr, Iir: Byte;
  Deadline, NextSend: LongInt;
  B: Byte;
  PortsWork, UartAlive: Boolean;

begin
  Assign(Report, 'PROBE.TAP');
  Rewrite(Report);
  Emit('1..3');
  Emit('# S2 probe: доступность портов и состояние UART');

  { --- 1. Работает ли Port[] вообще --- }
  Port[COM1_BASE + REG_LCR] := $80;         { DLAB = 1 }
  Port[COM1_BASE + REG_DATA] := $0C;        { делитель = 12 }
  Port[COM1_BASE + REG_IER]  := $00;
  Back := Port[COM1_BASE + REG_DATA];       { читаем обратно }
  Port[COM1_BASE + REG_LCR] := $03;         { DLAB = 0, 8N1 }

  DiagHex('записали в делитель $0C, прочли', Back);
  PortsWork := Back = $0C;
  if PortsWork then
    Emit('ok 1 - порты доступны, запись и чтение сходятся')
  else
    Emit('not ok 1 - Port[] не работает или UART отсутствует');

  { --- 2. Жив ли UART --- }
  Port[COM1_BASE + REG_IER] := $00;
  Port[COM1_BASE + REG_IIR] := $C7;         { FCR: FIFO }
  Port[COM1_BASE + REG_MCR] := $03;         { DTR, RTS }

  LsrIdle := Port[COM1_BASE + REG_LSR];
  Msr     := Port[COM1_BASE + REG_MSR];
  Iir     := Port[COM1_BASE + REG_IIR];

  DiagHex('LSR в покое', LsrIdle);
  DiagHex('MSR', Msr);
  DiagHex('IIR', Iir);
  Emit('# LSR: бит0 данные, бит5 THR пуст, бит6 TSR пуст');
  Emit('# MSR: бит4 CTS, бит5 DSR, бит7 DCD');

  UartAlive := (LsrIdle and $20) <> 0;
  if UartAlive then
    Emit('ok 2 - передатчик готов, UART отвечает')
  else
    Emit('not ok 2 - LSR не показывает готовность передатчика');

  { --- 3. Обмен --- }
  Got  := 0;
  Sent := 0;
  Deadline := Ticks + 182;        { 10 секунд }
  NextSend := Ticks;

  while Ticks < Deadline do
  begin
    if (Port[COM1_BASE + REG_LSR] and $01) <> 0 then
    begin
      B := Port[COM1_BASE + REG_DATA];
      Inc(Got);
      if Got <= 8 then
        DiagHex('принят байт', B);
    end;

    if (Ticks >= NextSend) and ((Port[COM1_BASE + REG_LSR] and $20) <> 0) then
    begin
      Port[COM1_BASE + REG_DATA] := $41 + Byte(Sent and $0F);   { 'A'.. }
      Inc(Sent);
      NextSend := Ticks + 9;      { примерно раз в полсекунды }
    end;
  end;

  DiagNum('отправлено байт', Sent);
  DiagNum('принято байт', Got);
  DiagHex('LSR в конце', Port[COM1_BASE + REG_LSR]);
  DiagHex('MSR в конце', Port[COM1_BASE + REG_MSR]);

  if Got > 0 then
    Emit('ok 3 - приём работает')
  else
    Emit('not ok 3 - не принято ни одного байта');

  Close(Report);
  Halt(0);
end.
