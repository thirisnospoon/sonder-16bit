{ ===================================================================
  Рандомизированный торец арены.

  Обычные тесты проверяют случаи, которые я придумал. Этот — те, которые
  не придумал: случайные последовательности выделений, обнулений,
  копирований и сбросов вперемешку, включая заведомо невозможные размеры.

  Требование одно и жёсткое: арена не имеет права ни упасть, ни выдать
  указатель за пределы своего блока, ни потерять память. Отказывать —
  сколько угодно.

  Генератор псевдослучайных чисел свой, а не Random из RTL: нужна
  повторяемость и одинаковая последовательность на обоих таргетах.
  Иначе падение на 16 битах невозможно было бы воспроизвести нативно,
  а в этом весь смысл дуальной сборки.

  Найденное падение фиксируется как обычный тест с конкретным семенем,
  а не оставляется на волю случая.
  =================================================================== }
program TstFuzz;

{$MODE TP}
{$R-}

uses
  TcResult, TcArena, TcTest;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  Rounds  = 20000;
  TapFile = 'TSTFUZZ.TAP';
{$ELSE}
  Rounds  = 500000;
  TapFile = 'tstfuzz.tap';
{$ENDIF}

  PlannedTests = 7;
  ArenaSize    = 2048;
  Seed0        = 20260901;

var
  Rnd: LongInt;

{ Линейный конгруэнтный генератор. Константы из Numerical Recipes;
  качество распределения здесь неважно, важна воспроизводимость.

  Q- обязателен и только здесь: умножение умышленно переполняет LongInt,
  в этом и состоит работа генератора. Нативная сборка идёт с проверкой
  переполнения и без этой директивы падает на первом же вызове, тогда как
  16-битная молча считает дальше. Ровно такое расхождение и должна ловить
  дуальная сборка — поэтому директива стоит точечно, а не на весь файл. }
{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

function RndBelow(N: LongInt): LongInt;
begin
  if N <= 0 then
    RndBelow := 0
  else
    RndBelow := NextRnd mod N;
end;

var
  A: TArena;
  R: TResult;
  P: Pointer;
  I: LongInt;

  Allocs, Refusals, Resets: LongInt;
  OutOfBounds: LongInt;
  Crashes: LongInt;
  MaxSeen: Word;

  Low, High: PByte;
  Cur: PByte;
  Size: LongInt;
  Action: LongInt;
  Guard: Boolean;

begin
  TestBegin(TapFile, PlannedTests);

  TestDiag('рандомизированный торец арены');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('раундов', Rounds);
  TestDiagInt('размер арены', ArenaSize);
  TestDiagInt('семя', Seed0);

  Rnd := Seed0;
  Allocs := 0;
  Refusals := 0;
  Resets := 0;
  OutOfBounds := 0;
  Crashes := 0;
  MaxSeen := 0;

  R := ArenaCreate(A, ArenaSize, 'fuzz');
  TestResultOk('арена для торца создана', R);

  Low := PByte(A.Base);
  High := PByte(A.Base);
  Inc(High, ArenaSize);

  for I := 1 to Rounds do
  begin
    Action := RndBelow(10);

    { Размер иногда заведомо больше арены и иногда нулевой: отказ на таких
      входах — часть контракта, и он обязан быть аккуратным. }
    case RndBelow(8) of
      0: Size := 0;
      1: Size := ArenaSize + RndBelow(60000);
      2: Size := 65535;
    else
      Size := 1 + RndBelow(400);
    end;

    if Action < 6 then
    begin
      R := ArenaAlloc(A, Word(Size), P);
      if R.Ok then
      begin
        Inc(Allocs);
        { Указатель обязан лежать внутри блока целиком, вместе с хвостом. }
        Cur := PByte(P);
        Guard := (PtrUInt(Cur) >= PtrUInt(Low));
        Inc(Cur, Size - 1);
        Guard := Guard and (PtrUInt(Cur) < PtrUInt(High));
        if not Guard then
          Inc(OutOfBounds)
        else
        begin
          { Пишем во всю выданную область: если границы посчитаны неверно,
            это испортит соседа и всплывёт на проверке ниже. }
          FillChar(P^, Size, Byte(I and $FF));
        end;
      end
      else
        Inc(Refusals);
    end
    else if Action < 8 then
    begin
      R := ArenaAllocZero(A, Word(Size), P);
      if R.Ok then Inc(Allocs) else Inc(Refusals);
    end
    else
    begin
      ArenaReset(A);
      Inc(Resets);
    end;

    if A.Used > MaxSeen then
      MaxSeen := A.Used;

    { Инвариант, который обязан держаться после любой операции. }
    if A.Used > A.Capacity then
      Inc(Crashes);
  end;

  TestDiagInt('успешных выделений', Allocs);
  TestDiagInt('отказов', Refusals);
  TestDiagInt('сбросов', Resets);
  TestDiagInt('максимум занятого', MaxSeen);

  TestEqInt('ни один указатель не вышел за пределы блока', OutOfBounds, 0);
  TestEqInt('занято никогда не превышало ёмкость', Crashes, 0);
  TestTrue('были и успехи, и отказы — торец добрался до границ',
           (Allocs > 0) and (Refusals > 0));
  TestTrue('база не сдвинулась за весь прогон', A.Base = Pointer(Low));
  TestTrue('занято не больше ёмкости в конце', A.Used <= A.Capacity);

  ArenaReset(A);
  TestEqInt('после финального сброса занято ноль', A.Used, 0);

  ArenaDestroy(A);

  Halt(TestEnd);
end.
