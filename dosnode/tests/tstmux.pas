{ ===================================================================
  Тесты мультиплексора.

  Нода — сервер (ADR-0011, ADR-0015), поэтому проверяется серверный
  жизненный цикл: кадр пришёл на свободный канал — значит началась
  команда; кадры с FlagMore продолжают её; кадр без флага её заканчивает;
  ответ уходит на тот же канал; приложение освобождает канал.

  Предыдущая редакция этих тестов описывала клиентский цикл — открыть
  канал, уснуть, дождаться ответа. Роль была не та, и тесты держали её
  зелёной. Они переписаны, а не дополнены.

  Отдельно — границы: канал вне диапазона, ответ на свободный канал,
  кадр после ответа, заполненное кольцо, пауза посреди кадра. Каждый из
  этих случаев на настоящей линии встретится, и ни один не должен
  ронять ноду.
  =================================================================== }
program TstMux;

{$MODE TP}
{$R-}

uses
  TcResult, TcTest, TcSched, TcFrame, TcMux;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile = 'TSTMUX.TAP';
{$ELSE}
  TapFile = 'tstmux.tap';
{$ENDIF}

  PlannedTests = 54;

var
  R: TResult;
  F: TFrame;
  I: Integer;
  B: Byte;
  Moved: Integer;
  St: TMuxStats;
  Sent: Integer;
  Before: Word;
  Owner: TFiberId;

  { След вызовов обработчика команд: по одному символу на кадр.
    «(» — первый кадр, «.» — продолжение, «)» — последний.
    Первый и последний кадр односегментного сообщения дают «*». }
  Trace: string;
  LastChan: Byte;
  Frames: Integer;
  Payload: LongInt;

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

{ Обработчик команд. far обязателен: в модели large переменная
  процедурного типа — дальний указатель. }
procedure OnCommand(Chan: Byte; const F: TFrame;
                    First, Last: Boolean); far;
begin
  LastChan := Chan;
  Inc(Frames);
  Inc(Payload, F.Len);
  if Length(Trace) > 200 then Exit;
  if First and Last then Trace := Trace + '*'
  else if First then Trace := Trace + '('
  else if Last then Trace := Trace + ')'
  else Trace := Trace + '.';
end;

procedure ClearTrace;
begin
  Trace := '';
  Frames := 0;
  Payload := 0;
  LastChan := 0;
end;

{ Отправить кадр в линию и тут же подать его обратно на вход: петля, на
  которой удобно проверять маршрутизацию без транспорта. }
function Loopback: Integer;
var
  N: Integer;
  Bt: Byte;
begin
  N := 0;
  while MuxOutByte(Bt) do
  begin
    MuxFeedByte(Bt);
    Inc(N);
  end;
  Loopback := N;
end;

{ Подать кадр на вход напрямую, минуя кольцо: так гейтвей и присылает
  команды — они не проходят через нашу очередь на отправку. }
procedure Inject(const Fr: TFrame; More: Boolean);
var
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  N, K: Word;
  G: TFrame;
  Rr: TResult;
begin
  G := Fr;
  if More then G.Flags := G.Flags or FlagMore;
  Rr := FrameEncode(G, Buf, SizeOf(Buf), N);
  if not Rr.Ok then Exit;
  for K := 0 to N - 1 do
    MuxFeedByte(Buf[K]);
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('мультиплексор, серверная сторона');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('размер кольца исходящих', OutRingBytes);
  TestDiagInt('каналов данных', LastDataChan);

  { ================================================================
    Входящая команда в один кадр
    ================================================================ }

  MuxReset;
  SchedReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;

  TestEqInt('после сброса каналы свободны',
            Ord(MuxChanState(3)), Ord(csFree));
  TestEqInt('после сброса активных каналов нет', MuxActive, 0);

  MakeFrame(F, 3, 100, 7);
  Inject(F, False);

  TestEqStr('односегментная команда — один вызов, первый и последний',
            Trace, '*');
  TestEqInt('канал команды дошёл до обработчика', LastChan, 3);
  TestEqInt('полезная нагрузка дошла целиком', Payload, 100);
  TestEqInt('канал занят обработкой',
            Ord(MuxChanState(3)), Ord(csServing));
  TestEqInt('канал числится активным', MuxActive, 1);

  St := MuxGetStats;
  TestEqInt('команда посчитана', St.Commands, 1);
  TestEqInt('сообщение завершено', St.Completed, 1);
  TestEqInt('продолжений не было', St.Continued, 0);
  TestEqInt('неприкаянных нет', St.Unrouted, 0);

  { ================================================================
    Ответ и освобождение канала
    ================================================================ }

  MakeFrame(F, 0, 40, 1);
  R := MuxReply(3, F, False);
  TestResultOk('ответ на обслуживаемый канал проходит', R);
  TestEqInt('ответ лёг в кольцо целиком',
            MuxOutPending, HeaderBytes + 40 + TrailerBytes);
  TestEqInt('после ответа канал ждёт освобождения',
            Ord(MuxChanState(3)), Ord(csAnswered));

  { Кадр на канал, ответ по которому уже отправлен. Принять его как
    продолжение значило бы приписать новую команду закончившейся. }
  ClearTrace;
  MakeFrame(F, 3, 8, 2);
  Inject(F, False);
  TestEqStr('кадр после ответа обработчику не отдан', Trace, '');
  St := MuxGetStats;
  TestEqInt('он посчитан как отклонённый', St.Refused, 1);

  MuxRelease(3);
  TestEqInt('освобождённый канал свободен',
            Ord(MuxChanState(3)), Ord(csFree));
  TestEqInt('активных каналов не осталось', MuxActive, 0);

  { ================================================================
    Многокадровое сообщение

    Конверт с телом поста на тысячу символов — около четырёх с половиной
    килобайт при полезной нагрузке кадра в 512. До ADR-0015 флаг FlagMore
    был объявлен и не обрабатывался никем.
    ================================================================ }

  MuxReset;
  SchedReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;

  MakeFrame(F, 5, MaxPayload, 1);
  Inject(F, True);
  MakeFrame(F, 5, MaxPayload, 2);
  Inject(F, True);
  MakeFrame(F, 5, 200, 3);
  Inject(F, False);

  TestEqStr('три кадра: начало, продолжение, конец', Trace, '(.)');
  TestEqInt('все кадры дошли до обработчика', Frames, 3);
  TestEqInt('нагрузка сложилась целиком',
            Payload, LongInt(MaxPayload) * 2 + 200);

  St := MuxGetStats;
  TestEqInt('команда одна на всё сообщение', St.Commands, 1);
  TestEqInt('продолжений два', St.Continued, 2);
  TestEqInt('завершение одно', St.Completed, 1);

  { Ответ тоже может не поместиться в кадр. }
  MakeFrame(F, 0, MaxPayload, 9);
  R := MuxReply(5, F, True);
  TestResultOk('первая часть ответа проходит', R);
  TestEqInt('после части с продолжением канал ещё обслуживается',
            Ord(MuxChanState(5)), Ord(csServing));
  MakeFrame(F, 0, 10, 9);
  R := MuxReply(5, F, False);
  TestResultOk('последняя часть ответа проходит', R);
  TestEqInt('после последней части канал ждёт освобождения',
            Ord(MuxChanState(5)), Ord(csAnswered));

  { ================================================================
    Одновременность

    Шестнадцать команд на разных каналах, кадры вперемешку. Разведение
    по каналам — единственная работа, ради которой модуль существует.
    ================================================================ }

  MuxReset;
  SchedReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;

  { Первые кадры всех шестнадцати. }
  for I := FirstDataChan to LastDataChan do
  begin
    MakeFrame(F, Byte(I), 40, Byte(I));
    Inject(F, True);
  end;
  TestEqInt('шестнадцать команд начаты', MuxActive, MaxFibers);

  { Вторые кадры в обратном порядке: перемешанность важнее порядка. }
  for I := LastDataChan downto FirstDataChan do
  begin
    MakeFrame(F, Byte(I), 40, Byte(I));
    Inject(F, False);
  end;

  St := MuxGetStats;
  TestEqInt('команд ровно шестнадцать', St.Commands, MaxFibers);
  TestEqInt('продолжений ровно шестнадцать', St.Continued, MaxFibers);
  TestEqInt('завершений ровно шестнадцать', St.Completed, MaxFibers);
  TestEqInt('кадров обработчику ровно тридцать два', Frames, MaxFibers * 2);

  { Ответы уходят в произвольном порядке: медленный ответ на одном канале
    не имеет права задержать остальные. }
  MuxReset;
  MuxSetCommandHandler(OnCommand);
  for I := FirstDataChan to LastDataChan do
  begin
    MakeFrame(F, Byte(I), 8, 0);
    Inject(F, False);
  end;
  Sent := 0;
  for I := LastDataChan downto FirstDataChan do
  begin
    MakeFrame(F, 0, 8, 0);
    R := MuxReply(Byte(I), F, False);
    if R.Ok then Inc(Sent);
  end;
  TestEqInt('ответы уходят в любом порядке', Sent, MaxFibers);

  { ================================================================
    Владелец канала
    ================================================================ }

  MuxReset;
  SchedReset;
  MuxSetCommandHandler(OnCommand);
  MakeFrame(F, 2, 8, 0);
  Inject(F, False);
  R := SchedSpawn('cmd', Owner);
  MuxSetOwner(2, Owner);
  TestEqInt('владелец канала записан', LongInt(MuxOwner(2)), LongInt(Owner));
  MuxRelease(2);
  TestEqInt('после освобождения владелец сброшен',
            LongInt(MuxOwner(2)), LongInt(SchedulerId));

  { ================================================================
    Границы
    ================================================================ }

  MuxReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;

  { Номер вне диапазона данных: либо порча, пережившая CRC, либо гейтвей
    нумерует не так, как договорились. }
  MakeFrame(F, 200, 8, 1);
  Inject(F, False);
  TestEqStr('кадр вне диапазона обработчику не отдан', Trace, '');
  St := MuxGetStats;
  TestEqInt('он посчитан как неприкаянный', St.Unrouted, 1);

  { Ответ на свободный канал — дефект приложения, а не линии. }
  MakeFrame(F, 0, 8, 1);
  R := MuxReply(7, F, False);
  TestResultErr('ответ на свободный канал отвергается', R, ERR_DECIDER_PANIC);
  R := MuxReply(200, F, False);
  TestResultErr('ответ на канал вне диапазона отвергается',
                R, ERR_DECIDER_PANIC);

  { Без обработчика команды принимаются и отбрасываются: канал в порядке,
    просто некому обслуживать. }
  MuxReset;
  MakeFrame(F, 4, 8, 1);
  Inject(F, False);
  St := MuxGetStats;
  TestEqInt('без обработчика команда всё равно посчитана', St.Commands, 1);
  TestEqInt('и неприкаянной не считается', St.Unrouted, 0);

  { ================================================================
    Обратное давление
    ================================================================ }

  MuxReset;
  Sent := 0;
  MakeFrame(F, 1, MaxPayload, 3);
  for I := 1 to 20 do
  begin
    R := MuxSend(F);
    if R.Ok then Inc(Sent);
  end;

  TestTrue('кольцо приняло несколько кадров', Sent > 0);
  TestTrue('кольцо переполнилось, а не выросло', Sent < 20);
  TestTrue('переполнение не превысило ёмкость', MuxOutPending <= OutRingBytes);

  St := MuxGetStats;
  TestTrue('отказы по обратному давлению посчитаны', St.Backpressure > 0);

  { Кадр кладётся целиком или не кладётся вовсе: половина кадра в линии —
    это мусор для приёмника и потерянная синхронизация. }
  Before := MuxOutPending;
  R := MuxSend(F);
  TestResultErr('при нехватке места отказ', R, ERR_DECIDER_UNAVAILABLE);
  TestEqInt('после отказа кольцо не изменилось', MuxOutPending, Before);

  for I := 1 to 600 do
    if not MuxOutByte(B) then Break;
  R := MuxSend(F);
  TestResultOk('после разгрузки отправка проходит', R);

  { ================================================================
    Пауза в линии
    ================================================================ }

  MuxReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;

  { Половина кадра ушла, дальше линия замолчала. }
  MakeFrame(F, 6, 200, 5);
  R := MuxSend(F);
  for I := 1 to 100 do
    if not MuxOutByte(B) then Break else MuxFeedByte(B);
  MuxIdle;
  St := MuxGetStats;
  TestEqInt('пауза посреди кадра посчитана', St.IdleResets, 1);
  TestEqStr('недособранный кадр обработчику не отдан', Trace, '');

  { И следующий кадр после паузы собирается. }
  MuxReset;
  MuxSetCommandHandler(OnCommand);
  ClearTrace;
  MakeFrame(F, 6, 32, 9);
  R := MuxSend(F);
  Moved := Loopback;
  TestEqInt('петля перенесла весь кадр',
            Moved, HeaderBytes + 32 + TrailerBytes);
  TestEqStr('после паузы следующий кадр доходит', Trace, '*');

  { Пауза при пустом декодере ничего не значит. }
  MuxReset;
  MuxIdle;
  St := MuxGetStats;
  TestEqInt('пауза между кадрами не считается обрывом', St.IdleResets, 0);

  Halt(TestEnd);
end.
