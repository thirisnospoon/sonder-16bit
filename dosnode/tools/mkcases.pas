{ ===================================================================
  Порождение корпуса решений для второго мнения.

  ЗАЧЕМ. Правильность доменных правил подтверждают тесты, написанные
  тем же человеком, что и правила. Ошибка в понимании правила уходит в
  обе стороны разом: и в код, и в проверку — и обе стороны согласны.

  Второе мнение — независимая реализация тех же правил на другом языке
  (dosnode/prolog/createpost.pl). Сравнивать её надо не с текстом
  правил, а с ПОВЕДЕНИЕМ настоящего ядра, поэтому корпус порождается
  здесь: случаи прогоняются через DecideCreatePost, и в файл ложится
  вход вместе с тем, что ядро на самом деле решило.

  Это тот же приём, что у эталонных кадров (mkframes.pas): «обе стороны
  написаны по одному описанию» — не доказательство.

  ФОРМАТ. Строки TSV, поля:

      номер, идентификатор пользователя (hex), статус,
      постов за час, комментариев за час, идентификатор поста (hex),
      тело (hex), принято, код отказа

  Строки в hex, потому что среди случаев есть НЕВЕРНЫЙ UTF-8: корпус,
  который нельзя записать в текстовом файле как есть, — ровно тот, ради
  которого всё затевается.

  НАБОР СЛУЧАЕВ подобран по границам правил и по капканам UTF-8, а
  хвост добит псевдослучайными строками из алфавита, где рядом стоят
  ведущие байты, продолжающие байты и латиница: расхождения двух
  реализаций живут именно на таких.
  =================================================================== }
program MkCases;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, DcdTypes, DmRules, DmDecide, Strings;

{$I errcodes.inc}
{$I dmlimits.inc}

const
  OutName = 'createpost.tsv';
  MaxBody = 4200;
  RandomCases = 600;
  Seed0 = 20260904;

var
  F: Text;
  Arena: TArena;
  Total: LongInt;
  Seed: LongInt;
  RC: TResult;
  BodyBuf: array[0..MaxBody] of Char;
  IdBuf: array[0..63] of Char;
  UserBuf: array[0..63] of Char;

{ Псевдослучайное. Тот же линейный конгруэнтный, что в тестах ядра:
  корпус обязан быть одинаковым при каждом прогоне, иначе проверка
  дрейфа краснела бы на каждой сборке. }
function NextRand(Limit: Word): Word;
begin
  Seed := (Seed * 1103515245 + 12345) and $7FFFFFFF;
  NextRand := (Seed shr 8) mod Limit;
end;

procedure WriteHex(P: PChar; Len: Word);
const
  { Строкой, а не массивом символов: инициализация массива литералом в
    режиме TP разбирается не так, как ожидает глаз. }
  Digits: string[16] = '0123456789abcdef';
var
  I: Word;
  B: Byte;
begin
  if Len = 0 then
  begin
    { Пустое поле неотличимо от пропущенного, а разница тут есть. }
    Write(F, '-');
    Exit;
  end;
  for I := 0 to Len - 1 do
  begin
    B := Byte(P[I]);
    Write(F, Digits[(B shr 4) + 1], Digits[(B and $0F) + 1]);
  end;
end;

{ Один случай: прогнать через настоящее решение и записать вход с
  исходом. }
procedure Emit(UserLen: Word; Status: TUserStatus;
               Posts, Comments: LongInt; IdLen, BodyLen: Word);
var
  Req: TCreatePostRequest;
  D: TDecision;
  R: TResult;
begin
  ArenaReset(Arena);
  FillChar(Req, SizeOf(Req), 0);

  Req.actor.userId.Ptr := @UserBuf[0];
  Req.actor.userId.Len := UserLen;
  Req.actor.role := Role_USER;
  Req.actor.status := Status;
  Req.actor.postsLastHour := Posts;
  Req.actor.commentsLastHour := Comments;

  Req.command.postId.Ptr := @IdBuf[0];
  Req.command.postId.Len := IdLen;
  Req.command.body.Ptr := @BodyBuf[0];
  Req.command.body.Len := BodyLen;

  R := DecideCreatePost(Arena, Req, D);
  if not R.Ok then
  begin
    { Отказ арены — не решение домена, и в корпус ему нельзя: вторая
      реализация о памяти ничего не знает и знать не должна. }
    WriteLn('АРЕНА ОТКАЗАЛА на случае ', Total);
    Halt(1);
  end;

  Inc(Total);
  Write(F, Total, #9);
  WriteHex(@UserBuf[0], UserLen);
  Write(F, #9, StrPas(UserStatusNames[Status]), #9, Posts, #9, Comments, #9);
  WriteHex(@IdBuf[0], IdLen);
  Write(F, #9);
  WriteHex(@BodyBuf[0], BodyLen);
  if D.accepted then
    Write(F, #9, 'accepted', #9, '-')
  else
  begin
    Write(F, #9, 'rejected', #9);
    if StrIsEmpty(D.errorCode) then
      Write(F, '-')
    else
      Write(F, StrHead(D.errorCode));
  end;
  WriteLn(F);
end;

procedure SetUser(const S: string);
var
  I: Word;
begin
  for I := 1 to Length(S) do
    UserBuf[I - 1] := S[I];
end;

procedure SetId(const S: string);
var
  I: Word;
begin
  for I := 1 to Length(S) do
    IdBuf[I - 1] := S[I];
end;

procedure SetBody(const S: string);
var
  I: Word;
begin
  for I := 1 to Length(S) do
    BodyBuf[I - 1] := S[I];
end;

{ Тело из повторяющегося куска: так набираются длины около предела,
  которых в короткую строку Паскаля не записать. }
procedure FillBody(const Piece: string; Times: Word; var Len: Word);
var
  I, J: Word;
begin
  Len := 0;
  for I := 1 to Times do
    for J := 1 to Length(Piece) do
    begin
      if Len >= MaxBody then
        Exit;
      BodyBuf[Len] := Piece[J];
      Inc(Len);
    end;
end;

{ --- Границы правил ------------------------------------------------ }

procedure BoundaryCases;
var
  Len: Word;
begin
  SetUser('u-andrey');
  SetId('p-1001');

  { Исправный случай — он же проверка, что корпус вообще осмыслен. }
  SetBody('привет');
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 12);

  { Пустой идентификатор пользователя и слишком длинный. }
  Emit(0, UserStatus_ACTIVE, 0, 0, 6, 12);
  SetUser('0123456789012345678901234567890123456789x');
  Emit(41, UserStatus_ACTIVE, 0, 0, 6, 12);
  SetUser('u andrey');
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 12);
  SetUser('u-andrey');

  { Отрицательные счётчики: оболочка не считала. }
  Emit(8, UserStatus_ACTIVE, -1, 0, 6, 12);
  Emit(8, UserStatus_ACTIVE, 0, -1, 6, 12);

  { Идентификатор поста. }
  Emit(8, UserStatus_ACTIVE, 0, 0, 0, 12);
  SetId('p 1001');
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 12);
  SetId('p-1001');

  { Статус важнее формы текста: заблокированному незачем сообщать, что
    его текст к тому же пуст. }
  SetBody('   ');
  Emit(8, UserStatus_BANNED, 0, 0, 6, 3);
  Emit(8, UserStatus_DELETED, 0, 0, 6, 3);

  { Пустое и пробельное тело. }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 0);
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 3);
  SetBody(#9#10#13' ');
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);

  { Предел длины в ЗНАКАХ, а не в байтах: кириллица по два байта. }
  FillBody('я', LIM_POST_BODY_MAX_LEN, Len);
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, Len);
  FillBody('я', LIM_POST_BODY_MAX_LEN + 1, Len);
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, Len);
  FillBody('a', LIM_POST_BODY_MAX_LEN, Len);
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, Len);
  FillBody('a', LIM_POST_BODY_MAX_LEN + 1, Len);
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, Len);

  { Предел частоты. }
  SetBody('норма');
  Emit(8, UserStatus_ACTIVE, LIM_POSTS_PER_HOUR - 1, 0, 6, 10);
  Emit(8, UserStatus_ACTIVE, LIM_POSTS_PER_HOUR, 0, 6, 10);
  Emit(8, UserStatus_ACTIVE, LIM_POSTS_PER_HOUR + 1, 0, 6, 10);

  { --- Капканы UTF-8 ------------------------------------------------
    Каждый случай — отдельный вид неверной записи, и каждый из них
    наивная реализация принимает за верный. }

  BodyBuf[0] := #$80;                                   { продолжающий первым }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 1);
  BodyBuf[0] := #$BF;
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 1);

  BodyBuf[0] := #$C0; BodyBuf[1] := #$AF;               { избыточная запись }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 2);
  BodyBuf[0] := #$C1; BodyBuf[1] := #$BF;
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 2);

  BodyBuf[0] := #$E0; BodyBuf[1] := #$9F; BodyBuf[2] := #$BF;  { короткая тройка }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 3);
  BodyBuf[0] := #$E0; BodyBuf[1] := #$A0; BodyBuf[2] := #$80;  { она же верная }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 3);

  BodyBuf[0] := #$ED; BodyBuf[1] := #$A0; BodyBuf[2] := #$80;  { суррогат }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 3);
  BodyBuf[0] := #$ED; BodyBuf[1] := #$9F; BodyBuf[2] := #$BF;  { перед ним }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 3);

  BodyBuf[0] := #$F0; BodyBuf[1] := #$8F; BodyBuf[2] := #$BF;
  BodyBuf[3] := #$BF;                                          { короткая четвёрка }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);
  BodyBuf[0] := #$F0; BodyBuf[1] := #$90; BodyBuf[2] := #$80;
  BodyBuf[3] := #$80;                                          { она же верная }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);

  BodyBuf[0] := #$F4; BodyBuf[1] := #$8F; BodyBuf[2] := #$BF;
  BodyBuf[3] := #$BF;                                          { последний знак }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);
  BodyBuf[0] := #$F4; BodyBuf[1] := #$90; BodyBuf[2] := #$80;
  BodyBuf[3] := #$80;                                          { за пределом }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);

  BodyBuf[0] := #$F5; BodyBuf[1] := #$80; BodyBuf[2] := #$80;
  BodyBuf[3] := #$80;
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);

  { Оборванный хвост: последовательность обещает продолжение, а строка
    кончается. }
  BodyBuf[0] := #$D0;
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 1);
  BodyBuf[0] := #$E4; BodyBuf[1] := #$B8;
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 2);
  BodyBuf[0] := #$D0; BodyBuf[1] := #$41;   { продолжение не продолжающее }
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 2);

  { Верный многобайтовый рядом с латиницей. }
  SetBody('a'#$D0#$B0'b');
  Emit(8, UserStatus_ACTIVE, 0, 0, 6, 4);
end;

{ --- Псевдослучайный хвост ----------------------------------------- }

procedure RandomCasesRun;
const
  { Алфавит нарочно злой: латиница, пробелы, ведущие и продолжающие
    байты вперемешку. Случайная строка из него верна по UTF-8 примерно
    в половине случаев — то есть обе ветки нагружены. }
  Alphabet: array[0..15] of Byte =
    ($61, $20, $09, $D0, $B0, $80, $BF, $C2,
     $E0, $A0, $ED, $F0, $90, $F4, $F5, $7A);
var
  N, I: Word;
  Len: Word;
  Status: TUserStatus;
  Posts: LongInt;
begin
  for N := 1 to RandomCases do
  begin
    Len := NextRand(24);
    { Проверка на ноль обязательна: Len типа Word, и `Len - 1` при нуле
      даёт 65535, а не минус единицу. Цикл затирает всё подряд и роняет
      программу — ровно этот капкан описан в StrCharLen, и он же поймал
      здесь на 59-м случае. }
    if Len > 0 then
      for I := 0 to Len - 1 do
        BodyBuf[I] := Char(Alphabet[NextRand(16)]);

    case NextRand(6) of
      0: Status := UserStatus_BANNED;
      1: Status := UserStatus_DELETED;
    else
      Status := UserStatus_ACTIVE;
    end;

    case NextRand(4) of
      0: Posts := LIM_POSTS_PER_HOUR;
      1: Posts := -1;
    else
      Posts := NextRand(LIM_POSTS_PER_HOUR);
    end;

    SetUser('u-andrey');
    SetId('p-1001');
    Emit(8, Status, Posts, 0, 6, Len);
  end;
end;

begin
  WriteLn('mkcases: начали');
  Total := 0;
  Seed := Seed0;

  RC := ArenaCreate(Arena, 8192, 'cases');
  if not RC.Ok then
  begin
    WriteLn('арена не создалась');
    Halt(1);
  end;

  Assign(F, OutName);
  Rewrite(F);
  WriteLn(F, '# порождено настоящим DecideCreatePost; правится не здесь');
  WriteLn(F, '# id', #9, 'userIdHex', #9, 'status', #9, 'postsLastHour', #9,
             'commentsLastHour', #9, 'postIdHex', #9, 'bodyHex', #9,
             'verdict', #9, 'errorCode');
  WriteLn(F, '# limits', #9, LIM_POST_BODY_MAX_LEN, #9, LIM_POSTS_PER_HOUR);

  WriteLn('mkcases: границы');
  BoundaryCases;
  WriteLn('mkcases: случайные');
  RandomCasesRun;

  Close(F);
  ArenaDestroy(Arena);
  WriteLn('случаев записано: ', Total);
  WriteLn('файл: ', OutName);
end.
