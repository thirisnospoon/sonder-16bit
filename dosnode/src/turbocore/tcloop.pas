{ ===================================================================
  TurboCore · цикл событий.

  Связывает политику из TcSched с переключением из TcFiber. Один тик:

    1. истечь сроки таймеров;
    2. выбрать следующего готового;
    3. переключиться на него и дождаться возврата;
    4. если работать некому, но кто-то ждёт — простой.

  ИСТОЧНИК ВРЕМЕНИ ПОДСТАВЛЯЕТСЯ. На DOS это тик BIOS с частотой 18.2 Гц,
  в тестах — виртуальные часы, которые двигает сам тест. Иначе проверка
  таймеров означала бы настоящее ожидание, а тест, который спит, — это
  тест, который однажды начнёт мигать.

  ПАДЕНИЕ ФАЙБЕРА. Panic помечает файбер как FAILED и уходит в
  планировщик навсегда. Стек упавшего остаётся как есть и
  переинициализируется при следующем использовании слота; вся память
  команды жила в её арене, и сбросить её — работа вызывающего. Ни
  отката, ни раскрутки стека: они не нужны, когда владение памятью
  устроено ареной.

  Гарантия, ради которой всё это: упавшая команда не задевает остальные
  пятнадцать. Проверяется тестом, который роняет файбер намеренно.
  =================================================================== }
unit TcLoop;

{$MODE TP}
{$F+}
{$S-}

interface

uses
  TcResult, TcSched, TcFiber;

type
  TTickSource = function: LongInt;

  TLoopStats = record
    Ticks:      LongInt;   { сколько раз прокрутился цикл }
    Switches:   LongInt;   { переключений в файберы }
    TimerWakes: LongInt;   { разбужено по сроку }
    Overruns:   LongInt;   { случаев превышения квоты }
    IdleTicks:  LongInt;   { тиков без работы, но с ожидающими }
  end;

const
  { Квота на один заход файбера, в тиках источника времени. Превышение
    не прерывает файбер — вытеснения здесь нет, — но фиксируется. Один
    залипший файбер должен быть виден в метриках, а не проявляться
    общей вялостью ноды. }
  DefaultQuantum = 5;

procedure LoopInit(Tick: TTickSource);

{ Завести файбер. Стек передаётся вызывающим: владение памятью остаётся
  у того, кто её выделил. }
function LoopSpawn(const Name: string; Entry: TFiberEntry;
                   Stack: Pointer; StackBytes: Word;
                   var Id: TFiberId): TResult;

{ Один оборот. False означает, что работы нет и ждать нечего. }
function LoopTick: Boolean;

{ Крутить, пока есть работа, но не дольше MaxTicks. Предел обязателен:
  цикл без предела в тесте превращается в зависание. }
function LoopRun(MaxTicks: LongInt): TLoopStats;

function LoopGetStats: TLoopStats;
procedure LoopResetStats;

{ --- вызывается ИЗ файбера --- }

{ Отдать управление, оставшись готовым. }
procedure Yield;

{ Заснуть на N тиков. Ноль означает «отдать управление», а не «спать
  один тик»: сравнение сроков нестрогое. }
procedure SleepTicks(N: LongInt);

{ Ждать пробуждения по ключу. Разбудит SchedWake из цикла или из
  обработчика порта. }
procedure AwaitKey(Reason: TWaitReason; Key: Word);

{ Упасть. Управление не возвращается никогда. }
procedure Panic(const Code: TErrCode);

implementation

var
  Entries: array[1..MaxFibers] of TFiberEntry;
  TickFn:  TTickSource;
  Stats:   TLoopStats;
  Quantum: LongInt;

function Now: LongInt;
begin
  if @TickFn = nil then
    Now := 0
  else
    Now := TickFn;
end;

{ ------------------------------------------------------------------
  Единая точка входа всех файберов.

  Подготовленный кадр возвращает управление сюда, а не прямо в тело:
  иначе каждое тело обязано было бы само помечать себя завершённым и
  уходить в планировщик, и забытый вызов означал бы возврат в никуда.
  ------------------------------------------------------------------ }
procedure Trampoline; far;
var
  Me: TFiberId;
begin
  Me := FiberCurrent;
  if (Me >= 1) and (Me <= MaxFibers) and (@Entries[Me] <> nil) then
    Entries[Me];

  { Тело отработало штатно. }
  SchedFinish(Me);

  { Обратно в планировщик и больше никогда сюда. Цикл, увидев состояние
    DONE, этот файбер уже не выберет. }
  FiberSwitch(Me, SchedulerId);
end;

procedure LoopInit(Tick: TTickSource);
var
  I: Integer;
begin
  TickFn := Tick;
  Quantum := DefaultQuantum;
  SchedReset;
  FillChar(Stats, SizeOf(Stats), 0);
  for I := 1 to MaxFibers do
    Entries[I] := nil;
  FiberSetCurrent(SchedulerId);
end;

function LoopSpawn(const Name: string; Entry: TFiberEntry;
                   Stack: Pointer; StackBytes: Word;
                   var Id: TFiberId): TResult;
var
  R: TResult;
begin
  Id := SchedulerId;

  if @Entry = nil then
  begin
    LoopSpawn := Err('DECIDER_PANIC');
    Exit;
  end;

  R := SchedSpawn(Name, Id);
  if not R.Ok then
  begin
    LoopSpawn := R;
    Exit;
  end;

  R := FiberPrepare(Id, Stack, StackBytes, Trampoline);
  if not R.Ok then
  begin
    { Слот занят, но контекст не готов — освобождаем, иначе он останется
      готовым к исполнению с неподготовленным стеком. }
    SchedFree(Id);
    Id := SchedulerId;
    LoopSpawn := R;
    Exit;
  end;

  Entries[Id] := Entry;
  LoopSpawn := Ok;
end;

function LoopTick: Boolean;
var
  T: LongInt;
  Woken: Integer;
  Next, Slow: TFiberId;
begin
  Inc(Stats.Ticks);
  T := Now;

  Woken := SchedExpireTimers(T);
  Inc(Stats.TimerWakes, Woken);

  Next := SchedPick;

  if Next = SchedulerId then
  begin
    { Работать некому. Если кто-то ждёт — это простой, и цикл имеет
      смысл продолжать. Если не ждёт никто — работа кончилась. }
    if SchedAnyWaiting then
    begin
      Inc(Stats.IdleTicks);
      LoopTick := True;
    end
    else
      LoopTick := False;
    Exit;
  end;

  SchedSetRunning(Next, T);
  FiberSetCurrent(Next);
  Inc(Stats.Switches);

  FiberSwitch(SchedulerId, Next);

  { Вернулись. Текущим снова становится планировщик. }
  FiberSetCurrent(SchedulerId);

  { Файбер, исполнявшийся дольше квоты, фиксируется. Прервать его мы не
    можем — вытеснения нет, — но и молчать нельзя. }
  Slow := SchedOverrun(Now, Quantum);
  if Slow <> SchedulerId then
    Inc(Stats.Overruns);

  { Файбер вернул управление, не сменив состояния: значит он просто
    отдал такт и остаётся готовым. }
  if SchedInfo(Next).State = fsRunning then
    SchedSetReady(Next);

  LoopTick := True;
end;

function LoopRun(MaxTicks: LongInt): TLoopStats;
var
  N: LongInt;
begin
  N := 0;
  while (N < MaxTicks) and LoopTick do
    Inc(N);
  LoopRun := Stats;
end;

function LoopGetStats: TLoopStats;
begin
  LoopGetStats := Stats;
end;

procedure LoopResetStats;
begin
  FillChar(Stats, SizeOf(Stats), 0);
end;

{ ------------------------------------------------------------------
  Точки переключения. Каждая из них — место, где файбер может быть
  прерван, и по правилу из docs/ENGINEERING.md §5 это объявляется в
  шапке любой функции, которая их вызывает.
  ------------------------------------------------------------------ }

procedure Yield;
var
  Me: TFiberId;
begin
  Me := FiberCurrent;
  if Me = SchedulerId then Exit;
  SchedSetReady(Me);
  FiberSwitch(Me, SchedulerId);
end;

procedure SleepTicks(N: LongInt);
var
  Me: TFiberId;
begin
  Me := FiberCurrent;
  if Me = SchedulerId then Exit;
  if N < 0 then N := 0;
  SchedWaitTimer(Me, Now + N);
  FiberSwitch(Me, SchedulerId);
end;

procedure AwaitKey(Reason: TWaitReason; Key: Word);
var
  Me: TFiberId;
begin
  Me := FiberCurrent;
  if Me = SchedulerId then Exit;
  SchedWaitKey(Me, Reason, Key);
  FiberSwitch(Me, SchedulerId);
end;

procedure Panic(const Code: TErrCode);
var
  Me: TFiberId;
begin
  Me := FiberCurrent;
  if Me = SchedulerId then Exit;
  SchedFail(Me, Code);

  { Уходим в планировщик и не возвращаемся: состояние FAILED больше
    никогда не будет выбрано. Стек остаётся как есть — его
    переинициализирует следующий LoopSpawn на этом слоте. }
  FiberSwitch(Me, SchedulerId);
end;

begin
  TickFn := nil;
  Quantum := DefaultQuantum;
  FillChar(Stats, SizeOf(Stats), 0);
end.
