{ ===================================================================
  TurboCore · переключение контекста.

  Единственный модуль проекта, который не может быть дуальным: это
  16-битный ассемблер, и на x86-64 такого нет. Поэтому здесь две
  реализации, и граница между ними проходит ровно по этому файлу.

    i8086-msdos — настоящее переключение пары SS:SP, проверенное
                  спайком S1b на ста тысячах переключений;
    нативный    — журнал вызовов вместо переключения. Тесты политики
                  планировщика проверяют последовательность решений,
                  не исполняя файберы.

  Такое разделение не компромисс, а следствие: ошибки в политике —
  голодание, потерянные пробуждения — проявляются под нагрузкой через
  час, а ошибка в ассемблере падает сразу. Дорогое к проверке отделено
  от дешёвого.

  МОДЕЛЬ ПАМЯТИ. Стек каждого файбера лежит в дальней куче, в
  собственном сегменте. Указатель нормализуется: смещение сводится к
  младшим четырём битам, остальное уходит в сегмент. Без этого стек мог
  бы пересечь границу сегмента, и SP завернулся бы через ноль посреди
  работы (ADR-0010).

  ПАДЕНИЕ ФАЙБЕРА. Отката через longjmp здесь нет, хотя изначально он
  планировался. Он не нужен: вся память команды живёт в её арене, а
  сброс арены делает планировщик, увидев состояние FAILED. Упавший
  файбер просто перестаёт получать управление, его стек остаётся как
  есть и переинициализируется при следующем использовании слота.
  Меньше движущихся частей при той же гарантии.
  =================================================================== }
unit TcFiber;

{$MODE TP}
{$ASMMODE INTEL}
{$F+}
{$S-}
{$R-}

interface

uses
  TcResult, TcSched;

type
  TFiberEntry = procedure;

{ Подготовить контекст файбера. Стек передаётся вызывающим: владение
  памятью остаётся у того, кто её выделил. }
function FiberPrepare(Id: TFiberId; Stack: Pointer; StackBytes: Word;
                      Entry: TFiberEntry): TResult;

{ Переключиться с FromId на ToId. Возврат происходит, когда переключат
  обратно. Оба контекста обязаны быть подготовлены. }
procedure FiberSwitch(FromId, ToId: TFiberId);

{ Кто исполняется прямо сейчас. }
function FiberCurrent: TFiberId;
procedure FiberSetCurrent(Id: TFiberId);

{ Глубина использованного стека в байтах — по «грязной» отметке.
  Ноль, если стек не подготовлен или уже переиспользован. }
function FiberStackDepth(Id: TFiberId): Word;

{ Целы ли канареечные слова. Проверяется планировщиком после каждого
  переключения в отладочной сборке. }
function FiberCanaryIntact(Id: TFiberId): Boolean;

{$IFNDEF CPU16}
{ Только на нативном таргете: журнал переключений для тестов политики. }
procedure FiberLogReset;
function FiberLogCount: Integer;
function FiberLogFrom(Index: Integer): Integer;
function FiberLogTo(Index: Integer): Integer;
{$ENDIF}

implementation

const
  CanaryLo    = $DEAD;
  CanaryHi    = $C0DE;
  FillPattern = $A5A5;

  { Смещения кадра от вершины стека, в словах. Порядок задан тем, что
    кладёт на стек дальний вызов и последующие push в ContextSwitch:
    от младших адресов к старшим — FLAGS, ES, DS, DI, SI, BP, IP, CS.

    Список обязан совпадать с телом ContextSwitch слово в слово. Первая
    версия этого модуля пришла из спайка S1b, где стояли cli и sti; при
    замене их на pushf и popf кадр вырос на слово, а константы остались
    прежними — и первое же настоящее переключение уводило управление в
    мусор. }
  OfsCS    = 2;
  OfsIP    = 3;
  OfsBP    = 4;
  OfsSI    = 5;
  OfsDI    = 6;
  OfsDS    = 7;
  OfsES    = 8;
  OfsFlags = 9;   { сюда встанет SP }

  { Флаги нового файбера: прерывания разрешены, бит 1 зарезервирован
    и всегда установлен. }
  InitialFlags = $0202;

type
  TFarAddr = record
    Ofs, Seg: Word;
  end;

  PStackWords = ^TStackWords;
  TStackWords = array[0..16383] of Word;

var
  { Сохранённые контексты. Индекс 0 — планировщик. }
  SSSlots: array[0..MaxFibers] of Word;
  SPSlots: array[0..MaxFibers] of Word;

  { Описание стека для замера глубины и проверки канареек. }
  StackBase:  array[0..MaxFibers] of PStackWords;
  StackWords: array[0..MaxFibers] of Word;

  gFrom, gTo: Word;
  Current: TFiberId;

{$IFNDEF CPU16}
const
  LogCap = 256;
var
  LogFrom, LogTo: array[0..LogCap - 1] of Integer;
  LogN: Integer;
{$ENDIF}

{$IFDEF CPU16}

{ ------------------------------------------------------------------
  Настоящее переключение. Обе стороны находятся в одной точке одной
  процедуры, поэтому кадры симметричны: что положено на стек уходящего
  файбера, то отсюда же снимется со стека приходящего.

  nostackframe обязателен — штатный эпилог восстановил бы SP из BP и
  отменил переключение. Возврат дальний: все процедуры объявлены far,
  и тогда конструкция работает в любой модели памяти.

  Пара mov ss / mov sp идёт под запретом прерываний. На 8086 запись в SS
  и так подавляет прерывание на одну инструкцию, но полагаться на это
  незачем. pushf/popf вместо cli/sti: иначе прерывания включались бы
  там, где вызывающий их намеренно выключил.
  ------------------------------------------------------------------ }
procedure ContextSwitch; assembler; nostackframe;
asm
  push bp
  push si
  push di
  push ds
  push es
  pushf

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

  popf
  pop  es
  pop  ds
  pop  di
  pop  si
  pop  bp
  retf
end;

function FiberPrepare(Id: TFiberId; Stack: Pointer; StackBytes: Word;
                      Entry: TFiberEntry): TResult;
var
  A: TFarAddr absolute Stack;
  NSeg, NOfs: Word;
  Words: Word;
  W: PStackWords;
  P: Pointer;
  PA: TFarAddr absolute P;
  EntryVar: TFiberEntry;
  EntryAddr: TFarAddr absolute EntryVar;
  I: Word;
begin
  if (Id < 1) or (Id > MaxFibers) or (Stack = nil) then
  begin
    FiberPrepare := Err('DECIDER_PANIC');
    Exit;
  end;

  { Кадру нужно девять слов плюс место под работу. Меньше килобайта
    брать незачем: измеренная в S1b глубина простого файбера 38 байт,
    рекурсивного — 220. }
  if StackBytes < 256 then
  begin
    FiberPrepare := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  { Нормализация: смещение в младшие четыре бита, остальное в сегмент.
    Иначе стек мог бы пересечь границу сегмента. }
  NSeg := A.Seg + (A.Ofs shr 4);
  NOfs := A.Ofs and $000F;
  StackBytes := StackBytes - NOfs;

  PA.Seg := NSeg;
  PA.Ofs := NOfs;
  W := PStackWords(P);

  Words := StackBytes div 2;
  if Words > 16384 then
    Words := 16384;

  for I := 0 to Words - 1 do
    W^[I] := FillPattern;

  W^[0] := CanaryLo;
  W^[Words - 1] := CanaryHi;

  EntryVar := Entry;
  W^[Words - OfsCS] := EntryAddr.Seg;
  W^[Words - OfsIP] := EntryAddr.Ofs;
  W^[Words - OfsBP] := 0;
  W^[Words - OfsSI] := 0;
  W^[Words - OfsDI] := 0;
  W^[Words - OfsDS] := Dseg;
  W^[Words - OfsES] := Dseg;
  W^[Words - OfsFlags] := InitialFlags;

  SSSlots[Id] := NSeg;
  SPSlots[Id] := NOfs + (Words - OfsFlags) * 2;
  StackBase[Id] := W;
  StackWords[Id] := Words;

  FiberPrepare := Ok;
end;

procedure FiberSwitch(FromId, ToId: TFiberId);
begin
  gFrom := FromId;
  gTo := ToId;
  ContextSwitch;
end;

{$ELSE}

{ ------------------------------------------------------------------
  Нативная реализация: журнал вместо переключения.

  Настоящее переключение здесь невозможно, и подделывать его нечестно.
  Вместо этого каждый вызов записывается, и тесты политики проверяют
  последовательность принятых решений — то есть ровно то, что здесь
  есть смысл проверять.
  ------------------------------------------------------------------ }

function FiberPrepare(Id: TFiberId; Stack: Pointer; StackBytes: Word;
                      Entry: TFiberEntry): TResult;
begin
  if (Id < 1) or (Id > MaxFibers) or (Stack = nil) then
  begin
    FiberPrepare := Err('DECIDER_PANIC');
    Exit;
  end;
  if StackBytes < 256 then
  begin
    FiberPrepare := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;
  StackBase[Id] := PStackWords(Stack);
  StackWords[Id] := StackBytes div 2;
  FiberPrepare := Ok;
end;

procedure FiberSwitch(FromId, ToId: TFiberId);
begin
  if LogN < LogCap then
  begin
    LogFrom[LogN] := FromId;
    LogTo[LogN] := ToId;
    Inc(LogN);
  end;
  Current := ToId;
end;

procedure FiberLogReset;
begin
  LogN := 0;
end;

function FiberLogCount: Integer;
begin
  FiberLogCount := LogN;
end;

function FiberLogFrom(Index: Integer): Integer;
begin
  if (Index < 0) or (Index >= LogN) then
    FiberLogFrom := -1
  else
    FiberLogFrom := LogFrom[Index];
end;

function FiberLogTo(Index: Integer): Integer;
begin
  if (Index < 0) or (Index >= LogN) then
    FiberLogTo := -1
  else
    FiberLogTo := LogTo[Index];
end;

{$ENDIF}

function FiberCurrent: TFiberId;
begin
  FiberCurrent := Current;
end;

procedure FiberSetCurrent(Id: TFiberId);
begin
  Current := Id;
end;

function FiberStackDepth(Id: TFiberId): Word;
var
  I, N: Word;
  W: PStackWords;
begin
  FiberStackDepth := 0;
  if (Id < 1) or (Id > MaxFibers) then Exit;
  W := StackBase[Id];
  N := StackWords[Id];
  if (W = nil) or (N = 0) then Exit;

  I := 1;
  while (I < N - 1) and (W^[I] = FillPattern) do
    Inc(I);
  FiberStackDepth := (N - I) * 2;
end;

function FiberCanaryIntact(Id: TFiberId): Boolean;
var
  W: PStackWords;
  N: Word;
begin
  FiberCanaryIntact := True;
  if (Id < 1) or (Id > MaxFibers) then Exit;
  W := StackBase[Id];
  N := StackWords[Id];
  if (W = nil) or (N = 0) then Exit;
  FiberCanaryIntact := (W^[0] = CanaryLo) and (W^[N - 1] = CanaryHi);
end;

begin
  FillChar(SSSlots, SizeOf(SSSlots), 0);
  FillChar(SPSlots, SizeOf(SPSlots), 0);
  FillChar(StackBase, SizeOf(StackBase), 0);
  FillChar(StackWords, SizeOf(StackWords), 0);
  Current := SchedulerId;
{$IFNDEF CPU16}
  LogN := 0;
{$ENDIF}
end.
