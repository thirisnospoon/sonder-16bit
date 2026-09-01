{ ===================================================================
  Тесты цикла событий и настоящих файберов.

  На 16-битном таргете здесь исполняются НАСТОЯЩИЕ файберы: спайк S1b
  проверил переключение в изоляции, а тут оно работает через планировщик,
  таймеры и точки ожидания.

  Главная проверка — падение. Один файбер намеренно роняется, и остальные
  обязаны доработать. Это и есть обещание всей конструкции: упавшая
  команда не задевает пятнадцать соседних.

  На нативном таргете настоящего переключения нет, и подделывать его
  нечестно. Там проверяется то, что имеет смысл: заведение файберов,
  отказ на неподготовленном стеке, работа виртуальных часов. Проверки
  различаются по таргетам осознанно, а не потому что «на DOS не собралось».

  Часы виртуальные: тест двигает время сам. Тест, который спит, — это
  тест, который однажды начнёт мигать.
  =================================================================== }
program TstLoop;

{$MODE TP}
{$F+}
{$R-}

uses
  TcResult, TcTest, TcSched, TcFiber, TcLoop;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile      = 'TSTLOOP.TAP';
  PlannedTests = 30;
  StackBytes   = 2048;
{$ELSE}
  TapFile      = 'tstloop.tap';
  PlannedTests = 9;
  StackBytes   = 4096;
{$ENDIF}

var
  { Виртуальные часы. }
  VirtualNow: LongInt;

function VClock: LongInt; far;
begin
  VClock := VirtualNow;
end;

var
  { Стеки файберов. На 16 битах берутся из дальней кучи, чтобы каждый лёг
    в собственный сегмент (ADR-0010). }
  Stacks: array[1..4] of Pointer;

  { Наблюдаемые следы работы файберов. }
  CountA, CountB, CountC: Integer;
  DoneA, DoneB: Boolean;
  Order: string;

procedure NoteRun(C: Char);
begin
  if Length(Order) < 200 then
    Order := Order + C;
end;

{$IFDEF CPU16}

{ Простой файбер: три захода с отдачей такта между ними. }
procedure WorkerA; far;
var
  I: Integer;
begin
  for I := 1 to 3 do
  begin
    Inc(CountA);
    NoteRun('a');
    Yield;
  end;
  DoneA := True;
end;

{ Файбер, который спит: проверяет, что таймер будит именно его и вовремя. }
procedure WorkerB; far;
begin
  Inc(CountB);
  NoteRun('b');
  SleepTicks(10);
  Inc(CountB);
  NoteRun('B');
  DoneB := True;
end;

{ Файбер, который падает. Соседи обязаны этого не заметить. }
procedure WorkerCrash; far;
begin
  Inc(CountC);
  NoteRun('c');
  Yield;
  Inc(CountC);
  Panic(ERR_DECIDER_PANIC);
  { Сюда управление не возвращается никогда. Если вернётся — счётчик
    вырастет до трёх, и тест это заметит. }
  Inc(CountC);
  NoteRun('!');
end;

{ Файбер с заметной вложенностью: кадры обязаны пережить переключение. }
procedure Recurse(Depth: Integer);
var
  Pad: array[0..7] of Word;
  I: Integer;
begin
  for I := 0 to 7 do
    Pad[I] := Depth + I;
  if Depth > 0 then
    Recurse(Depth - 1)
  else
    Yield;
  for I := 0 to 7 do
    if Pad[I] <> Depth + I then
      Panic(ERR_DECIDER_PANIC);
end;

procedure WorkerDeep; far;
begin
  Recurse(6);
  NoteRun('d');
end;

{$ENDIF}

{ Точка входа, которая не делает ничего: нужна для проверок заведения. }
procedure WorkerNoop; far;
begin
end;

var
  R: TResult;
  Id, A, B, C, D: TFiberId;
  St: TLoopStats;
  I: Integer;
  Alive: Integer;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('цикл событий и файберы');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos, файберы настоящие');
{$ELSE}
  TestDiag('таргет: нативный, переключения нет');
{$ENDIF}

  VirtualNow := 0;
  Order := '';
  CountA := 0; CountB := 0; CountC := 0;
  DoneA := False; DoneB := False;

  for I := 1 to 4 do
    GetMem(Stacks[I], StackBytes);

  { ================================================================
    Заведение файберов — проверяется одинаково на обоих таргетах
    ================================================================ }

  LoopInit(VClock);

  R := LoopSpawn('noop', WorkerNoop, Stacks[1], StackBytes, Id);
  TestResultOk('файбер заводится', R);
  TestTrue('номер выдан', (Id >= 1) and (Id <= MaxFibers));
  TestEqInt('заведённый файбер готов', Ord(SchedInfo(Id).State), Ord(fsReady));

  { Слишком маленький стек — отказ, а не порча памяти при первом же
    переключении. }
  R := LoopSpawn('tiny', WorkerNoop, Stacks[2], 64, Id);
  TestResultErr('крошечный стек отвергается', R, ERR_INSUFFICIENT_CONTEXT);
  TestEqInt('после отказа слот не занят', Id, SchedulerId);

  { Пустая точка входа — дефект вызывающего. }
  R := LoopSpawn('nil', nil, Stacks[2], StackBytes, Id);
  TestResultErr('пустая точка входа отвергается', R, ERR_DECIDER_PANIC);

  { Виртуальные часы двигает тест, а не время. }
  VirtualNow := 77;
  LoopInit(VClock);
  R := LoopSpawn('sleeper', WorkerNoop, Stacks[1], StackBytes, A);
  SchedWaitTimer(A, 100);
  TestEqInt('до срока не будит', SchedExpireTimers(VirtualNow), 0);
  VirtualNow := 100;
  TestEqInt('на сроке будит', SchedExpireTimers(VirtualNow), 1);

  { Цикл без работы завершается, а не крутится вхолостую. }
  LoopInit(VClock);
  St := LoopRun(50);
  TestEqInt('без файберов цикл останавливается сразу', St.Ticks, 1);

{$IFDEF CPU16}

  { ================================================================
    Настоящие файберы
    ================================================================ }

  VirtualNow := 0;
  Order := '';
  CountA := 0; CountB := 0; CountC := 0;
  DoneA := False; DoneB := False;

  LoopInit(VClock);
  R := LoopSpawn('a', WorkerA, Stacks[1], StackBytes, A);
  TestResultOk('рабочий файбер заведён', R);
  R := LoopSpawn('deep', WorkerDeep, Stacks[2], StackBytes, D);
  TestResultOk('файбер с вложенностью заведён', R);

  St := LoopRun(200);

  TestEqInt('простой файбер отработал три захода', CountA, 3);
  TestTrue('простой файбер завершился', DoneA);
  TestEqInt('оба файбера в состоянии DONE', SchedCount(fsDone), 2);
  TestTrue('вложенность пережила переключения', Pos('d', Order) > 0);
  TestTrue('канарейки целы после прогона',
           FiberCanaryIntact(A) and FiberCanaryIntact(D));
  TestDiagInt('глубина стека простого файбера, байт', FiberStackDepth(A));
  TestDiagInt('глубина стека вложенного файбера, байт', FiberStackDepth(D));

  { Первая версия этой проверки утверждала, что вложенный файбер съест
    больше стека. Измерение дало 330 против 332: глубину определяет не
    рекурсия на шесть уровней, а машинерия переключения — трамплин,
    кадр ContextSwitch и возвраты. Утверждение проверяло моё ожидание,
    а не свойство системы, и заменено на то, что действительно важно. }
  TestTrue('оба стека уложились в бюджет',
           (FiberStackDepth(A) < StackBytes) and
           (FiberStackDepth(D) < StackBytes));
  TestTrue('глубина измерима и ненулевая',
           (FiberStackDepth(A) > 0) and (FiberStackDepth(D) > 0));

  { ================================================================
    Сон и пробуждение по таймеру
    ================================================================ }

  VirtualNow := 0;
  Order := '';
  CountB := 0;
  DoneB := False;

  LoopInit(VClock);
  R := LoopSpawn('b', WorkerB, Stacks[1], StackBytes, B);

  { Первый оборот: файбер поработал и заснул. }
  LoopTick;
  TestEqInt('до сна файбер отработал один раз', CountB, 1);
  TestEqInt('спящий ждёт', Ord(SchedInfo(B).State), Ord(fsWaiting));

  { Время не двигали — спящий не проснулся. }
  LoopTick;
  TestEqInt('без хода времени сон не кончается', CountB, 1);

  VirtualNow := 10;
  St := LoopRun(20);
  TestEqInt('после срока файбер продолжил', CountB, 2);
  TestTrue('проснувшийся довёл работу до конца', DoneB);

  { ================================================================
    Падение файбера — главное обещание конструкции
    ================================================================ }

  VirtualNow := 0;
  Order := '';
  CountA := 0; CountC := 0;
  DoneA := False;

  LoopInit(VClock);
  R := LoopSpawn('crash', WorkerCrash, Stacks[1], StackBytes, C);
  R := LoopSpawn('a', WorkerA, Stacks[2], StackBytes, A);

  St := LoopRun(200);

  TestEqInt('упавший помечен', Ord(SchedInfo(C).State), Ord(fsFailed));
  TestEqStr('причина падения сохранена', SchedInfo(C).PanicCode,
            'DECIDER_PANIC');
  TestEqInt('после Panic управление не вернулось в файбер', CountC, 2);
  TestEqInt('сосед доработал полностью', CountA, 3);
  TestTrue('сосед завершился штатно', DoneA);
  TestEqInt('сосед в состоянии DONE', Ord(SchedInfo(A).State), Ord(fsDone));
  TestTrue('канарейка соседа цела после чужого падения',
           FiberCanaryIntact(A));

  TestDiagInt('переключений за прогон', St.Switches);
  TestDiagInt('оборотов цикла', St.Ticks);

{$ENDIF}

  for I := 1 to 4 do
    FreeMem(Stacks[I], StackBytes);

  Halt(TestEnd);
end.
