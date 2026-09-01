{ ===================================================================
  Тесты драйвера последовательного порта.

  На нативном таргете линия заменена петлёй с управляемым содержимым:
  тест сам решает, что «пришло» и забирает то, что «ушло». Проверяется
  логика — перекачка, рукопожатие, обнаружение паузы, — а она одинакова
  на обоих таргетах.

  Настоящие регистры 16550 проверить нативно невозможно, и подделывать
  это нечестно. Зато рукопожатие и обрыв — те самые места, на которых
  сгорел прогон спайка S2, — проверяются здесь полностью.

  Часы виртуальные: тест двигает время сам.
  =================================================================== }
program TstPort;

{$MODE TP}
{$R-}

uses
  TcResult, TcTest, TcSched, TcFrame, TcMux, TcPort;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile      = 'TSTPORT.TAP';
  PlannedTests = 6;
{$ELSE}
  TapFile      = 'tstport.tap';
  PlannedTests = 29;
{$ENDIF}

var
  R: TResult;
  St: TPortStats;
  MSt: TMuxStats;
  F: TFrame;
  { Нода — сервер (ADR-0015): кадр, вернувшийся по петле, приходит как
    входящая команда, а не как ответ на открытый нами канал. }
  GotChan: Byte;
  GotLen:  Word;
  GotCount: Integer;
  Chan: Byte;
  Owner: TFiberId;
  I, N: Integer;
  B: Byte;
  Now: LongInt;

procedure OnCommand(Chan: Byte; const Fr: TFrame;
                    First, Last: Boolean); far;
begin
  GotChan := Chan;
  GotLen := Fr.Len;
  Inc(GotCount);
end;

procedure MakeFrame(var Fr: TFrame; C: Byte; Len: Word; Seed: Byte);
var
  K: Word;
begin
  FillChar(Fr, SizeOf(Fr), 0);
  Fr.Channel := C;
  Fr.Len := Len;
  if Len > 0 then
    for K := 0 to Len - 1 do
      Fr.Payload[K] := Byte((K + Seed) and $FF);
end;

{$IFNDEF CPU16}
{ Сколько байт драйвер отправил в линию. }
function DrainOut: Integer;
var
  Bt: Byte;
  Cnt: Integer;
begin
  Cnt := 0;
  while PortTaken(Bt) do Inc(Cnt);
  DrainOut := Cnt;
end;

{ Полный круг через линию: то, что драйвер отправил, возвращается ему
  же на вход. Заходов делается с запасом, потому что перекачка
  ограничена MaxPumpBytes и кадр крупнее предела проходит за несколько. }
procedure Circulate(Ticks: LongInt; Rounds: Integer);
const
  Chunk = 256;
var
  Bt: Byte;
  Tmp: array[0..Chunk - 1] of Byte;
  K, Got, Round: Integer;
begin
  for Round := 1 to Rounds do
  begin
    PortPump(Ticks);
    Got := 0;
    while (Got < Chunk) and PortTaken(Bt) do
    begin
      Tmp[Got] := Bt;
      Inc(Got);
    end;
    for K := 0 to Got - 1 do
      PortInject(Tmp[K]);
    PortPump(Ticks);
  end;
end;
{$ENDIF}

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('драйвер последовательного порта');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos, настоящие регистры');
{$ELSE}
  TestDiag('таргет: нативный, петля');
{$ENDIF}

  MuxReset;
  SchedReset;
  Now := 0;

  { ================================================================
    Открытие — проверяется одинаково
    ================================================================ }

  R := PortOpen(Com1Base, 0);
  TestResultErr('нулевой делитель отвергается', R, ERR_INSUFFICIENT_CONTEXT);

  MuxReset;
  R := PortOpen(Com1Base, Divisor115200);
  TestResultOk('порт открывается', R);
  TestEqInt('после открытия объявляем готовность',
            Ord(PortState), Ord(psAnnouncing));
  TestFalse('до ответа другая сторона не готова', PortReady);

  St := PortGetStats;
  TestEqInt('приветствие отправлено сразу', St.HellosOut, 1);
  TestTrue('приветствие лежит в кольце на отправку', MuxOutPending > 0);

{$IFNDEF CPU16}

  { ================================================================
    Рукопожатие

    Место, на котором сгорел прогон спайка S2: DOSBox открывает сокет
    задолго до запуска программы, а инициализация UART чистит FIFO.
    ================================================================ }

  TestTrue('перекачка вытолкнула приветствие в линию', PortPump(Now));
  TestTrue('в линии появились байты', DrainOut > 0);

  { Другая сторона отвечает приветствием. }
  MakeFrame(F, ChanControl, 0, 0);
  F.Flags := FlagHello;
  R := PortInjectFrame(F);
  TestResultOk('приветствие другой стороны положено в линию', R);

  PortPump(Now);
  TestEqInt('после ответа обе стороны готовы', Ord(PortState), Ord(psReady));
  TestTrue('готовность видна снаружи', PortReady);

  St := PortGetStats;
  TestEqInt('приветствие другой стороны учтено', St.HellosIn, 1);
  TestTrue('на ответ отправлено подтверждение', St.HellosOut >= 2);

  { Пока другая сторона молчит, приветствие повторяется. }
  MuxReset;
  R := PortOpen(Com1Base, Divisor115200);
  Now := 0;
  PortPump(Now);
  DrainOut;
  St := PortGetStats;
  I := St.HellosOut;
  Now := HelloEveryTicks + 1;
  PortPump(Now);
  St := PortGetStats;
  TestTrue('пока другая сторона молчит, приветствие повторяется',
           St.HellosOut > I);

  { ================================================================
    Круг через линию
    ================================================================ }

  MuxReset;
  SchedReset;
  R := PortOpen(Com1Base, Divisor115200);
  Now := 0;
  PortPump(Now);
  DrainOut;

  MuxSetCommandHandler(OnCommand);
  GotCount := 0;
  Chan := 1;
  MakeFrame(F, Chan, 64, 5);
  R := MuxSend(F);
  TestResultOk('кадр поставлен в очередь', R);

  { Перекачка ограничена намеренно, и это видно снаружи: кадр в 72 байта
    за один заход не уходит. Проверяется именно предел — без него цикл
    событий застрял бы на линии при быстром отправителе. }
  PortPump(Now);
  TestEqInt('за один заход уходит не больше предела',
            DrainOut, MaxPumpBytes);

  N := MaxPumpBytes;
  for I := 1 to 4 do
  begin
    PortPump(Now);
    Inc(N, DrainOut);
  end;
  TestEqInt('за несколько заходов кадр уходит целиком',
            N, HeaderBytes + 64 + TrailerBytes);

  { Полный круг: то, что ушло, возвращается на вход. Рукопожатие при
    этом проходит само — своё же приветствие приходит обратно. }
  MuxReset;
  SchedReset;
  R := PortOpen(Com1Base, Divisor115200);
  Now := 0;
  Circulate(Now, 4);
  TestTrue('петля замкнула рукопожатие', PortReady);

  MuxSetCommandHandler(OnCommand);
  GotCount := 0;
  Chan := 1;
  MakeFrame(F, Chan, 64, 5);
  R := MuxSend(F);
  Circulate(Now, 4);

  TestEqInt('кадр вернулся через линию и дошёл до обработчика',
            GotCount, 1);
  TestEqInt('номер канала сохранён', GotChan, Chan);
  TestEqInt('длина сохранена', GotLen, 64);

  St := PortGetStats;
  TestTrue('принятые байты посчитаны', St.RxBytes > 0);
  TestTrue('отправленные байты посчитаны', St.TxBytes > 0);

  { ================================================================
    Обнаружение паузы
    ================================================================ }

  MuxReset;
  SchedReset;
  R := PortOpen(Com1Base, Divisor115200);
  Now := 0;
  PortPump(Now);
  DrainOut;

  MuxSetCommandHandler(OnCommand);
  GotCount := 0;
  Chan := 1;
  MakeFrame(F, Chan, 200, 3);
  R := MuxSend(F);
  PortPump(Now);

  { Половина ушедшего возвращается, дальше линия молчит. }
  N := 0;
  while (N < 100) and PortTaken(B) do
  begin
    PortInject(B);
    Inc(N);
  end;
  DrainOut;
  Now := 1;
  PortPump(Now);

  MSt := MuxGetStats;
  TestEqInt('недособранный кадр не отдан обработчику', MSt.Commands, 0);

  { Молчание дольше порога — обрыв. }
  Now := 10;
  PortPump(Now);
  St := PortGetStats;
  TestEqInt('пауза замечена и передана мультиплексору', St.Idles, 1);

  { Повторять сигнал на каждом тике незачем. }
  Now := 20;
  PortPump(Now);
  St := PortGetStats;
  TestEqInt('сигнал о паузе не повторяется', St.Idles, 1);

  { После паузы следующий кадр собирается. }
  MakeFrame(F, Chan, 32, 9);
  R := PortInjectFrame(F);
  Now := 21;
  PortPump(Now);
  TestEqInt('после паузы следующий кадр доставлен', GotCount, 1);

  { ================================================================
    Закрытие
    ================================================================ }

  PortClose;
  TestEqInt('закрытый порт в исходном состоянии',
            Ord(PortState), Ord(psClosed));
  TestFalse('закрытый порт не перекачивает', PortPump(Now));

{$ENDIF}

  Halt(TestEnd);
end.
