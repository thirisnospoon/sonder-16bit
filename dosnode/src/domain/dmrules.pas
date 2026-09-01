{ ===================================================================
  Домен · элементарные правила.

  Здесь только предикаты: ни ввода-вывода, ни арены, ни решений. Каждая
  функция — чистая, и потому проверяется прямым вызовом тысячами случаев
  в секунду на нативном таргете.

  Разделение с DmDecide намеренное. Правило «ник состоит из строчных букв,
  цифр и подчёркивания» существует само по себе, независимо от того, при
  регистрации оно применяется или где-то ещё. Смешивать его с порядком
  проверок в конкретной команде — значит потерять возможность проверить
  его отдельно.
  =================================================================== }
unit DmRules;

{$MODE TP}

interface

uses
  TcStr, DcdTypes;

{$I dmlimits.inc}

type
  { Исход проверки свободного текста.

    Три разных отказа, а не один Boolean, потому что оболочке они
    соответствуют разным кодам, и слить их значило бы сказать
    пользователю «неверно» там, где на самом деле «слишком длинно». }
  TTextVerdict = (tvOk, tvEmpty, tvTooLong, tvBadEncoding);

{ Свободный текст: непустой, корректный UTF-8, не длиннее MaxChars СИМВОЛОВ.

  Символов, а не байт. Предел приходит из веб-контракта, где maxLength
  считается в кодовых точках: шестьдесят кириллических букв — это шестьдесят
  символов и сто двадцать байт. Сравнение байтовой длины с таким пределом
  означало бы, что клиент подсказал пользователю одно, а ядро решило по
  другому — ровно то расхождение, от которого предостерегает limits.yaml.

  Кодировка проверяется до пустоты и до длины: интерпретировать байты,
  которые ещё не признаны корректными, неправильно. }
function TextCheck(const S: TStr; MaxChars: Word): TTextVerdict;

{ Ник: строчные латинские буквы, цифры и подчёркивание, длина в границах
  контракта. Регистр не приводится: ник «Andrey» неверен, а не
  эквивалентен «andrey». Приведение регистра — работа оболочки при
  проверке занятости, а не ядра.

  Длина здесь честно байтовая: контракт допускает только ASCII, и
  проверка символов всё равно отвергнет любой байт со старшим битом. }
function NickWellFormed(const Nick: TStr): Boolean;

{ Отображаемое имя: непустое, корректное и не длиннее границы. Символы
  произвольные: это человеческое имя, а не идентификатор. }
function DisplayNameCheck(const Name: TStr): TTextVerdict;

{ Ранг роли. Нужен, чтобы сравнивать роли, а не перечислять пары. }
function RoleRank(R: TRole): Integer;

{ Строго старше. Модератор не применяет меры к равному по роли — иначе
  двое модераторов могут заблокировать друг друга. }
function RoleOutranks(Actor, Target: TRole): Boolean;

function RoleAtLeast(Actor, Minimum: TRole): Boolean;

function IsBanned(Status: TUserStatus): Boolean;
function IsGone(Status: TUserStatus): Boolean;

{ Идентификатор непуст и состоит из символов, разрешённых контрактом.
  Пустой идентификатор в состоянии означает, что оболочка прислала не
  всё, а не что объект называется пустой строкой. }
function IdWellFormed(const Id: TStr): Boolean;

implementation

function IsLowerAlnum(C: Char): Boolean;
begin
  IsLowerAlnum := ((C >= 'a') and (C <= 'z')) or
                  ((C >= '0') and (C <= '9')) or
                  (C = '_');
end;

function IsIdChar(C: Char): Boolean;
begin
  IsIdChar := ((C >= 'a') and (C <= 'z')) or
              ((C >= 'A') and (C <= 'Z')) or
              ((C >= '0') and (C <= '9')) or
              (C = '_') or (C = '-');
end;

function NickWellFormed(const Nick: TStr): Boolean;
var
  I: Word;
begin
  NickWellFormed := False;
  if StrIsEmpty(Nick) then
    Exit;
  if (Nick.Len < LIM_NICK_MIN_LEN) or (Nick.Len > LIM_NICK_MAX_LEN) then
    Exit;
  for I := 0 to Nick.Len - 1 do
    if not IsLowerAlnum(StrCharAt(Nick, I)) then
      Exit;
  NickWellFormed := True;
end;

function TextCheck(const S: TStr; MaxChars: Word): TTextVerdict;
var
  N: Word;
begin
  if not StrCharLen(S, N) then
  begin
    TextCheck := tvBadEncoding;
    Exit;
  end;
  if StrIsBlank(S) then
  begin
    { Текст из одних пробельных символов пуст по смыслу: пост из десяти
      пробелов — это пустой пост. }
    TextCheck := tvEmpty;
    Exit;
  end;
  if N > MaxChars then
  begin
    TextCheck := tvTooLong;
    Exit;
  end;
  TextCheck := tvOk;
end;

function DisplayNameCheck(const Name: TStr): TTextVerdict;
begin
  DisplayNameCheck := TextCheck(Name, LIM_DISPLAY_NAME_MAX_LEN);
end;

function RoleRank(R: TRole): Integer;
begin
  case R of
    Role_USER:      RoleRank := 0;
    Role_MODERATOR: RoleRank := 1;
    Role_ADMIN:     RoleRank := 2;
  else
    { Перечисление закрыто контрактом, но значение приходит по линии и
      могло быть испорчено. Неизвестная роль — самая низкая, а не самая
      высокая: ошибка не должна давать прав. }
    RoleRank := -1;
  end;
end;

function RoleOutranks(Actor, Target: TRole): Boolean;
begin
  RoleOutranks := RoleRank(Actor) > RoleRank(Target);
end;

function RoleAtLeast(Actor, Minimum: TRole): Boolean;
begin
  RoleAtLeast := RoleRank(Actor) >= RoleRank(Minimum);
end;

function IsBanned(Status: TUserStatus): Boolean;
begin
  IsBanned := Status = UserStatus_BANNED;
end;

function IsGone(Status: TUserStatus): Boolean;
begin
  IsGone := Status = UserStatus_DELETED;
end;

function IdWellFormed(const Id: TStr): Boolean;
var
  I: Word;
begin
  IdWellFormed := False;
  if StrIsEmpty(Id) or (Id.Len > 40) then
    Exit;
  for I := 0 to Id.Len - 1 do
    if not IsIdChar(StrCharAt(Id, I)) then
      Exit;
  IdWellFormed := True;
end;

end.
