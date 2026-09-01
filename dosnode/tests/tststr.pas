{ ===================================================================
  Тесты строки произвольной длины.

  Модуль долго жил без собственных тестов: его проверяли косвенно, через
  доменные правила. Так делать нельзя — примитив фреймворка ломается
  тише всего, а виноватым выглядит тот, кто им пользуется.

  Основной вес здесь на StrCharLen. Счёт символов выглядит арифметикой, а
  на деле это разбор формата, у которого есть недопустимые записи, и
  принимать их нельзя даже когда «оно же посчиталось».

  Проверяются все четыре длины последовательности, обе границы каждой,
  избыточные формы, суррогаты, обрывы и продолжающий байт на месте
  ведущего. Отдельно — прогон на случайных байтах: разборщик не имеет
  права ни падать, ни насчитать символов больше, чем в строке байт.
  =================================================================== }
program TstStr;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcTest;

const
{$IFDEF CPU16}
  TapFile    = 'TSTSTR.TAP';
  FuzzRounds = 5000;
{$ELSE}
  TapFile    = 'tststr.tap';
  FuzzRounds = 200000;
{$ENDIF}

  PlannedTests = 52;
  Seed0 = 20260904;

var
  S, T: TStr;
  N: Word;
  Buf: array[0..63] of Byte;
  Pas: string;
  Rnd: LongInt;
  I, J, Bad, Overcount, Accepted: LongInt;
  Len: Word;

{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

{ Строка из перечисленных байт. Байты, а не литерал: половина проверяемых
  случаев — это последовательности, которых в корректном тексте не бывает,
  и записать их литералом нельзя. }
function Bytes(const B: array of Byte): TStr;
var
  K: Integer;
  R: TStr;
begin
  for K := 0 to High(B) do
    Buf[K] := B[K];
  R.Ptr := PChar(@Buf[0]);
  R.Len := High(B) + 1;
  Bytes := R;
end;

{ Проверка «принято и посчитано столько-то». Два утверждения слиты в одно
  намеренно: посчитанное значение при отказе не определено, и проверять
  его отдельно значило бы читать мусор. }
procedure CheckLen(const Name: string; const V: TStr; Want: Word);
var
  Got: Word;
begin
  if not StrCharLen(V, Got) then
  begin
    TestOk(Name, False);
    TestDiag('  отвергнуто, а ожидалось ' + 'принятие');
    Exit;
  end;
  TestOk(Name, Got = Want);
  if Got <> Want then
  begin
    TestDiagInt('  посчитано', Got);
    TestDiagInt('  ожидалось', Want);
  end;
end;

procedure CheckBad(const Name: string; const V: TStr);
var
  Got: Word;
begin
  TestOk(Name, not StrCharLen(V, Got));
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('строка произвольной длины');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('SizeOf(TStr)', SizeOf(TStr));
  TestDiagInt('случайных строк', FuzzRounds);

  { ================================================================
    Основа
    ================================================================ }

  S := StrNil;
  TestTrue('пустая строка пуста', StrIsEmpty(S));
  TestEqInt('длина пустой строки', S.Len, 0);
  TestTrue('пустая строка пробельна по смыслу', StrIsBlank(S));

  Pas := 'andrey';
  S := StrView(Pas);
  TestEqInt('вид на строку сохраняет длину', S.Len, 6);
  TestFalse('непустая строка не пуста', StrIsEmpty(S));
  TestFalse('непустая строка не пробельна', StrIsBlank(S));
  TestEqStr('голова строки читается', StrHead(S), 'andrey');

  Pas := '   ';
  S := StrView(Pas);
  TestTrue('строка из пробелов пробельна', StrIsBlank(S));
  TestFalse('строка из пробелов не пуста технически', StrIsEmpty(S));

  Pas := 'andrey';
  S := StrView(Pas);
  TestEqInt('символ по индексу', Ord(StrCharAt(S, 0)), Ord('a'));

  Pas := 'andrey';
  S := StrView(Pas);
  T := StrView(Pas);
  TestTrue('равные строки равны', StrEq(S, T));
  TestTrue('сравнение с паскалевской строкой', StrEqPas(S, 'andrey'));
  TestFalse('разные строки не равны', StrEqPas(S, 'Andrey'));

  { ================================================================
    StrCharLen: корректные записи
    ================================================================ }

  CheckLen('пустая строка — ноль символов', StrNil, 0);

  Pas := 'hello';
  CheckLen('ASCII считается побайтно', StrView(Pas), 5);

  CheckLen('однобайтовая граница: 00', Bytes([$00]), 1);
  CheckLen('однобайтовая граница: 7F', Bytes([$7F]), 1);

  { U+0080 — самая короткая двухбайтовая запись. }
  CheckLen('двухбайтовая граница снизу: C2 80', Bytes([$C2, $80]), 1);
  CheckLen('двухбайтовая граница сверху: DF BF', Bytes([$DF, $BF]), 1);

  { U+0800 и U+FFFF. }
  CheckLen('трёхбайтовая граница снизу: E0 A0 80',
           Bytes([$E0, $A0, $80]), 1);
  CheckLen('трёхбайтовая граница сверху: EF BF BF',
           Bytes([$EF, $BF, $BF]), 1);

  { U+D7FF — последняя кодовая точка перед областью суррогатов. }
  CheckLen('перед суррогатами: ED 9F BF', Bytes([$ED, $9F, $BF]), 1);

  { U+10000 и U+10FFFF. }
  CheckLen('четырёхбайтовая граница снизу: F0 90 80 80',
           Bytes([$F0, $90, $80, $80]), 1);
  CheckLen('четырёхбайтовая граница сверху: F4 8F BF BF',
           Bytes([$F4, $8F, $BF, $BF]), 1);

  { Ради этого всё и затевалось: кириллица занимает по два байта, и
    длина в символах вдвое меньше длины в байтах. }
  Pas := 'Андрей';
  S := StrView(Pas);
  TestEqInt('шесть кириллических букв — двенадцать байт', S.Len, 12);
  CheckLen('шесть кириллических букв — шесть символов', S, 6);

  { Смесь всех четырёх длин в одной строке. }
  CheckLen('смесь однобайтовых и многобайтовых',
           Bytes([$61, $D1, $8F, $E2, $82, $AC, $F0, $9F, $98, $80]), 4);

  { ================================================================
    StrCharLen: записи, которых не бывает

    Все они отвергаются, а не «как-нибудь считаются». Принять их значило
    бы пустить в домен строку, которую Java-сторона считает невозможной.
    ================================================================ }

  CheckBad('продолжающий байт на месте ведущего: 80', Bytes([$80]));
  CheckBad('продолжающий байт на месте ведущего: BF', Bytes([$BF]));
  CheckBad('продолжающий байт после ASCII: 61 BF', Bytes([$61, $BF]));

  { C0 и C1 существуют только как избыточная запись ASCII. }
  CheckBad('избыточная запись NUL: C0 80', Bytes([$C0, $80]));
  CheckBad('избыточная запись: C1 BF', Bytes([$C1, $BF]));

  CheckBad('избыточная трёхбайтовая: E0 80 80', Bytes([$E0, $80, $80]));
  CheckBad('избыточная трёхбайтовая: E0 9F BF', Bytes([$E0, $9F, $BF]));
  CheckBad('избыточная четырёхбайтовая: F0 80 80 80',
           Bytes([$F0, $80, $80, $80]));
  CheckBad('избыточная четырёхбайтовая: F0 8F BF BF',
           Bytes([$F0, $8F, $BF, $BF]));

  { Суррогаты D800..DFFF в UTF-8 не существуют вовсе. }
  CheckBad('суррогат D800: ED A0 80', Bytes([$ED, $A0, $80]));
  CheckBad('суррогат DFFF: ED BF BF', Bytes([$ED, $BF, $BF]));

  { За верхней границей Unicode. }
  CheckBad('за U+10FFFF: F4 90 80 80', Bytes([$F4, $90, $80, $80]));
  CheckBad('ведущий байт вне диапазона: F5', Bytes([$F5, $80, $80, $80]));
  CheckBad('ведущий байт вне диапазона: FF', Bytes([$FF]));

  { Обрывы. Строка кончилась там, где последовательность продолжается —
    ровно то, что даст усечённый кадр. }
  CheckBad('обрыв двухбайтовой: D1', Bytes([$D1]));
  CheckBad('обрыв трёхбайтовой: E2 82', Bytes([$E2, $82]));
  CheckBad('обрыв четырёхбайтовой: F0 9F 98', Bytes([$F0, $9F, $98]));

  { Второй байт не продолжающий. }
  CheckBad('второй байт не продолжающий: D1 41', Bytes([$D1, $41]));
  CheckBad('третий байт не продолжающий: E2 82 41',
           Bytes([$E2, $82, $41]));
  CheckBad('четвёртый байт не продолжающий: F0 9F 98 41',
           Bytes([$F0, $9F, $98, $41]));

  { Испорченное в конце длинной корректной строки не должно теряться. }
  CheckBad('порча в хвосте корректной строки',
           Bytes([$61, $62, $63, $D1, $8F, $E0, $80, $80]));

  { ================================================================
    Случайные байты

    Разборщик читает то, что пришло по линии, и не имеет права ни падать,
    ни насчитать символов больше, чем в строке байт: каждый символ — это
    хотя бы один байт.
    ================================================================ }

  Rnd := Seed0;
  Bad := 0;
  Overcount := 0;
  Accepted := 0;

  for I := 1 to FuzzRounds do
  begin
    Len := Word(NextRnd mod 17);
    for J := 0 to Integer(Len) - 1 do
      Buf[J] := Byte(NextRnd and $FF);
    S.Ptr := PChar(@Buf[0]);
    S.Len := Len;

    if StrCharLen(S, N) then
    begin
      Inc(Accepted);
      if N > Len then
        Inc(Overcount);
    end
    else
      Inc(Bad);
  end;

  TestEqInt('на случайных байтах символов не больше, чем байт',
            Overcount, 0);
  TestEqInt('все случайные строки обработаны',
            Accepted + Bad, FuzzRounds);
  TestTrue('часть случайных строк отвергнута', Bad > 0);
  TestTrue('часть случайных строк принята', Accepted > 0);
  TestDiagInt('отвергнуто случайных строк', Bad);
  TestDiagInt('принято случайных строк', Accepted);

  Halt(TestEnd);
end.
