{ ===================================================================
  Тесты политики планировщика.

  Здесь проверяется не переключение контекста, а решения: кто исполняется
  следующим, кого будить, кто просрочил квоту. Эти ошибки коварнее
  ассемблерных — голодание и потерянные пробуждения проявляются под
  нагрузкой через час, а не падают сразу.

  Всё это чистая логика над таблицей, поэтому прогон одинаков на обоих
  таргетах и занимает доли секунды.

  Отдельно проверяется СПРАВЕДЛИВОСТЬ: при шестнадцати готовых файберах
  за шестнадцать выборов каждый обязан получить управление ровно один
  раз. Планировщик, начинающий обход с начала таблицы, прошёл бы все
  остальные проверки и при этом уморил бы голодом последний файбер.
  =================================================================== }
program TstSched;

{$MODE TP}
{$R-}

uses
  TcResult, TcTest, TcSched;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  FuzzRounds = 2000;
  TapFile    = 'TSTSCHED.TAP';
{$ELSE}
  FuzzRounds = 40000;
  TapFile    = 'tstsched.tap';
{$ENDIF}

  PlannedTests = 48;
  Seed0 = 20260904;

var
  Rnd: LongInt;

{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

function RndBelow(N: LongInt): LongInt;
begin
  if N <= 0 then RndBelow := 0 else RndBelow := NextRnd mod N;
end;

var
  Id, A, B, C: TFiberId;
  R: TResult;
  I, J: Integer;
  N: Integer;
  Picks: array[0..MaxFibers] of Integer;
  Ids: array[1..MaxFibers] of TFiberId;
  Deadline: LongInt;
  Info: TFiberInfo;
  Bad: LongInt;
  { Отдельный счётчик для длинных циклов: в диалекте TP тип Integer
    шестнадцатибитный, и сорок тысяч раундов в него не помещаются. }
  Round: LongInt;
  MinP, MaxP: Integer;
  Seq: string;
  Pick: TFiberId;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('политика планировщика');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('предел файберов', MaxFibers);
  TestDiagInt('случайных раундов', FuzzRounds);

  { ================================================================
    Занятие и освобождение слотов
    ================================================================ }

  SchedReset;
  TestEqInt('после сброса все слоты свободны', SchedFreeSlots, MaxFibers);
  TestEqInt('готовых нет', SchedCount(fsReady), 0);
  TestEqInt('выбирать некого', SchedPick, SchedulerId);

  R := SchedSpawn('cmd-1', A);
  TestResultOk('слот занимается', R);
  TestTrue('выданный номер в пределах', (A >= 1) and (A <= MaxFibers));
  TestEqInt('свежий файбер готов', Ord(SchedInfo(A).State), Ord(fsReady));
  TestEqStr('имя сохранено', SchedInfo(A).Name, 'cmd-1');
  TestEqInt('свободных стало на один меньше', SchedFreeSlots, MaxFibers - 1);

  SchedFree(A);
  TestEqInt('слот возвращается', SchedFreeSlots, MaxFibers);

  { Предел одновременных команд — часть контракта ноды. Семнадцатая
    обязана получить вежливый отказ, а не порчу памяти. }
  SchedReset;
  N := 0;
  for I := 1 to MaxFibers do
  begin
    R := SchedSpawn('f', Id);
    if R.Ok then Inc(N);
  end;
  TestEqInt('занялись все слоты', N, MaxFibers);
  R := SchedSpawn('overflow', Id);
  TestResultErr('сверх предела — вежливый отказ', R, ERR_DECIDER_UNAVAILABLE);
  TestEqInt('при отказе номер не выдан', Id, SchedulerId);

  { ================================================================
    Справедливость обхода
    ================================================================ }

  SchedReset;
  for I := 1 to MaxFibers do
  begin
    R := SchedSpawn('f', Ids[I]);
    Picks[I] := 0;
  end;

  { Шестнадцать выборов при шестнадцати готовых: каждый ровно один раз. }
  for I := 1 to MaxFibers do
  begin
    Pick := SchedPick;
    if (Pick >= 1) and (Pick <= MaxFibers) then
      Inc(Picks[Pick]);
  end;

  MinP := Picks[1];
  MaxP := Picks[1];
  for I := 2 to MaxFibers do
  begin
    if Picks[I] < MinP then MinP := Picks[I];
    if Picks[I] > MaxP then MaxP := Picks[I];
  end;
  TestEqInt('за круг каждый выбран минимум один раз', MinP, 1);
  TestEqInt('за круг никто не выбран дважды', MaxP, 1);

  { Длинный прогон: перекос не должен накапливаться. }
  for I := 1 to MaxFibers do
    Picks[I] := 0;
  for I := 1 to MaxFibers * 100 do
  begin
    Pick := SchedPick;
    if (Pick >= 1) and (Pick <= MaxFibers) then
      Inc(Picks[Pick]);
  end;
  MinP := Picks[1];
  MaxP := Picks[1];
  for I := 2 to MaxFibers do
  begin
    if Picks[I] < MinP then MinP := Picks[I];
    if Picks[I] > MaxP then MaxP := Picks[I];
  end;
  TestDiagInt('минимум выборов на файбер', MinP);
  TestDiagInt('максимум выборов на файбер', MaxP);
  TestEqInt('за сто кругов перекоса нет', MaxP - MinP, 0);

  { Обход продолжается с места остановки, а не с начала таблицы.
    Планировщик, начинающий с единицы, уморил бы последний файбер. }
  SchedReset;
  R := SchedSpawn('a', A);
  R := SchedSpawn('b', B);
  R := SchedSpawn('c', C);
  Seq := '';
  for I := 1 to 6 do
  begin
    Pick := SchedPick;
    Seq := Seq + Chr(Ord('0') + Pick);
  end;
  TestEqStr('обход круговой, а не с начала', Seq, '123123');

  { ================================================================
    Состояния: кто не выбирается
    ================================================================ }

  SchedReset;
  R := SchedSpawn('a', A);
  R := SchedSpawn('b', B);

  SchedWaitKey(A, wrChannel, 7);
  TestEqInt('ожидающий не выбирается', SchedPick, B);
  TestEqInt('ожидающих один', SchedCount(fsWaiting), 1);
  TestTrue('есть ожидающие', SchedAnyWaiting);

  SchedFinish(B);
  TestEqInt('завершённый не выбирается', SchedPick, SchedulerId);

  SchedFail(A, ERR_DECIDER_PANIC);
  Info := SchedInfo(A);
  TestEqInt('упавший помечен', Ord(Info.State), Ord(fsFailed));
  TestEqStr('причина падения сохранена', Info.PanicCode, 'DECIDER_PANIC');
  TestEqInt('упавший не выбирается', SchedPick, SchedulerId);

  { Падение без кода неопознаваемо, поэтому подставляется заметный. }
  SchedReset;
  R := SchedSpawn('a', A);
  SchedFail(A, '');
  TestEqStr('падение без кода получает заметный', SchedInfo(A).PanicCode,
            'DECIDER_PANIC');

  { Ожидание без причины — дефект: такой файбер никто не разбудит,
    поэтому он падает сразу, а не зависает навсегда. }
  SchedReset;
  R := SchedSpawn('a', A);
  SchedWaitKey(A, wrNone, 0);
  TestEqInt('ожидание без причины роняет файбер',
            Ord(SchedInfo(A).State), Ord(fsFailed));

  { ================================================================
    Таймеры
    ================================================================ }

  SchedReset;
  R := SchedSpawn('a', A);
  R := SchedSpawn('b', B);
  SchedWaitTimer(A, 100);
  SchedWaitTimer(B, 200);

  TestEqInt('до срока никто не просыпается', SchedExpireTimers(99), 0);
  TestEqInt('на сроке просыпается ровно один', SchedExpireTimers(100), 1);
  TestEqInt('проснувшийся готов', Ord(SchedInfo(A).State), Ord(fsReady));
  TestEqInt('второй ещё ждёт', Ord(SchedInfo(B).State), Ord(fsWaiting));
  TestEqInt('позже просыпается второй', SchedExpireTimers(500), 1);
  TestEqInt('повторное истечение никого не будит', SchedExpireTimers(500), 0);

  { Сравнение нестрогое: срок, поставленный на текущий тик, обязан
    сработать сейчас. Иначе Sleep(0) означал бы Sleep(1). }
  SchedReset;
  R := SchedSpawn('a', A);
  SchedWaitTimer(A, 42);
  TestEqInt('срок на текущий тик срабатывает сразу',
            SchedExpireTimers(42), 1);

  SchedReset;
  R := SchedSpawn('a', A);
  R := SchedSpawn('b', B);
  SchedWaitTimer(A, 900);
  SchedWaitTimer(B, 300);
  TestTrue('ближайший срок находится', SchedNearestDeadline(Deadline));
  TestEqInt('ближайший срок — минимальный', Deadline, 300);

  SchedReset;
  TestFalse('без ожидающих таймера ближайшего срока нет',
            SchedNearestDeadline(Deadline));

  { ================================================================
    Пробуждение по ключу
    ================================================================ }

  SchedReset;
  R := SchedSpawn('a', A);
  R := SchedSpawn('b', B);
  R := SchedSpawn('c', C);
  SchedWaitKey(A, wrReply, 11);
  SchedWaitKey(B, wrReply, 22);
  SchedWaitKey(C, wrChannel, 11);

  TestEqInt('будится только совпавший по причине и ключу',
            SchedWake(wrReply, 11), 1);
  TestEqInt('разбуженный готов', Ord(SchedInfo(A).State), Ord(fsReady));
  TestEqInt('другой ключ не задет', Ord(SchedInfo(B).State), Ord(fsWaiting));
  TestEqInt('другая причина не задета', Ord(SchedInfo(C).State),
            Ord(fsWaiting));

  { Пробуждение, которого никто не ждёт, — не ошибка: ответ мог прийти
    после отмены. Но это повод для метрики, поэтому возвращается ноль. }
  TestEqInt('пробуждение без ожидающих возвращает ноль',
            SchedWake(wrReply, 999), 0);
  TestEqInt('пробуждение без причины ничего не делает',
            SchedWake(wrNone, 11), 0);

  { ================================================================
    Сторожевой таймер
    ================================================================ }

  SchedReset;
  R := SchedSpawn('slow', A);
  R := SchedSpawn('fast', B);
  SchedSetRunning(A, 1000);
  TestEqInt('в пределах квоты нарушителя нет',
            SchedOverrun(1005, 10), SchedulerId);
  TestEqInt('за пределами квоты нарушитель найден',
            SchedOverrun(1020, 10), A);
  TestEqInt('готовый файбер не считается нарушителем',
            Ord(SchedInfo(B).State), Ord(fsReady));

  SchedSetReady(A);
  TestEqInt('переставший исполняться не нарушитель',
            SchedOverrun(9999, 10), SchedulerId);

  { Счётчик очередей растёт только при получении управления. }
  SchedReset;
  R := SchedSpawn('a', A);
  SchedSetRunning(A, 1);
  SchedSetReady(A);
  SchedSetRunning(A, 2);
  TestEqInt('очереди посчитаны', SchedInfo(A).Turns, 2);

  { ================================================================
    Инварианты на случайных последовательностях
    ================================================================ }

  TestDiag('--- инварианты на случайных операциях ---');

  Rnd := Seed0;
  Bad := 0;
  SchedReset;

  for Round := 1 to FuzzRounds do
  begin
    case RndBelow(8) of
      0: begin
           R := SchedSpawn('f', Id);
         end;
      1: SchedFree(TFiberId(1 + RndBelow(MaxFibers)));
      2: SchedSetReady(TFiberId(1 + RndBelow(MaxFibers)));
      3: SchedWaitTimer(TFiberId(1 + RndBelow(MaxFibers)), RndBelow(1000));
      4: SchedWaitKey(TFiberId(1 + RndBelow(MaxFibers)), wrChannel,
                      Word(RndBelow(8)));
      5: SchedFinish(TFiberId(1 + RndBelow(MaxFibers)));
      6: N := SchedExpireTimers(RndBelow(1000));
      7: N := SchedWake(wrChannel, Word(RndBelow(8)));
    end;

    Pick := SchedPick;

    { Выбранный обязан быть готов — иначе планировщик отдаст управление
      файберу, который ждёт или уже умер. }
    if Pick <> SchedulerId then
      if SchedInfo(Pick).State <> fsReady then
        Inc(Bad);

    { Если готовых нет, выбирать некого, и наоборот. }
    if (SchedCount(fsReady) = 0) and (Pick <> SchedulerId) then Inc(Bad);
    if (SchedCount(fsReady) > 0) and (Pick = SchedulerId) then Inc(Bad);

    { Сумма по состояниям обязана сходиться: файбер всегда ровно в одном. }
    J := SchedCount(fsFree) + SchedCount(fsReady) + SchedCount(fsRunning) +
         SchedCount(fsWaiting) + SchedCount(fsDone) + SchedCount(fsFailed);
    if J <> MaxFibers then Inc(Bad);
  end;

  TestEqInt('инварианты держатся на всех случайных операциях', Bad, 0);

  { Голодание проверяется отдельно: при постоянно готовом наборе
    ни один файбер не должен быть обойдён больше круга подряд. }
  SchedReset;
  for I := 1 to MaxFibers do
    R := SchedSpawn('f', Ids[I]);
  for I := 1 to MaxFibers do
    Picks[I] := 0;

  Bad := 0;
  for I := 1 to MaxFibers * 50 do
  begin
    Pick := SchedPick;
    if Pick = SchedulerId then
    begin
      Inc(Bad);
      Break;
    end;
    Inc(Picks[Pick]);
    { Разница между самым частым и самым редким не может превысить
      единицу: обход круговой. }
    MinP := Picks[1]; MaxP := Picks[1];
    for J := 2 to MaxFibers do
    begin
      if Picks[J] < MinP then MinP := Picks[J];
      if Picks[J] > MaxP then MaxP := Picks[J];
    end;
    if MaxP - MinP > 1 then Inc(Bad);
  end;
  TestEqInt('голодания нет ни на одном шаге', Bad, 0);

  TestDiagInt('свободных слотов в конце', SchedFreeSlots);

  Halt(TestEnd);
end.
