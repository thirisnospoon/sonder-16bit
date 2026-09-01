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

end.
