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
  RegName = 'registeruser.tsv';
  MaxBody = 4200;
  RandomCases = 600;
  Seed0 = 20260904;

var
  F: Text;
  Arena: TArena;
  NickBuf: array[0..127] of Char;
  NameBuf: array[0..511] of Char;
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

{ ===================================================================
  Регистрация: правила другого рода.

  У ника свой набор знаков, строже идентификатора; пустое и длинное
  отображаемое имя дают ОДИН код, а неверная кодировка — отдельный.
  =================================================================== }

procedure SetNick(const S: string);
var
  I: Word;
begin
  for I := 1 to Length(S) do
    NickBuf[I - 1] := S[I];
end;

procedure SetName(const S: string);
var
  I: Word;
begin
  for I := 1 to Length(S) do
    NameBuf[I - 1] := S[I];
end;

procedure FillName(const Piece: string; Times: Word; var Len: Word);
var
  I, J: Word;
begin
  Len := 0;
  for I := 1 to Times do
    for J := 1 to Length(Piece) do
    begin
      if Len >= 511 then
        Exit;
      NameBuf[Len] := Piece[J];
      Inc(Len);
    end;
end;

procedure EmitReg(IdLen, NickLen, NameLen: Word; Taken: Boolean);
var
  Req: TRegisterUserRequest;
  D: TDecision;
  R: TResult;
begin
  ArenaReset(Arena);
  FillChar(Req, SizeOf(Req), 0);

  Req.command.userId.Ptr := @IdBuf[0];
  Req.command.userId.Len := IdLen;
  Req.command.nick.Ptr := @NickBuf[0];
  Req.command.nick.Len := NickLen;
  Req.command.displayName.Ptr := @NameBuf[0];
  Req.command.displayName.Len := NameLen;
  Req.nick.taken := Taken;

  R := DecideRegisterUser(Arena, Req, D);
  if not R.Ok then
  begin
    WriteLn('АРЕНА ОТКАЗАЛА на регистрации ', Total);
    Halt(1);
  end;

  Inc(Total);
  Write(F, Total, #9);
  WriteHex(@IdBuf[0], IdLen);
  Write(F, #9);
  WriteHex(@NickBuf[0], NickLen);
  Write(F, #9);
  WriteHex(@NameBuf[0], NameLen);
  if Taken then
    Write(F, #9, 'yes')
  else
    Write(F, #9, 'no');
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

procedure RegisterCases;
var
  Len: Word;
begin
  SetId('u-andrey');
  SetNick('andrey');
  SetName('Андрей');

  { Исправный случай. }
  EmitReg(8, 6, 12, False);
  EmitReg(8, 6, 12, True);

  { Идентификатор. }
  EmitReg(0, 6, 12, False);
  SetId('u andrey');
  EmitReg(8, 6, 12, False);
  SetId('u-andrey');

  { Ник: границы длины и набор знаков. }
  EmitReg(8, 0, 12, False);
  SetNick('ab');
  EmitReg(8, LIM_NICK_MIN_LEN - 1, 12, False);
  SetNick('abc');
  EmitReg(8, LIM_NICK_MIN_LEN, 12, False);
  SetNick('abcdefghijklmnopqrst');
  EmitReg(8, LIM_NICK_MAX_LEN, 12, False);
  SetNick('abcdefghijklmnopqrstu');
  EmitReg(8, LIM_NICK_MAX_LEN + 1, 12, False);
  SetNick('Andrey');
  EmitReg(8, 6, 12, False);
  SetNick('u-andrey');
  EmitReg(8, 8, 12, False);
  SetNick('andrey_1');
  EmitReg(8, 8, 12, False);
  SetNick('андрей');
  EmitReg(8, 12, 12, False);
  SetNick('andrey');

  { Отображаемое имя: пустое, пробельное, на границе и за ней. }
  EmitReg(8, 6, 0, False);
  SetName('   ');
  EmitReg(8, 6, 3, False);
  FillName('я', LIM_DISPLAY_NAME_MAX_LEN, Len);
  EmitReg(8, 6, Len, False);
  FillName('я', LIM_DISPLAY_NAME_MAX_LEN + 1, Len);
  EmitReg(8, 6, Len, False);
  FillName('a', LIM_DISPLAY_NAME_MAX_LEN + 1, Len);
  EmitReg(8, 6, Len, False);

  { Неверная кодировка в имени — отдельный код, не DISPLAY_NAME_INVALID. }
  NameBuf[0] := #$C0; NameBuf[1] := #$AF;
  EmitReg(8, 6, 2, False);
  NameBuf[0] := #$ED; NameBuf[1] := #$A0; NameBuf[2] := #$80;
  EmitReg(8, 6, 3, False);
  NameBuf[0] := #$D0;
  EmitReg(8, 6, 1, False);

  { Приоритет: плохой ник побеждает плохое имя, а плохое имя — занятость. }
  SetNick('AB');
  NameBuf[0] := #$C0; NameBuf[1] := #$AF;
  EmitReg(8, 2, 2, True);
  SetNick('andrey');
  EmitReg(8, 6, 2, True);
  SetName('Андрей');
  EmitReg(8, 6, 12, True);
end;

procedure RegisterRandom;
const
  NickAlpha: array[0..11] of Byte =
    ($61, $7A, $30, $5F, $2D, $41, $20, $D0, $B0, $39, $6D, $C3);
  NameAlpha: array[0..11] of Byte =
    ($61, $20, $D0, $B0, $80, $C2, $ED, $A0, $F0, $90, $09, $7A);
  { Только то, что законно в нике: нижний регистр, цифры, подчёркивание. }
  GoodNick: array[0..7] of Byte =
    ($61, $62, $7A, $30, $39, $5F, $6D, $71);
var
  N, I: Word;
  NickLen, NameLen: Word;
  Taken: Boolean;
begin
  for N := 1 to RandomCases do
  begin
    { Половина ников берётся из ЗАКОННОГО набора, половина из злого.
      Без этого корпус вырождается: случайная строка из злого алфавита
      почти всегда негодный ник, и остальные ветки — имя, занятость,
      согласие — остаются непройденными. Замерено: 598 отказов по нику
      из 624 случаев. }
    if NextRand(2) = 0 then
    begin
      NickLen := LIM_NICK_MIN_LEN + NextRand(LIM_NICK_MAX_LEN
                                             - LIM_NICK_MIN_LEN + 1);
      for I := 0 to NickLen - 1 do
        NickBuf[I] := Char(GoodNick[NextRand(8)]);
    end
    else
    begin
      NickLen := NextRand(24);
      if NickLen > 0 then
        for I := 0 to NickLen - 1 do
          NickBuf[I] := Char(NickAlpha[NextRand(12)]);
    end;

    if NextRand(2) = 0 then
    begin
      NameLen := 1 + NextRand(12);
      for I := 0 to NameLen - 1 do
        NameBuf[I] := Char(GoodNick[NextRand(8)]);
    end
    else
    begin
      NameLen := NextRand(20);
      if NameLen > 0 then
        for I := 0 to NameLen - 1 do
          NameBuf[I] := Char(NameAlpha[NextRand(12)]);
    end;

    Taken := NextRand(4) = 0;
    SetId('u-andrey');
    EmitReg(8, NickLen, NameLen, Taken);
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
  WriteLn('createpost: ', Total);

  Total := 0;
  Seed := Seed0;
  Assign(F, RegName);
  Rewrite(F);
  WriteLn(F, '# порождено настоящим DecideRegisterUser; правится не здесь');
  WriteLn(F, '# id', #9, 'userIdHex', #9, 'nickHex', #9, 'displayNameHex', #9,
             'nickTaken', #9, 'verdict', #9, 'errorCode');
  WriteLn(F, '# limits', #9, LIM_NICK_MIN_LEN, #9, LIM_NICK_MAX_LEN, #9,
             LIM_DISPLAY_NAME_MAX_LEN);
  RegisterCases;
  RegisterRandom;
  Close(F);
  WriteLn('registeruser: ', Total);

  ArenaDestroy(Arena);
  WriteLn('файлы: ', OutName, ' ', RegName);
end.
