{ ===================================================================
  Спайк S1b — файберы в модели large: стеки в дальней куче, при
  переключении меняются и SS, и SP.

  Зачем: S1 показал, что модели с SS = DS дают около 52 КБ на все данные,
  а модели с дальними данными — около 596 КБ. Разница в одиннадцать раз.
  Цена дальней модели — одна дополнительная пара инструкций в ContextSwitch.
  Цена ближней — EMS, размазанный по каждому выделению памяти.

  Проверяемые утверждения:
    A. Стек файбера можно разместить в дальней куче.
    B. Переключение SS вместе с SP работает.
    C. 100 000 переключений не портят ни один стек.
    D. Локальные данные переживают переключение.
    E. Доступная память действительно на порядок больше, чем в medium.

  Отчёт сбрасывается на диск после каждой строки: если конструкция окажется
  неверной, программа рухнет, и важно сохранить всё, что успело выясниться.

  Одноразовый код. В TurboCore переедут только числа и выводы.
  =================================================================== }
program S1bFibers;

{$MODE TP}
{$ASMMODE INTEL}
{$F+}   { дальние вызовы }
{$S-}   { проверка стека опирается на глобальный StackLimit и здесь мешает }
{$R-}
{$Q-}

const
  MAX_FIBERS      = 4;
  STACK_WORDS     = 512;      { 1024 байта на файбер }
  TARGET_SWITCHES = 100000;

  CANARY_LO    = $DEAD;
  CANARY_HI    = $C0DE;
  FILL_PATTERN = $A5A5;

  { Кадр: от младших адресов к старшим — ES, DS, DI, SI, BP, IP, CS. }
  OFS_CS = 2;
  OFS_IP = 3;
  OFS_BP = 4;
  OFS_SI = 5;
  OFS_DI = 6;
  OFS_DS = 7;
  OFS_ES = 8;

type
  TFiberState = (fsUnused, fsReady, fsDone);
  TFiberProc  = procedure;
  TFarAddr    = record
    Ofs, Seg: Word;
  end;
  PStack = ^TStack;
  TStack = array[0..STACK_WORDS-1] of Word;

var
  { Стеки живут в дальней куче, у каждого файбера свой сегмент. }
  Stk:     array[1..MAX_FIBERS] of PStack;
  RawPtr:  array[1..MAX_FIBERS] of Pointer;   { что вернул GetMem, для FreeMem }

  { Слот 0 — контекст планировщика. Теперь сохраняем пару SS:SP. }
  SSSlots: array[0..MAX_FIBERS] of Word;
  SPSlots: array[0..MAX_FIBERS] of Word;

  State:    array[1..MAX_FIBERS] of TFiberState;
  Body:     array[1..MAX_FIBERS] of TFiberProc;
  Turns:    array[1..MAX_FIBERS] of LongInt;
  Counters: array[1..MAX_FIBERS] of LongInt;

  gFrom, gTo: Word;
  Current:    Word;
  Switches:   LongInt;

  Failures:  Integer;
  TestNo:    Integer;
  Report:    Text;
  Corrupted: Boolean;
  gTmp:      string;
  MemAtStart: LongInt;

{ ------------------------------------------------------------------
  Переключение контекста со сменой сегмента стека.

  Отличие от S1 ровно в двух инструкциях: сохраняется и восстанавливается
  SS. Пара mov ss / mov sp обязана быть неразрывной, поэтому идёт под cli.
  На 8086 запись в SS и так подавляет прерывание на одну инструкцию, но
  полагаться на это незачем.
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
  mov  ax, ss
  mov  [SSSlots + bx], ax
  mov  [SPSlots + bx], sp

  mov  bx, gTo
  shl  bx, 1
  cli
  mov  ax, [SSSlots + bx]
  mov  ss, ax
  mov  sp, [SPSlots + bx]
  sti

  pop  es
  pop  ds
  pop  di
  pop  si
  pop  bp
  retf
end;

procedure Yield;
begin
  Inc(Turns[Current]);
  gFrom := Current;
  gTo   := 0;
  ContextSwitch;
end;

procedure FiberBootstrap;
begin
  Body[Current];
  State[Current] := fsDone;
  gFrom := Current;
  gTo   := 0;
  ContextSwitch;
end;

{ ------------------------------------------------------------------
  Выделение стека в дальней куче.

  Указатель нормализуется: смещение сводится к младшим четырём битам, всё
  остальное уходит в сегмент. Без этого стек мог бы пересечь границу
  сегмента, и SP завернулся бы через ноль посреди работы.
  ------------------------------------------------------------------ }
procedure AllocStack(Idx: Word);
var
  P: Pointer;
  A: TFarAddr absolute P;
  NSeg, NOfs: Word;
begin
  { Запас в 16 байт, чтобы нормализация не вышла за выделенный блок. }
  GetMem(P, STACK_WORDS * 2 + 16);
  RawPtr[Idx] := P;

  NSeg := A.Seg + (A.Ofs shr 4);
  NOfs := A.Ofs and $000F;

  A.Seg := NSeg;
  A.Ofs := NOfs;
  Stk[Idx] := PStack(P);
end;

procedure SpawnFiber(Idx: Word; Pr: TFiberProc);
var
  I: Integer;
  BootVar:  TFiberProc;
  BootAddr: TFarAddr absolute BootVar;
  Top: TFarAddr;
  TopPtr: Pointer absolute Top;
begin
  BootVar := FiberBootstrap;

  for I := 0 to STACK_WORDS - 1 do
    Stk[Idx]^[I] := FILL_PATTERN;

  Stk[Idx]^[0] := CANARY_LO;
  Stk[Idx]^[STACK_WORDS-1] := CANARY_HI;

  Stk[Idx]^[STACK_WORDS-OFS_CS] := BootAddr.Seg;
  Stk[Idx]^[STACK_WORDS-OFS_IP] := BootAddr.Ofs;
  Stk[Idx]^[STACK_WORDS-OFS_BP] := 0;
  Stk[Idx]^[STACK_WORDS-OFS_SI] := 0;
  Stk[Idx]^[STACK_WORDS-OFS_DI] := 0;
  Stk[Idx]^[STACK_WORDS-OFS_DS] := Dseg;
  Stk[Idx]^[STACK_WORDS-OFS_ES] := Dseg;

  TopPtr := @Stk[Idx]^[STACK_WORDS-OFS_ES];
  SSSlots[Idx] := Top.Seg;
  SPSlots[Idx] := Top.Ofs;

  State[Idx] := fsReady;
  Body[Idx]  := Pr;
  Turns[Idx] := 0;
end;

function CanariesIntact: Boolean;
var
  I: Integer;
begin
  CanariesIntact := True;
  for I := 1 to MAX_FIBERS do
    if (Stk[I]^[0] <> CANARY_LO) or (Stk[I]^[STACK_WORDS-1] <> CANARY_HI) then
    begin
      CanariesIntact := False;
      Exit;
    end;
end;

function StackDepth(Idx: Word): Integer;
var
  I: Integer;
begin
  I := 1;
  while (I < STACK_WORDS - 1) and (Stk[Idx]^[I] = FILL_PATTERN) do
    Inc(I);
  StackDepth := (STACK_WORDS - I) * 2;
end;

procedure WorkerShallow;
begin
  while Switches < TARGET_SWITCHES do
  begin
    Inc(Counters[Current]);
    Yield;
  end;
end;

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
  Отчёт TAP. Flush после каждой строки: если конструкция неверна и
  программа рухнет, всё выясненное до падения должно остаться на диске.
  ------------------------------------------------------------------ }
procedure Emit(const S: string);
begin
  WriteLn(Report, S);
  Flush(Report);
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

var
  I: Integer;
  Next: Word;
  AnyReady: Boolean;
  AllRan: Boolean;
  TotalTurns: LongInt;

begin
  Assign(Report, 'S1B.TAP');
  Rewrite(Report);

  Failures  := 0;
  TestNo    := 0;
  Switches  := 0;
  Current   := 0;
  Corrupted := False;
  MemAtStart := MemAvail;

  Emit('1..7');
  Diag('spike S1b - fibers in far heap, SS switched with SP');
  DiagNum('MAX_FIBERS', MAX_FIBERS);
  DiagNum('STACK_WORDS', STACK_WORDS);
  DiagNum('target switches', TARGET_SWITCHES);
  DiagNum('SizeOf(Pointer)', SizeOf(Pointer));
  DiagNum('SizeOf(TFiberProc)', SizeOf(TFiberProc));
  DiagNum('DSEG', Dseg);
  DiagNum('SSEG', Sseg);
  DiagNum('MemAvail at start', MemAtStart);

  { Смысл упражнения: памяти должно быть на порядок больше, чем 52 КБ,
    которые дают модели с SS = DS. }
  if MemAtStart > 200000 then
    Ok('дальняя куча даёт больше 200 КБ')
  else
    NotOk('дальней кучи нет: модель собрана не так, как предполагалось');

  for I := 1 to MAX_FIBERS do
  begin
    State[I] := fsUnused;
    Counters[I] := 0;
    AllocStack(I);
  end;

  DiagNum('MemAvail после выделения стеков', MemAvail);
  for I := 1 to MAX_FIBERS do
  begin
    Str(Seg(Stk[I]^[0]), gTmp);
    Diag('сегмент стека файбера: ' + gTmp);
  end;

  { Стеки в разных сегментах — значит SS действительно придётся менять. }
  if Seg(Stk[1]^[0]) <> Sseg then
    Ok('стек файбера лежит вне сегмента стека программы')
  else
    NotOk('стек файбера совпал с сегментом программы: проверка бессмысленна');

  SpawnFiber(1, WorkerShallow);
  SpawnFiber(2, WorkerShallow);
  SpawnFiber(3, WorkerDeep);
  SpawnFiber(4, WorkerShallow);

  if CanariesIntact then
    Ok('канарейки целы после инициализации')
  else
    NotOk('канарейки повреждены сразу после инициализации');

  Diag('входим в цикл переключений');

  repeat
    AnyReady := False;
    for Next := 1 to MAX_FIBERS do
      if State[Next] = fsReady then
      begin
        AnyReady := True;
        Current := Next;
        gFrom := 0;
        gTo   := Next;
        ContextSwitch;
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

  for I := 1 to MAX_FIBERS do
    FreeMem(RawPtr[I], STACK_WORDS * 2 + 16);
  DiagNum('MemAvail после освобождения', MemAvail);

  if Failures = 0 then
    Diag('ИТОГ: спайк S1b пройден')
  else
    DiagNum('ИТОГ: провалов', Failures);

  Close(Report);
  Halt(Failures);
end.
