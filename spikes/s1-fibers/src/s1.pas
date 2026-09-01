{ ===================================================================
  Спайк S1 — переключение контекста файберов на i8086-msdos.

  Проверяемые утверждения:
    A. Модель памяти даёт SS = DS, то есть стеки файберов можно держать
       статическим массивом в сегменте данных.
    B. Переключение только SP при неизменном SS работает.
    C. 100 000 переключений не портят ни один стек.
    D. Локальные данные переживают переключение.
    E. Реальная глубина стека измерима.

  Все процедуры объявлены far директивой F+, а возврат из ContextSwitch —
  дальний, retf. Так одна и та же конструкция работает во всех шести моделях
  памяти: в моделях с ближним кодом дальний вызов тоже корректен, а в моделях
  с дальним — единственно возможен. Спайку нужно сравнить все модели, а не
  работать в одной.

  Результат пишется в S1.TAP: вердикт выносится снаружи эмулятора, а не по
  коду возврата DOSBox (RISKS.md, R3).

  Одноразовый код. В TurboCore переедут только числа и выводы.
  =================================================================== }
program S1Fibers;

{$MODE TP}
{$ASMMODE INTEL}
{$F+}   { дальние вызовы: см. шапку }
{$S-}   { проверка стека выключена: она опирается на глобальный StackLimit,
          который ничего не знает про чужие стеки файберов }
{$R-}
{$Q-}

const
  MAX_FIBERS      = 4;
  STACK_WORDS     = 512;      { 1024 байта на файбер }
  TARGET_SWITCHES = 100000;

  CANARY_LO    = $DEAD;       { дно стека: сюда упираемся при переполнении }
  CANARY_HI    = $C0DE;       { вершина стека }
  FILL_PATTERN = $A5A5;       { заливка для замера реальной глубины }

  { Смещения кадра от вершины стека, в словах. Порядок задан тем, что
    кладёт на стек дальний вызов и последующие push в ContextSwitch:
    от младших адресов к старшим — ES, DS, DI, SI, BP, IP, CS. }
  OFS_CS = 2;
  OFS_IP = 3;
  OFS_BP = 4;
  OFS_SI = 5;
  OFS_DI = 6;
  OFS_DS = 7;
  OFS_ES = 8;                 { сюда встанет SP }

type
  TFiberState = (fsUnused, fsReady, fsDone);
  TFiberProc  = procedure;
  TFarAddr    = record
    Ofs, Seg: Word;
  end;

var
  { Стеки всех файберов — один статический массив в DS.
    SS не меняется никогда, переключается только SP. }
  Stacks: array[1..MAX_FIBERS, 0..STACK_WORDS-1] of Word;

  { Слот 0 — контекст планировщика (главный стек программы). }
  SPSlots: array[0..MAX_FIBERS] of Word;

  State:    array[1..MAX_FIBERS] of TFiberState;
  Body:     array[1..MAX_FIBERS] of TFiberProc;
  Turns:    array[1..MAX_FIBERS] of LongInt;
  Counters: array[1..MAX_FIBERS] of LongInt;

  gFrom, gTo: Word;      { индексы в SPSlots для ContextSwitch }
  Current:    Word;      { исполняемый файбер, 0 = планировщик }
  Switches:   LongInt;

  Failures:  Integer;
  TestNo:    Integer;
  Report:    Text;
  Corrupted: Boolean;
  gTmp:      string;     { буфер для Str() в отчёте }

{ ------------------------------------------------------------------
  Переключение контекста.

  Обе стороны находятся в одной точке одной процедуры, поэтому кадры
  симметричны: что положено на стек уходящего файбера, то отсюда же
  снимется со стека приходящего.

  nostackframe обязателен: со штатным эпилогом компилятор восстановил бы
  SP из BP и отменил переключение.

  cli/sti здесь огрублены намеренно: спайк не обязан сохранять исходное
  состояние флага прерываний. В TurboCore на этом месте будет pushf/popf.
  ------------------------------------------------------------------ }
procedure ContextSwitch; assembler; nostackframe;
asm
  push bp
  push si
  push di
  push ds
  push es

  mov  bx, gFrom
  shl  bx, 1
  mov  [SPSlots + bx], sp

  mov  bx, gTo
  shl  bx, 1
  cli
  mov  sp, [SPSlots + bx]
  sti

  pop  es
  pop  ds
  pop  di
  pop  si
  pop  bp
  retf
end;

{ Отдать управление планировщику. Вызывается изнутри файбера. }
procedure Yield;
begin
  Inc(Turns[Current]);
  gFrom := Current;
  gTo   := 0;
  ContextSwitch;
end;

{ Точка, в которую «возвращается» только что созданный файбер. }
procedure FiberBootstrap;
begin
  Body[Current];
  State[Current] := fsDone;
  { Файбер закончился: уходим в планировщик и больше не возвращаемся. }
  gFrom := Current;
  gTo   := 0;
  ContextSwitch;
end;

{ ------------------------------------------------------------------
  Подготовка стека нового файбера: кадр строится так, чтобы ContextSwitch,
  сняв пять регистров и выполнив retf, оказался в FiberBootstrap.
  ------------------------------------------------------------------ }
procedure SpawnFiber(Idx: Word; P: TFiberProc);
var
  I: Integer;
  BootVar:  TFiberProc;
  BootAddr: TFarAddr absolute BootVar;
begin
  BootVar := FiberBootstrap;

  for I := 0 to STACK_WORDS - 1 do
    Stacks[Idx][I] := FILL_PATTERN;

  Stacks[Idx][0] := CANARY_LO;
  Stacks[Idx][STACK_WORDS-1] := CANARY_HI;

  Stacks[Idx][STACK_WORDS-OFS_CS] := BootAddr.Seg;
  Stacks[Idx][STACK_WORDS-OFS_IP] := BootAddr.Ofs;
  Stacks[Idx][STACK_WORDS-OFS_BP] := 0;
  Stacks[Idx][STACK_WORDS-OFS_SI] := 0;
  Stacks[Idx][STACK_WORDS-OFS_DI] := 0;
  Stacks[Idx][STACK_WORDS-OFS_DS] := Dseg;
  Stacks[Idx][STACK_WORDS-OFS_ES] := Dseg;

  SPSlots[Idx] := Ofs(Stacks[Idx][STACK_WORDS-OFS_ES]);
  State[Idx]   := fsReady;
  Body[Idx]    := P;
  Turns[Idx]   := 0;
end;

function CanariesIntact: Boolean;
var
  I: Integer;
begin
  CanariesIntact := True;
  for I := 1 to MAX_FIBERS do
    if (Stacks[I][0] <> CANARY_LO) or (Stacks[I][STACK_WORDS-1] <> CANARY_HI) then
    begin
      CanariesIntact := False;
      Exit;
    end;
end;

{ Максимальная использованная глубина стека файбера, в байтах. }
function StackDepth(Idx: Word): Integer;
var
  I: Integer;
begin
  I := 1;
  while (I < STACK_WORDS - 1) and (Stacks[Idx][I] = FILL_PATTERN) do
    Inc(I);
  StackDepth := (STACK_WORDS - I) * 2;
end;

{ ------------------------------------------------------------------
  Тела файберов.
  ------------------------------------------------------------------ }
procedure WorkerShallow;
begin
  while Switches < TARGET_SWITCHES do
  begin
    Inc(Counters[Current]);
    Yield;
  end;
end;

{ Файбер с заметной вложенностью: проверяем, что кадры переживают
  переключение и локальные данные не портятся. }
procedure Recurse(Depth: Integer);
var
  Padding: array[0..7] of Word;
  I: Integer;
begin
  for I := 0 to 7 do
    Padding[I] := Depth + I;

  if Depth > 0 then
    Recurse(Depth - 1)
  else
  begin
    Inc(Counters[Current]);
    Yield;
  end;

  for I := 0 to 7 do
    if Padding[I] <> Depth + I then
      Corrupted := True;
end;

procedure WorkerDeep;
begin
  while Switches < TARGET_SWITCHES do
    Recurse(6);
end;

{ ------------------------------------------------------------------
  Отчёт в формате TAP.
  ------------------------------------------------------------------ }
procedure Emit(const S: string);
begin
  WriteLn(Report, S);
  WriteLn(S);
end;

procedure Ok(const Name: string);
begin
  Inc(TestNo);
  Str(TestNo, gTmp);
  Emit('ok ' + gTmp + ' - ' + Name);
end;

procedure NotOk(const Name: string);
begin
  Inc(TestNo);
  Str(TestNo, gTmp);
  Emit('not ok ' + gTmp + ' - ' + Name);
  Inc(Failures);
end;

procedure Diag(const S: string);
begin
  Emit('# ' + S);
end;

procedure DiagNum(const S: string; N: LongInt);
var
  T: string;
begin
  Str(N, T);
  Emit('# ' + S + ' ' + T);
end;

{ ------------------------------------------------------------------ }
var
  I: Integer;
  Next: Word;
  AnyReady: Boolean;
  AllRan: Boolean;
  TotalTurns: LongInt;

begin
  Assign(Report, 'S1.TAP');
  Rewrite(Report);

  Failures  := 0;
  TestNo    := 0;
  Switches  := 0;
  Current   := 0;
  Corrupted := False;

  Emit('1..7');
  Diag('spike S1 - fiber context switch on i8086-msdos');
  DiagNum('MAX_FIBERS', MAX_FIBERS);
  DiagNum('STACK_WORDS', STACK_WORDS);
  DiagNum('target switches', TARGET_SWITCHES);
  DiagNum('SizeOf(Pointer)', SizeOf(Pointer));
  DiagNum('SizeOf(TFiberProc)', SizeOf(TFiberProc));
  DiagNum('DSEG', Dseg);
  DiagNum('SSEG', Sseg);
  DiagNum('MemAvail', MemAvail);

  { A. Стеки в сегменте данных возможны только при SS = DS.
       Если это не так — дальше идти нельзя: Ofs() от элемента массива
       не будет валидным смещением относительно SS, и мы просто рухнем,
       ничего не сообщив. }
  if Sseg = Dseg then
    Ok('SS = DS: стеки файберов живут в сегменте данных')
  else
  begin
    NotOk('SS <> DS: модель памяти не даёт держать стеки в DS');
    Diag('дальнейшие проверки пропущены: конструкция неприменима');
    for I := 2 to 7 do
    begin
      Inc(TestNo);
      Str(TestNo, gTmp);
      Emit('not ok ' + gTmp + ' - пропущено (SS <> DS)');
      Inc(Failures);
    end;
    Close(Report);
    Halt(Failures);
  end;

  { Дальний указатель на код — обязательное условие для retf. }
  if SizeOf(TFiberProc) = 4 then
    Ok('указатель на код дальний, retf в ContextSwitch корректен')
  else
    NotOk('указатель на код ближний: retf снимет лишнее слово');

  for I := 1 to MAX_FIBERS do
  begin
    State[I] := fsUnused;
    Counters[I] := 0;
  end;

  SpawnFiber(1, WorkerShallow);
  SpawnFiber(2, WorkerShallow);
  SpawnFiber(3, WorkerDeep);
  SpawnFiber(4, WorkerShallow);

  if CanariesIntact then
    Ok('канарейки целы после инициализации')
  else
    NotOk('канарейки повреждены сразу после инициализации');

  { --- главный цикл планировщика: round-robin --- }
  repeat
    AnyReady := False;
    for Next := 1 to MAX_FIBERS do
      if State[Next] = fsReady then
      begin
        AnyReady := True;
        Current := Next;
        gFrom := 0;
        gTo   := Next;
        ContextSwitch;           { уходим в файбер }
        Current := 0;
        Inc(Switches);
      end;
  until (not AnyReady) or (Switches >= TARGET_SWITCHES);

  DiagNum('выполнено переключений планировщика', Switches);

  TotalTurns := 0;
  for I := 1 to MAX_FIBERS do
    TotalTurns := TotalTurns + Turns[I];
  DiagNum('всего yield из файберов', TotalTurns);

  if CanariesIntact then
    Ok('канарейки целы после всех переключений')
  else
    NotOk('канарейка повреждена во время работы');

  if not Corrupted then
    Ok('локальные данные переживают переключение контекста')
  else
    NotOk('локальные данные испорчены переключением');

  if Switches >= TARGET_SWITCHES then
    Ok('достигнута цель по числу переключений')
  else
    NotOk('цикл завершился раньше цели');

  for I := 1 to MAX_FIBERS do
    DiagNum('глубина стека файбера, байт:', StackDepth(I));
  for I := 1 to MAX_FIBERS do
    DiagNum('счётчик файбера:', Counters[I]);

  AllRan := True;
  for I := 1 to MAX_FIBERS do
    if Counters[I] = 0 then AllRan := False;
  if AllRan then
    Ok('все файберы получали управление')
  else
    NotOk('какой-то файбер ни разу не исполнился');

  if Failures = 0 then
    Diag('ИТОГ: спайк S1 пройден')
  else
    DiagNum('ИТОГ: провалов', Failures);

  Close(Report);
  Halt(Failures);
end.
