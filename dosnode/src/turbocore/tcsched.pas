{ ===================================================================
  TurboCore · политика планировщика.

  Здесь нет ни ассемблера, ни переключения контекста — только таблица
  файберов и решение о том, кто исполняется следующим. Разделение
  намеренное и определяет, что можно проверить.

  Переключение контекста дуальным быть не может: это 16-битный
  ассемблер, на x86-64 такого нет. Зато политика — чистая логика над
  таблицей, и она проверяется на нативном таргете тысячами случаев в
  секунду. Сам переключатель живёт в TcFiber и проверяется на DOS, где
  спайк S1b уже прогнал сто тысяч переключений.

  Ошибки в политике — голодание, потерянные пробуждения, гонка на
  истечении срока — гораздо коварнее ошибок в ассемблере: последние
  падают сразу, первые проявляются под нагрузкой через час.

  СПРАВЕДЛИВОСТЬ. Обход круговой с сохранением позиции: следующий поиск
  начинается за последним выбранным. Без этого файбер с меньшим номером
  получал бы управление всегда, а последний — никогда.
  =================================================================== }
unit TcSched;

{$MODE TP}

interface

uses
  TcResult;

const
  { Предел одновременных команд. Константа компиляции и часть контракта
    ноды: семнадцатая команда получает вежливый отказ, а не порчу памяти
    (docs/TURBOCORE.md §12). }
  MaxFibers = 16;

  { Ноль зарезервирован за планировщиком: он тоже контекст, между которым
    и файберами происходит переключение. }
  SchedulerId = 0;

  FiberNameLen = 15;

type
  TFiberId = 0..MaxFibers;

  TFiberState = (
    fsFree,      { слот свободен }
    fsReady,     { готов исполняться }
    fsRunning,   { исполняется прямо сейчас }
    fsWaiting,   { ждёт события или срока }
    fsDone,      { отработал штатно }
    fsFailed     { упал; причина в PanicCode }
  );

  TWaitReason = (
    wrNone,
    wrTimer,     { ждёт наступления срока }
    wrChannel,   { ждёт данных в канале }
    wrReply      { ждёт ответа по корреляционному ключу }
  );

  TFiberInfo = record
    State:     TFiberState;
    Wait:      TWaitReason;
    Deadline:  LongInt;              { для wrTimer }
    WaitKey:   Word;                 { канал или корреляция }
    Name:      string[FiberNameLen];
    Turns:     LongInt;              { сколько раз получал управление }
    StartTick: LongInt;              { когда получил управление в этот раз }
    PanicCode: TErrCode;
  end;

procedure SchedReset;

{ Занять слот. Отказывает, когда свободных нет: это нормальный исход,
  а не дефект, и вызывающий обязан отдать команде отказ. }
function SchedSpawn(const Name: string; var Id: TFiberId): TResult;

{ Освободить слот. Стеком и ареной владеет вызывающий. }
procedure SchedFree(Id: TFiberId);

procedure SchedSetReady(Id: TFiberId);
procedure SchedSetRunning(Id: TFiberId; Now: LongInt);
procedure SchedWaitTimer(Id: TFiberId; Deadline: LongInt);
procedure SchedWaitKey(Id: TFiberId; Reason: TWaitReason; Key: Word);
procedure SchedFinish(Id: TFiberId);
procedure SchedFail(Id: TFiberId; const Code: TErrCode);

{ Перевести в READY всех, чей срок истёк. Возвращает число разбуженных.

  Сравнение нестрогое: файбер, поставивший срок на текущий тик, обязан
  проснуться сейчас, а не через тик. Иначе Sleep(0) означал бы Sleep(1). }
function SchedExpireTimers(Now: LongInt): Integer;

{ Разбудить всех, кто ждёт по этому ключу. Возвращает число разбуженных.
  Ноль означает, что пробуждение пришло раньше ожидания или уже не нужно —
  это не ошибка, но повод для метрики. }
function SchedWake(Reason: TWaitReason; Key: Word): Integer;

{ Кто исполняется следующим. SchedulerId означает «работать некому».

  Обход круговой: поиск начинается за последним выбранным, поэтому ни
  один готовый файбер не может быть обойдён дважды подряд. }
function SchedPick: TFiberId;

function SchedCount(State: TFiberState): Integer;
function SchedAnyWaiting: Boolean;
function SchedInfo(Id: TFiberId): TFiberInfo;
function SchedFreeSlots: Integer;

{ Ближайший срок среди ожидающих таймера. Возвращает False, если таких
  нет: тогда цикл событий может спать до внешнего прерывания. }
function SchedNearestDeadline(var Deadline: LongInt): Boolean;

{ Файбер, исполняющийся дольше квоты. SchedulerId — таких нет.
  Отдельно от политики выбора: это диагностика, а не решение. }
function SchedOverrun(Now: LongInt; Quantum: LongInt): TFiberId;

implementation

var
  Tab: array[1..MaxFibers] of TFiberInfo;
  Cursor: TFiberId;      { где остановился прошлый обход }

procedure SchedReset;
var
  I: Integer;
begin
  for I := 1 to MaxFibers do
  begin
    FillChar(Tab[I], SizeOf(Tab[I]), 0);
    Tab[I].State := fsFree;
    Tab[I].Wait := wrNone;
  end;
  Cursor := SchedulerId;
end;

function Valid(Id: TFiberId): Boolean;
begin
  Valid := (Id >= 1) and (Id <= MaxFibers);
end;

function SchedSpawn(const Name: string; var Id: TFiberId): TResult;
var
  I: Integer;
begin
  Id := SchedulerId;
  for I := 1 to MaxFibers do
    if Tab[I].State = fsFree then
    begin
      FillChar(Tab[I], SizeOf(Tab[I]), 0);
      Tab[I].State := fsReady;
      Tab[I].Wait := wrNone;
      if Length(Name) > FiberNameLen then
        Tab[I].Name := Copy(Name, 1, FiberNameLen)
      else
        Tab[I].Name := Name;
      Id := I;
      SchedSpawn := Ok;
      Exit;
    end;

  { Свободных слотов нет. Это предел, объявленный контрактом ноды,
    и отказ здесь — правильное поведение. }
  SchedSpawn := Err('DECIDER_UNAVAILABLE');
end;

procedure SchedFree(Id: TFiberId);
begin
  if not Valid(Id) then Exit;
  FillChar(Tab[Id], SizeOf(Tab[Id]), 0);
  Tab[Id].State := fsFree;
  Tab[Id].Wait := wrNone;
end;

procedure SchedSetReady(Id: TFiberId);
begin
  if not Valid(Id) then Exit;
  if Tab[Id].State = fsFree then Exit;
  Tab[Id].State := fsReady;
  Tab[Id].Wait := wrNone;
end;

procedure SchedSetRunning(Id: TFiberId; Now: LongInt);
begin
  if not Valid(Id) then Exit;
  Tab[Id].State := fsRunning;
  Tab[Id].Wait := wrNone;
  Tab[Id].StartTick := Now;
  Inc(Tab[Id].Turns);
end;

procedure SchedWaitTimer(Id: TFiberId; Deadline: LongInt);
begin
  if not Valid(Id) then Exit;
  Tab[Id].State := fsWaiting;
  Tab[Id].Wait := wrTimer;
  Tab[Id].Deadline := Deadline;
end;

procedure SchedWaitKey(Id: TFiberId; Reason: TWaitReason; Key: Word);
begin
  if not Valid(Id) then Exit;
  { Ожидание без причины — дефект: такой файбер никто не разбудит. }
  if Reason = wrNone then
  begin
    SchedFail(Id, 'DECIDER_PANIC');
    Exit;
  end;
  Tab[Id].State := fsWaiting;
  Tab[Id].Wait := Reason;
  Tab[Id].WaitKey := Key;
end;

procedure SchedFinish(Id: TFiberId);
begin
  if not Valid(Id) then Exit;
  Tab[Id].State := fsDone;
  Tab[Id].Wait := wrNone;
end;

procedure SchedFail(Id: TFiberId; const Code: TErrCode);
begin
  if not Valid(Id) then Exit;
  Tab[Id].State := fsFailed;
  Tab[Id].Wait := wrNone;
  if Code = '' then
    Tab[Id].PanicCode := 'DECIDER_PANIC'
  else
    Tab[Id].PanicCode := Code;
end;

function SchedExpireTimers(Now: LongInt): Integer;
var
  I, N: Integer;
begin
  N := 0;
  for I := 1 to MaxFibers do
    if (Tab[I].State = fsWaiting) and (Tab[I].Wait = wrTimer) and
       (Tab[I].Deadline <= Now) then
    begin
      Tab[I].State := fsReady;
      Tab[I].Wait := wrNone;
      Inc(N);
    end;
  SchedExpireTimers := N;
end;

function SchedWake(Reason: TWaitReason; Key: Word): Integer;
var
  I, N: Integer;
begin
  N := 0;
  if Reason = wrNone then
  begin
    SchedWake := 0;
    Exit;
  end;
  for I := 1 to MaxFibers do
    if (Tab[I].State = fsWaiting) and (Tab[I].Wait = Reason) and
       (Tab[I].WaitKey = Key) then
    begin
      Tab[I].State := fsReady;
      Tab[I].Wait := wrNone;
      Inc(N);
    end;
  SchedWake := N;
end;

function SchedPick: TFiberId;
var
  I, Idx: Integer;
begin
  { Начинаем за последним выбранным и обходим круг ровно один раз.
    Так ни один готовый файбер не будет обойдён дважды подряд, и
    голодание невозможно по построению. }
  for I := 1 to MaxFibers do
  begin
    Idx := Cursor + I;
    while Idx > MaxFibers do
      Dec(Idx, MaxFibers);
    if Tab[Idx].State = fsReady then
    begin
      Cursor := Idx;
      SchedPick := Idx;
      Exit;
    end;
  end;
  SchedPick := SchedulerId;
end;

function SchedCount(State: TFiberState): Integer;
var
  I, N: Integer;
begin
  N := 0;
  for I := 1 to MaxFibers do
    if Tab[I].State = State then Inc(N);
  SchedCount := N;
end;

function SchedAnyWaiting: Boolean;
begin
  SchedAnyWaiting := SchedCount(fsWaiting) > 0;
end;

function SchedInfo(Id: TFiberId): TFiberInfo;
var
  Empty: TFiberInfo;
begin
  if Valid(Id) then
    SchedInfo := Tab[Id]
  else
  begin
    FillChar(Empty, SizeOf(Empty), 0);
    Empty.State := fsFree;
    SchedInfo := Empty;
  end;
end;

function SchedFreeSlots: Integer;
begin
  SchedFreeSlots := SchedCount(fsFree);
end;

function SchedNearestDeadline(var Deadline: LongInt): Boolean;
var
  I: Integer;
  Found: Boolean;
begin
  Found := False;
  for I := 1 to MaxFibers do
    if (Tab[I].State = fsWaiting) and (Tab[I].Wait = wrTimer) then
      if (not Found) or (Tab[I].Deadline < Deadline) then
      begin
        Deadline := Tab[I].Deadline;
        Found := True;
      end;
  SchedNearestDeadline := Found;
end;

function SchedOverrun(Now: LongInt; Quantum: LongInt): TFiberId;
var
  I: Integer;
begin
  for I := 1 to MaxFibers do
    if (Tab[I].State = fsRunning) and (Now - Tab[I].StartTick > Quantum) then
    begin
      SchedOverrun := I;
      Exit;
    end;
  SchedOverrun := SchedulerId;
end;

begin
  SchedReset;
end.
