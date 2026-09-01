{ ===================================================================
  TurboCore · строка произвольной длины.

  Тип string в диалекте TP7 не длиннее 255 байт, а тело поста по контракту
  может быть до тысячи. Поэтому строка здесь — пара «указатель и длина»,
  без завершающего нуля и без счётчика ссылок.

  ВЛАДЕНИЕ. TStr ничем не владеет. Он указывает на память, которой владеет
  кто-то другой: чаще всего арена команды, иногда буфер разбора. Из этого
  следует главное правило:

      TStr не имеет права пережить то, на что указывает.

  Отсюда же запрет из docs/TURBOCORE.md §13: указатель на локальную
  переменную не покидает кадр. В модели памяти large локальные переменные
  живут в чужом сегменте, и такой указатель после возврата смотрит в
  произвольные данные (ADR-0010).

  Все операции сравнения работают побайтно и не знают о кодировках.
  Регистронезависимое сравнение сделано только для ASCII: ники по контракту
  ASCII, а к произвольному тексту оно и не применяется.
  =================================================================== }
unit TcStr;

{$MODE TP}

interface

type
  TStr = record
    Ptr: PChar;
    Len: Word;
  end;

{ Пустая строка. Ptr = nil, Len = 0. }
function StrNil: TStr;

{ Вид на паскалевскую строку без копирования.

  ОПАСНО и потому названо явно: результат живёт ровно столько, сколько
  живёт S. Годится для литералов в тестах и для локальных переменных,
  не покидающих кадр. Для всего остального есть ArenaDupStr. }
function StrView(const S: string): TStr;

{ Копирование в арену живёт в TcArena: строка ничего не знает о том,
  кто владеет памятью, и не должна знать. }

function StrIsEmpty(const S: TStr): Boolean;

{ Строка из одних пробельных символов считается пустой по смыслу:
  пост из десяти пробелов — это пустой пост. }
function StrIsBlank(const S: TStr): Boolean;

function StrCharAt(const S: TStr; Index: Word): Char;

function StrEq(const A, B: TStr): Boolean;
function StrEqPas(const A: TStr; const B: string): Boolean;

{ Первые 255 байт как паскалевская строка. Только для логов и сообщений:
  всё, что длиннее, обрезается молча, и полагаться на это нельзя. }
function StrHead(const S: TStr): string;

{ Длина в символах Unicode, а не в байтах.

  Нужна потому, что maxLength в веб-контракте считается в символах: шесть
  десятков кириллических букв — это шестьдесят символов и сто двадцать байт.
  Сравнение байтовой длины с таким пределом означало бы, что клиент подсказал
  пользователю одно, а ядро решило по другому.

  Проверка и счёт неразделимы: посчитать испорченную последовательность
  нельзя, а два прохода по одним и тем же байтам на 4.77 МГц — расточительство.
  Отсюда Boolean в результате и счёт через var, как у AlignUp в TcArena.
  False означает, что N не определено.

  Отвергаются и избыточные формы, и суррогаты (RFC 3629). Избыточная запись
  даёт две записи одной кодовой точке, а суррогатов в UTF-8 не существует
  вовсе — принимать их значило бы пропускать в домен строки, которые
  Java-сторона считает невозможными. }
function StrCharLen(const S: TStr; var N: Word): Boolean;

{ Целое из строки, пришедшей по линии.

  Строгий разбор: необязательный знак, дальше только цифры, и хотя бы
  одна. Ни пробелов, ни хвоста, ни пустой строки. Снисходительность тут
  дорого стоит — «12abc», разобранное как 12, даёт правдоподобное
  неверное число, а не отказ.

  Переполнение проверяется ДО умножения, а не после: после него проверять
  уже нечего. False означает, что V не определено. }
function StrToInt64(const S: TStr; var V: Int64): Boolean;

implementation

function StrNil: TStr;
var
  R: TStr;
begin
  R.Ptr := nil;
  R.Len := 0;
  StrNil := R;
end;

function StrView(const S: string): TStr;
var
  R: TStr;
begin
  if Length(S) = 0 then
  begin
    StrView := StrNil;
    Exit;
  end;
  R.Ptr := @S[1];
  R.Len := Length(S);
  StrView := R;
end;

function StrIsEmpty(const S: TStr): Boolean;
begin
  StrIsEmpty := (S.Len = 0) or (S.Ptr = nil);
end;

function StrCharAt(const S: TStr; Index: Word): Char;
var
  P: PChar;
begin
  if (S.Ptr = nil) or (Index >= S.Len) then
  begin
    StrCharAt := #0;
    Exit;
  end;
  P := S.Ptr;
  Inc(P, Index);
  StrCharAt := P^;
end;

function StrIsBlank(const S: TStr): Boolean;
var
  I: Word;
  C: Char;
begin
  if StrIsEmpty(S) then
  begin
    StrIsBlank := True;
    Exit;
  end;
  for I := 0 to S.Len - 1 do
  begin
    C := StrCharAt(S, I);
    if (C <> ' ') and (C <> #9) and (C <> #10) and (C <> #13) then
    begin
      StrIsBlank := False;
      Exit;
    end;
  end;
  StrIsBlank := True;
end;

function StrEq(const A, B: TStr): Boolean;
var
  I: Word;
begin
  if A.Len <> B.Len then
  begin
    StrEq := False;
    Exit;
  end;
  if A.Len = 0 then
  begin
    StrEq := True;
    Exit;
  end;
  for I := 0 to A.Len - 1 do
    if StrCharAt(A, I) <> StrCharAt(B, I) then
    begin
      StrEq := False;
      Exit;
    end;
  StrEq := True;
end;

function StrEqPas(const A: TStr; const B: string): Boolean;
var
  I: Word;
begin
  if A.Len <> Word(Length(B)) then
  begin
    StrEqPas := False;
    Exit;
  end;
  if A.Len = 0 then
  begin
    StrEqPas := True;
    Exit;
  end;
  for I := 0 to A.Len - 1 do
    if StrCharAt(A, I) <> B[I + 1] then
    begin
      StrEqPas := False;
      Exit;
    end;
  StrEqPas := True;
end;

function StrHead(const S: TStr): string;
var
  R: string;
  I, N: Word;
begin
  R := '';
  if StrIsEmpty(S) then
  begin
    StrHead := R;
    Exit;
  end;
  N := S.Len;
  if N > 255 then
    N := 255;
  for I := 0 to N - 1 do
    R := R + StrCharAt(S, I);
  StrHead := R;
end;

function StrCharLen(const S: TStr; var N: Word): Boolean;
var
  I, K, Cnt: Word;
  Need, J: Byte;
  B, B2: Byte;
begin
  StrCharLen := False;
  N := 0;

  if S.Len = 0 then
  begin
    { Пустая строка корректна и состоит из нуля символов. Ptr при этом
      может быть каким угодно: читать по нему всё равно нечего. }
    StrCharLen := True;
    Exit;
  end;
  if S.Ptr = nil then
    Exit;

  I := 0;
  Cnt := 0;
  while I < S.Len do
  begin
    B := Byte(S.Ptr[I]);

    if B < $80 then
      Need := 0
    else if B < $C2 then
      { $80..$BF — продолжающий байт на месте ведущего.
        $C0 и $C1 — только избыточные записи ASCII. }
      Exit
    else if B < $E0 then
      Need := 1
    else if B < $F0 then
      Need := 2
    else if B < $F5 then
      Need := 3
    else
      { $F5..$FF вывели бы за U+10FFFF. }
      Exit;

    { Сравнение записано так, а не как I + Need >= S.Len, потому что сумма
      переполнила бы Word при длине под предел. Ровно на таком переполнении
      уже сломался AlignUp, и 16-битный таргет проглотил это молча. }
    if Need > S.Len - 1 - I then
      Exit;

    if Need > 0 then
    begin
      { Смещение считается отдельным курсором типа Word, а не выражением
        I + J: на шестнадцати битах сумма Word и Byte считалась бы в знаковом
        типе и завернулась бы за 32767. Строка по контракту столько не
        наберёт, но скрытого предела в примитиве фреймворка быть не должно. }
      K := I;
      Inc(K);
      B2 := Byte(S.Ptr[K]);
      if (B2 < $80) or (B2 > $BF) then
        Exit;

      { Избыточные формы и суррогаты видны по второму байту. }
      if (B = $E0) and (B2 < $A0) then Exit;   { трёхбайтовая запись короткой }
      if (B = $ED) and (B2 > $9F) then Exit;   { D800..DFFF — суррогаты }
      if (B = $F0) and (B2 < $90) then Exit;   { четырёхбайтовая запись короткой }
      if (B = $F4) and (B2 > $8F) then Exit;   { за U+10FFFF }

      for J := 2 to Need do
      begin
        Inc(K);
        B2 := Byte(S.Ptr[K]);
        if (B2 < $80) or (B2 > $BF) then
          Exit;
      end;
    end;

    Inc(I, Need + 1);
    Inc(Cnt);
  end;

  N := Cnt;
  StrCharLen := True;
end;

function StrToInt64(const S: TStr; var V: Int64): Boolean;
const
  { Накопление идёт В ОТРИЦАТЕЛЬНОМ диапазоне, а не в положительном.

    Причина в асимметрии дополнительного кода: минимум Int64 по модулю
    на единицу больше максимума. Накопив -9223372036854775808 как
    положительное число, его негде подержать — переполнение происходит
    ДО того, как дело дойдёт до смены знака. Первая редакция так и
    делала и падала с ошибкой 215 ровно на нижней границе. }
  NegLimit = Int64(-922337203685477580);
  LastNegDigit = 8;
var
  I: Word;
  Neg: Boolean;
  Acc: Int64;
  D: Byte;
  C: Char;
begin
  StrToInt64 := False;
  V := 0;

  if (S.Len = 0) or (S.Ptr = nil) then
    Exit;

  I := 0;
  Neg := False;
  if (S.Ptr[0] = '-') or (S.Ptr[0] = '+') then
  begin
    Neg := S.Ptr[0] = '-';
    I := 1;
    if I >= S.Len then
      Exit;   { один знак без цифр }
  end;

  Acc := 0;
  while I < S.Len do
  begin
    C := S.Ptr[I];
    if (C < '0') or (C > '9') then
      Exit;
    D := Ord(C) - Ord('0');

    { Проверка до умножения. После него переполнение уже произошло, и
      судить о нём по знаку результата — гадание. }
    if Acc < NegLimit then
      Exit;
    if (Acc = NegLimit) and (D > LastNegDigit) then
      Exit;

    Acc := Acc * 10 - D;
    Inc(I);
  end;

  if Neg then
    V := Acc
  else
  begin
    { Положительного двойника у минимума нет. }
    if Acc = Low(Int64) then
      Exit;
    V := -Acc;
  end;
  StrToInt64 := True;
end;

end.
