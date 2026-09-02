{ ===================================================================
  Тесты доменного ядра.

  Три слоя проверок, и каждый ловит своё:

    1. Элементарные правила — предикаты из DmRules, по отдельности.

    2. Golden-набор — пары «команда и состояние → решение», по одной на
       каждый код отказа, который умеет возвращать ядро. Если код есть в
       errors.yaml с пометкой decided_by: core, здесь обязан быть случай,
       который его порождает. Иначе код существует только на бумаге.

    3. Структурные инварианты — утверждения, верные для ЛЮБОГО входа,
       проверяемые на случайных данных. Они ловят то, чего я не придумал:
       например, решение, одновременно принятое и с кодом отказа.

  Всё это чистые вызовы: ни транспорта, ни базы, ни эмулятора. Прогон на
  нативном таргете занимает доли секунды, поэтому случайных входов можно
  брать десятки тысяч.
  =================================================================== }
program TstDomn;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, TcTest, DcdTypes, DmRules, DmDecide, Strings;

{$I errcodes.inc}
{$I dmlimits.inc}

const
{$IFDEF CPU16}
  FuzzRounds = 3000;
  TapFile    = 'TSTDOMN.TAP';
{$ELSE}
  FuzzRounds = 50000;
  TapFile    = 'tstdomn.tap';
{$ENDIF}

  PlannedTests = 83;
  Seed0 = 20260903;

var
  Arena: TArena;
  D: TDecision;
  R: TResult;
  Rnd: LongInt;

  { Буферы под строки: TStr смотрит внутрь них, поэтому они обязаны жить
    дольше запросов, которые на них ссылаются. Здесь это глобальные
    переменные — самый простой способ обеспечить нужное время жизни. }
  SActor, STarget, SPost, SNick, SName, SBody, SReason, SComment: string;
  SProbe: string;   { для проб в тестах правил, чтобы не портить заготовки }

  { Буфер под длинные строки. Паскалевская строка не длиннее 255 байт, а
    тело поста по контракту — тысяча символов, то есть до четырёх тысяч
    байт кириллицей.

    Подделывать длину, не подкладывая байтов, больше нельзя: ядро их
    читает, чтобы посчитать символы. Раньше два теста так и делали, и
    это работало ровно до тех пор, пока длина сравнивалась байтовая. }
  BigBuf: array[0..4095] of Char;

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

{ Код отказа из решения как паскалевская строка — для сравнения в тестах. }
function Code(const Dec: TDecision): string;
begin
  Code := StrHead(Dec.errorCode);
end;

{ Строка из Count повторений последовательности Seq, уложенная в BigBuf.

  Именно повторений, а не байт: для кириллицы Seq длиной два байта даёт
  Count символов и вдвое больше байт. На этом различии и держится вся
  проверка — предел контракта задан в символах. }
function Repeated(const Seq: string; Count: Word): TStr;
var
  I, J, Pos: Word;
  R: TStr;
begin
  Pos := 0;
  for I := 1 to Count do
    for J := 1 to Length(Seq) do
    begin
      if Pos >= SizeOf(BigBuf) then
      begin
        { Молча обрезать значило бы получить зелёный тест на строке не той
          длины, какую он объявляет. Лучше упасть. }
        TestDiag('BigBuf мал для этого случая');
        Halt(2);
      end;
      BigBuf[Pos] := Seq[J];
      Inc(Pos);
    end;
  R.Ptr := @BigBuf[0];
  R.Len := Pos;
  Repeated := R;
end;

function EventCount(const Dec: TDecision): Integer;
var
  N: Integer;
  Cur: PDomainEventNode;
begin
  N := 0;
  Cur := Dec.event;
  while Cur <> nil do
  begin
    Inc(N);
    Cur := Cur^.Next;
  end;
  EventCount := N;
end;

function FirstEventType(const Dec: TDecision): string;
begin
  if Dec.event = nil then
    FirstEventType := ''
  else
    FirstEventType := StrHead(Dec.event^.Value.type_);
end;

{ ------------------------------------------------------------------
  Заготовки корректных запросов. Каждый тест портит ровно одно поле:
  так видно, что именно вызвало отказ.
  ------------------------------------------------------------------ }

{ Каждая заготовка восстанавливает свои строки. Без этого тест, испортивший
  строку ради проверки отказа, ломал бы все последующие — ровно это и
  случилось при первом прогоне. }
procedure ResetStrings;
begin
  SActor  := 'u-andrey';
  STarget := 'u-maria';
  SPost   := 'p-1001';
  SNick   := 'andrey';
  SName   := 'Андрей';
  SBody   := 'Первый пост в этой странной системе';
  SReason := 'нарушение правил';
  SComment := 'c-2001';
end;

procedure BaseActor(var Act: TActorContext);
begin
  ResetStrings;
  FillChar(Act, SizeOf(Act), 0);
  Act.userId := StrView(SActor);
  Act.role := Role_USER;
  Act.status := UserStatus_ACTIVE;
  Act.postsLastHour := 0;
  Act.commentsLastHour := 0;
end;

procedure BaseCreatePost(var Req: TCreatePostRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.meta.traceId := StrView(SActor);
  Req.meta.commandId := StrView(SPost);
  Req.command.postId := StrView(SPost);
  Req.command.body := StrView(SBody);
  BaseActor(Req.actor);
end;

procedure BaseRegister(var Req: TRegisterUserRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.command.userId := StrView(SActor);
  Req.command.nick := StrView(SNick);
  Req.command.displayName := StrView(SName);
  Req.nick.taken := False;
end;

procedure BaseDelete(var Req: TDeletePostRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.command.postId := StrView(SPost);
  BaseActor(Req.actor);
  Req.post.exists := True;
  Req.post.postId := StrView(SPost);
  Req.post.authorId := StrView(SActor);
  Req.post.status := PostStatus_VISIBLE;
  Req.post.version := 1;
end;

procedure BaseFollow(var Req: TFollowUserRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.command.targetUserId := StrView(STarget);
  BaseActor(Req.actor);
  Req.target.exists := True;
  Req.target.userId := StrView(STarget);
  Req.target.role := Role_USER;
  Req.target.status := UserStatus_ACTIVE;
  Req.target.version := 1;
  Req.follow.alreadyFollowing := False;
end;

procedure BaseComment(var Req: TCreateCommentRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.meta.traceId := StrView(SActor);
  Req.meta.commandId := StrView(SPost);
  Req.command.commentId := StrView(SComment);
  Req.command.postId := StrView(SPost);
  Req.command.body := StrView(SBody);
  BaseActor(Req.actor);
  Req.post.exists := True;
  Req.post.postId := StrView(SPost);
  Req.post.authorId := StrView(STarget);
  Req.post.status := PostStatus_VISIBLE;
  Req.post.version := 1;
end;

procedure BaseUnfollow(var Req: TUnfollowUserRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.command.targetUserId := StrView(STarget);
  BaseActor(Req.actor);
  Req.target.exists := True;
  Req.target.userId := StrView(STarget);
  Req.target.role := Role_USER;
  Req.target.status := UserStatus_ACTIVE;
  Req.target.version := 1;
  { Заготовка корректна: подписка есть, значит отписка возможна. }
  Req.follow.alreadyFollowing := True;
end;

procedure BaseBan(var Req: TBanUserRequest);
begin
  ResetStrings;
  FillChar(Req, SizeOf(Req), 0);
  Req.command.targetUserId := StrView(STarget);
  Req.command.reason := StrView(SReason);
  BaseActor(Req.actor);
  Req.actor.role := Role_MODERATOR;
  Req.target.exists := True;
  Req.target.userId := StrView(STarget);
  Req.target.role := Role_USER;
  Req.target.status := UserStatus_ACTIVE;
  Req.target.version := 1;
end;

{ ------------------------------------------------------------------ }

{ Какие коды решения golden-набор успел породить. Индексы совпадают с
  ErrDecisionCodes из errcodes.inc. }
var
  Produced: array[1..ERR_DECISION_CODE_COUNT] of Boolean;

procedure NoteProduced(const C: string);
var
  K: Integer;
begin
  for K := 1 to ERR_DECISION_CODE_COUNT do
    { StrPas, а не StrComp с указателем на C[1]: паскалевская строка не
      заканчивается нулём, и StrComp читал бы за её конец. Первая
      редакция так и делала — и половина кодов ложно числилась
      непорождённой. }
    if StrPas(ErrDecisionCodes[K]) = C then
    begin
      Produced[K] := True;
      Exit;
    end;
end;

procedure CheckRejected(const Name: string; const Dec: TDecision;
                        const WantCode: string);
begin
  NoteProduced(Code(Dec));
  TestOk(Name, (not Dec.accepted) and (Code(Dec) = WantCode));
  if Dec.accepted then
    TestDiag('  ожидался отказ ' + WantCode + ', получено принятие')
  else if Code(Dec) <> WantCode then
  begin
    TestDiag('  получен код: ' + Code(Dec));
    TestDiag('  ожидался код: ' + WantCode);
  end;
end;

procedure CheckAccepted(const Name: string; const Dec: TDecision);
begin
  TestOk(Name, Dec.accepted);
  if not Dec.accepted then
    TestDiag('  отказ с кодом: ' + Code(Dec));
end;

var
  CP: TCreatePostRequest;
  CC: TCreateCommentRequest;
  RU: TRegisterUserRequest;
  DP: TDeletePostRequest;
  FU: TFollowUserRequest;
  UF: TUnfollowUserRequest;
  BU: TBanUserRequest;
  I, Bad: LongInt;
  N: Integer;
  Nick: TStr;

begin
  FillChar(Produced, SizeOf(Produced), 0);
  TestBegin(TapFile, PlannedTests);
  TestDiag('доменное ядро');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('случайных входов', FuzzRounds);

  ResetStrings;

  R := ArenaCreate(Arena, 8192, 'decide');
  TestResultOk('арена решений создана', R);

  { ================================================================
    1. Элементарные правила
    ================================================================ }

  SProbe := 'andrey';    Nick := StrView(SProbe);
  TestTrue('корректный ник принимается', NickWellFormed(Nick));
  SProbe := 'an';        Nick := StrView(SProbe);
  TestFalse('слишком короткий ник отвергается', NickWellFormed(Nick));
  SProbe := 'Andrey';    Nick := StrView(SProbe);
  TestFalse('ник с заглавной буквой отвергается', NickWellFormed(Nick));
  SProbe := 'and rey';   Nick := StrView(SProbe);
  TestFalse('ник с пробелом отвергается', NickWellFormed(Nick));
  SProbe := 'and-rey';   Nick := StrView(SProbe);
  TestFalse('ник с дефисом отвергается', NickWellFormed(Nick));
  SProbe := 'and_rey9';  Nick := StrView(SProbe);
  TestTrue('подчёркивание и цифры разрешены', NickWellFormed(Nick));
  SProbe := '';          Nick := StrView(SProbe);
  TestFalse('пустой ник отвергается', NickWellFormed(Nick));

  TestTrue('администратор старше модератора',
           RoleOutranks(Role_ADMIN, Role_MODERATOR));
  TestFalse('модератор не старше модератора',
            RoleOutranks(Role_MODERATOR, Role_MODERATOR));
  TestFalse('пользователь не старше администратора',
            RoleOutranks(Role_USER, Role_ADMIN));
  TestTrue('модератор дотягивает до порога модератора',
           RoleAtLeast(Role_MODERATOR, Role_MODERATOR));
  TestFalse('пользователь не дотягивает до модератора',
            RoleAtLeast(Role_USER, Role_MODERATOR));

  { ================================================================
    2. Golden-набор: по случаю на каждый код отказа ядра
    ================================================================ }

  TestDiag('--- RegisterUser ---');

  BaseRegister(RU);
  R := DecideRegisterUser(Arena, RU, D);
  TestResultOk('инфраструктура не подвела', R);
  CheckAccepted('корректная регистрация принимается', D);
  TestEqInt('регистрация порождает одно событие', EventCount(D), 1);
  TestEqStr('событие названо верно', FirstEventType(D), 'user.registered');

  ArenaReset(Arena);
  BaseRegister(RU);
  SNick := 'Ан';
  RU.command.nick := StrView(SNick);
  R := DecideRegisterUser(Arena, RU, D);
  CheckRejected('кривой ник отвергается', D, 'NICK_FORMAT_INVALID');
  TestEqInt('отказ не порождает событий', EventCount(D), 0);

  ArenaReset(Arena);
  BaseRegister(RU);
  SName := '   ';
  RU.command.displayName := StrView(SName);
  R := DecideRegisterUser(Arena, RU, D);
  CheckRejected('пустое отображаемое имя отвергается', D, 'DISPLAY_NAME_INVALID');

  ArenaReset(Arena);
  BaseRegister(RU);
  RU.nick.taken := True;
  R := DecideRegisterUser(Arena, RU, D);
  CheckRejected('занятый ник отвергается', D, 'NICK_TAKEN');

  TestDiag('--- CreatePost ---');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  R := DecideCreatePost(Arena, CP, D);
  CheckAccepted('корректный пост принимается', D);
  TestEqStr('событие названо верно', FirstEventType(D), 'post.created');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.actor.status := UserStatus_BANNED;
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('заблокированный не создаёт пост', D, 'ACTOR_BANNED');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  SBody := '    ';
  CP.command.body := StrView(SBody);
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('пост из пробелов отвергается', D, 'POST_BODY_EMPTY');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.command.body := Repeated('a', LIM_POST_BODY_MAX_LEN + 1);
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('слишком длинный пост отвергается', D, 'POST_BODY_TOO_LONG');

  { Предел контракта задан в СИМВОЛАХ, а не в байтах. Кириллица занимает
    по два байта, и тысяча таких символов — законный пост длиной две
    тысячи байт. Пока ядро сравнивало байты, оно отвергало его, а веб по
    тому же контракту принимал: клиент подсказывал одно, ядро решало по
    другому. Ровно то расхождение, от которого предостерегает limits.yaml. }
  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.command.body := Repeated('я', LIM_POST_BODY_MAX_LEN);
  R := DecideCreatePost(Arena, CP, D);
  CheckAccepted('тысяча кириллических символов — законный пост', D);

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.command.body := Repeated('я', LIM_POST_BODY_MAX_LEN + 1);
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('символом больше предела — отказ, и именно по длине',
                D, 'POST_BODY_TOO_LONG');

  { Испорченная последовательность не должна выдавать себя за короткий
    текст: посчитать её нельзя, и отказ выносится до проверки длины. }
  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.command.body := Repeated(#$D0, 4);   { ведущий байт без продолжения }
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('испорченный UTF-8 в теле поста отвергается',
                D, 'TEXT_ENCODING_INVALID');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.actor.postsLastHour := LIM_POSTS_PER_HOUR;
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('превышение частоты отвергается', D, 'POST_RATE_EXCEEDED');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.actor.postsLastHour := LIM_POSTS_PER_HOUR - 1;
  R := DecideCreatePost(Arena, CP, D);
  CheckAccepted('ровно на границе частоты ещё можно', D);

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.actor.postsLastHour := -1;
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('несчитанный счётчик — неполное состояние', D,
                'INSUFFICIENT_CONTEXT');

  ArenaReset(Arena);
  BaseCreatePost(CP);
  CP.actor.userId := StrNil;
  R := DecideCreatePost(Arena, CP, D);
  CheckRejected('пустой идентификатор автора — неполное состояние', D,
                'INSUFFICIENT_CONTEXT');

  TestDiag('--- DeletePost ---');

  ArenaReset(Arena);
  BaseDelete(DP);
  R := DecideDeletePost(Arena, DP, D);
  CheckAccepted('автор удаляет свой пост', D);
  TestEqStr('событие названо верно', FirstEventType(D), 'post.deleted');

  ArenaReset(Arena);
  BaseDelete(DP);
  DP.post.exists := False;
  R := DecideDeletePost(Arena, DP, D);
  CheckRejected('несуществующий пост', D, 'POST_NOT_FOUND');

  ArenaReset(Arena);
  BaseDelete(DP);
  DP.post.authorId := StrView(STarget);
  R := DecideDeletePost(Arena, DP, D);
  CheckRejected('чужой пост обычному пользователю нельзя', D, 'NOT_OWNER');

  ArenaReset(Arena);
  BaseDelete(DP);
  DP.post.authorId := StrView(STarget);
  DP.actor.role := Role_MODERATOR;
  R := DecideDeletePost(Arena, DP, D);
  CheckAccepted('модератор удаляет чужой пост', D);

  ArenaReset(Arena);
  BaseDelete(DP);
  DP.post.status := PostStatus_DELETED;
  R := DecideDeletePost(Arena, DP, D);
  CheckAccepted('повторное удаление принимается', D);
  TestEqInt('повторное удаление не порождает событий', EventCount(D), 0);

  ArenaReset(Arena);
  BaseDelete(DP);
  DP.actor.status := UserStatus_BANNED;
  R := DecideDeletePost(Arena, DP, D);
  CheckAccepted('заблокированный вправе удалить свой пост', D);

  TestDiag('--- FollowUser ---');

  ArenaReset(Arena);
  BaseFollow(FU);
  R := DecideFollowUser(Arena, FU, D);
  CheckAccepted('подписка принимается', D);
  TestEqStr('событие названо верно', FirstEventType(D), 'follow.created');

  ArenaReset(Arena);
  BaseFollow(FU);
  FU.target.exists := False;
  R := DecideFollowUser(Arena, FU, D);
  CheckRejected('подписка на несуществующего', D, 'USER_NOT_FOUND');

  ArenaReset(Arena);
  BaseFollow(FU);
  FU.target.status := UserStatus_DELETED;
  R := DecideFollowUser(Arena, FU, D);
  CheckRejected('подписка на удалённого', D, 'USER_NOT_FOUND');

  ArenaReset(Arena);
  BaseFollow(FU);
  FU.target.userId := StrView(SActor);
  R := DecideFollowUser(Arena, FU, D);
  CheckRejected('подписка на себя отвергается', D, 'SELF_FOLLOW');

  ArenaReset(Arena);
  BaseFollow(FU);
  FU.actor.status := UserStatus_BANNED;
  R := DecideFollowUser(Arena, FU, D);
  CheckRejected('заблокированный не подписывается', D, 'ACTOR_BANNED');

  ArenaReset(Arena);
  BaseFollow(FU);
  FU.follow.alreadyFollowing := True;
  R := DecideFollowUser(Arena, FU, D);
  CheckRejected('повторная подписка отвергается', D, 'ALREADY_FOLLOWING');

  TestDiag('--- BanUser ---');

  ArenaReset(Arena);
  BaseBan(BU);
  R := DecideBanUser(Arena, BU, D);
  CheckAccepted('модератор блокирует пользователя', D);
  TestEqStr('событие названо верно', FirstEventType(D), 'user.banned');
  TestEqInt('событие несёт причину и исполнителя', EventCount(D), 1);

  ArenaReset(Arena);
  BaseBan(BU);
  BU.actor.role := Role_USER;
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('обычный пользователь не блокирует', D, 'ROLE_INSUFFICIENT');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.target.exists := False;
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('блокировка несуществующего', D, 'USER_NOT_FOUND');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.target.role := Role_MODERATOR;
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('равного по роли блокировать нельзя', D,
                'CANNOT_MODERATE_PEER');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.target.userId := StrView(SActor);
  BU.target.role := Role_MODERATOR;
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('себя блокировать нельзя', D, 'CANNOT_MODERATE_PEER');

  ArenaReset(Arena);
  BaseBan(BU);
  SReason := '  ';
  BU.command.reason := StrView(SReason);
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('пустая причина отвергается', D, 'BAN_REASON_EMPTY');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.command.reason := Repeated('a', LIM_BAN_REASON_MAX_LEN + 1);
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('слишком длинная причина отвергается', D,
                'BAN_REASON_TOO_LONG');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.target.status := UserStatus_BANNED;
  R := DecideBanUser(Arena, BU, D);
  CheckRejected('повторная блокировка отвергается', D, 'ALREADY_BANNED');

  ArenaReset(Arena);
  BaseBan(BU);
  BU.actor.role := Role_ADMIN;
  BU.target.role := Role_MODERATOR;
  R := DecideBanUser(Arena, BU, D);
  CheckAccepted('администратор блокирует модератора', D);

  { ================================================================
    3. Структурные инварианты на случайных входах

    Эти утверждения обязаны держаться для ЛЮБОГО входа, поэтому
    проверяются на данных, которых я не придумывал. Ловят именно то,
    что упускает golden-набор: сочетания, до которых не додумался.
    ================================================================ }

  TestDiag('--- инварианты на случайных входах ---');

  Rnd := Seed0;
  Bad := 0;
  N := 0;

  for I := 1 to FuzzRounds do
  begin
    ArenaReset(Arena);
    BaseCreatePost(CP);

    CP.actor.role := TRole(RndBelow(3));
    CP.actor.status := TUserStatus(RndBelow(3));
    CP.actor.postsLastHour := RndBelow(40) - 5;
    CP.actor.commentsLastHour := RndBelow(80) - 5;
    CP.command.body.Len := Word(RndBelow(LIM_POST_BODY_MAX_LEN * 2));
    if RndBelow(10) = 0 then
      CP.actor.userId := StrNil;

    R := DecideCreatePost(Arena, CP, D);

    { Инфраструктура не имеет права отказать на этих объёмах. }
    if not R.Ok then Inc(Bad);

    { Принято и отказано одновременно — невозможное состояние. }
    if D.accepted and (Code(D) <> '') then Inc(Bad);

    { Отказ без кода неопознаваем. }
    if (not D.accepted) and (Code(D) = '') then Inc(Bad);

    { Отказ не порождает событий: иначе оболочка запишет в outbox то,
      чего домен не разрешал. }
    if (not D.accepted) and (EventCount(D) > 0) then Inc(Bad);

    { Заблокированный не создаёт содержимого — ни при каких сочетаниях
      остальных полей. }
    if D.accepted and (CP.actor.status = UserStatus_BANNED) then Inc(Bad);

    { Превышение частоты не проходит никогда. }
    if D.accepted and (CP.actor.postsLastHour >= LIM_POSTS_PER_HOUR) then
      Inc(Bad);

    { Принятие всегда порождает ровно одно событие. }
    if D.accepted and (EventCount(D) <> 1) then Inc(Bad);

    if D.accepted then Inc(N);
  end;

  TestDiagInt('принято из случайных', N);
  TestEqInt('инварианты держатся на всех случайных входах', Bad, 0);
  TestTrue('среди случайных были и принятия, и отказы',
           (N > 0) and (N < FuzzRounds));

  { ================================================================
    CreateComment

    Границы и коды для комментария были объявлены в контракте с самого
    начала, а операции не было: три кода существовали только на бумаге.
    Обнаружилось это не глазами — механической сверкой ниже.
    ================================================================ }

  ArenaReset(Arena);
  BaseComment(CC);
  R := DecideCreateComment(Arena, CC, D);
  CheckAccepted('корректный комментарий принимается', D);
  TestEqInt('комментарий порождает одно событие', EventCount(D), 1);
  TestEqStr('тип события комментария', FirstEventType(D), 'comment.created');

  ArenaReset(Arena);
  BaseComment(CC);
  CC.actor.commentsLastHour := -1;
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('неполное состояние автора отвергается',
                D, 'INSUFFICIENT_CONTEXT');

  ArenaReset(Arena);
  BaseComment(CC);
  CC.post.exists := False;
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('комментарий к несуществующему посту отвергается',
                D, 'POST_NOT_FOUND');

  ArenaReset(Arena);
  BaseComment(CC);
  CC.actor.status := UserStatus_BANNED;
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('заблокированный не комментирует', D, 'ACTOR_BANNED');

  ArenaReset(Arena);
  BaseComment(CC);
  SBody := '   ';
  CC.command.body := StrView(SBody);
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('пустой комментарий отвергается', D, 'COMMENT_BODY_EMPTY');

  ArenaReset(Arena);
  BaseComment(CC);
  CC.command.body := Repeated('a', LIM_COMMENT_BODY_MAX_LEN + 1);
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('слишком длинный комментарий отвергается',
                D, 'COMMENT_BODY_TOO_LONG');

  { Предел в символах, а не в байтах — как и у поста. }
  ArenaReset(Arena);
  BaseComment(CC);
  CC.command.body := Repeated('я', LIM_COMMENT_BODY_MAX_LEN);
  R := DecideCreateComment(Arena, CC, D);
  CheckAccepted('пятьсот кириллических символов — законный комментарий', D);

  { Удалённый пост — это конфликт состояния, а не ошибка формы, поэтому
    проверяется после неё. }
  ArenaReset(Arena);
  BaseComment(CC);
  CC.post.status := PostStatus_DELETED;
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('комментарий к удалённому посту отвергается',
                D, 'POST_NOT_FOUND');

  ArenaReset(Arena);
  BaseComment(CC);
  CC.actor.commentsLastHour := LIM_COMMENTS_PER_HOUR;
  R := DecideCreateComment(Arena, CC, D);
  CheckRejected('превышение частоты комментариев отвергается',
                D, 'COMMENT_RATE_EXCEEDED');

  { Инварианты подписки: самоподписка не проходит никогда. }
  Rnd := Seed0 + 1;
  Bad := 0;
  for I := 1 to FuzzRounds do
  begin
    ArenaReset(Arena);
    BaseFollow(FU);
    FU.actor.status := TUserStatus(RndBelow(3));
    FU.target.status := TUserStatus(RndBelow(3));
    FU.target.exists := RndBelow(4) > 0;
    FU.follow.alreadyFollowing := RndBelow(3) = 0;
    if RndBelow(3) = 0 then
      FU.target.userId := StrView(SActor);

    R := DecideFollowUser(Arena, FU, D);
    if not R.Ok then Inc(Bad);
    if D.accepted and StrEq(FU.actor.userId, FU.target.userId) then Inc(Bad);
    if D.accepted and (not FU.target.exists) then Inc(Bad);
    if D.accepted and FU.follow.alreadyFollowing then Inc(Bad);
    if D.accepted and (FU.actor.status = UserStatus_BANNED) then Inc(Bad);
  end;
  TestEqInt('инварианты подписки держатся', Bad, 0);

  { Инварианты блокировки: младший никогда не блокирует старшего. }
  Rnd := Seed0 + 2;
  Bad := 0;
  for I := 1 to FuzzRounds do
  begin
    ArenaReset(Arena);
    BaseBan(BU);
    BU.actor.role := TRole(RndBelow(3));
    BU.target.role := TRole(RndBelow(3));
    BU.target.status := TUserStatus(RndBelow(3));
    BU.target.exists := RndBelow(5) > 0;

    R := DecideBanUser(Arena, BU, D);
    if not R.Ok then Inc(Bad);
    if D.accepted and (not RoleOutranks(BU.actor.role, BU.target.role)) then
      Inc(Bad);
    if D.accepted and (BU.target.status = UserStatus_BANNED) then Inc(Bad);
    if D.accepted and (not BU.target.exists) then Inc(Bad);
  end;
  TestEqInt('инварианты блокировки держатся', Bad, 0);

  TestDiagInt('арена: пик за прогон', Arena.HighMark);
  TestTrue('арена решений не переполнилась', Arena.HighMark < Arena.Capacity);

  { ================================================================
    UnfollowUser

    Операции не было в контракте вовсе, хотя веб объявлял
    DELETE на подписку. Третья дыра такого рода, и найдена
    тем же способом — сверкой объявленного с реализованным.
    ================================================================ }

  ArenaReset(Arena);
  BaseUnfollow(UF);
  R := DecideUnfollowUser(Arena, UF, D);
  CheckAccepted('корректная отписка принимается', D);
  TestEqStr('тип события отписки', FirstEventType(D), 'follow.removed');

  ArenaReset(Arena);
  BaseUnfollow(UF);
  UF.actor.postsLastHour := -1;
  R := DecideUnfollowUser(Arena, UF, D);
  CheckRejected('неполное состояние при отписке отвергается',
                D, 'INSUFFICIENT_CONTEXT');

  ArenaReset(Arena);
  BaseUnfollow(UF);
  UF.target.exists := False;
  R := DecideUnfollowUser(Arena, UF, D);
  CheckRejected('отписка от несуществующего отвергается',
                D, 'USER_NOT_FOUND');

  ArenaReset(Arena);
  BaseUnfollow(UF);
  UF.actor.status := UserStatus_BANNED;
  R := DecideUnfollowUser(Arena, UF, D);
  CheckRejected('заблокированный не отписывается', D, 'ACTOR_BANNED');

  ArenaReset(Arena);
  BaseUnfollow(UF);
  UF.follow.alreadyFollowing := False;
  R := DecideUnfollowUser(Arena, UF, D);
  CheckRejected('отписка без подписки отвергается', D, 'NOT_FOLLOWING');

  { Отписка от себя приходит в NOT_FOLLOWING, а не в SELF_FOLLOW:
    подписаться на себя нельзя, значит подписки на себя не бывает. }
  ArenaReset(Arena);
  BaseUnfollow(UF);
  UF.target.userId := StrView(SActor);
  UF.command.targetUserId := StrView(SActor);
  UF.follow.alreadyFollowing := False;
  R := DecideUnfollowUser(Arena, UF, D);
  CheckRejected('отписка от себя — это «не подписан», а не «нельзя на себя»',
                D, 'NOT_FOLLOWING');

  ArenaDestroy(Arena);
  { ================================================================
    Полнота golden-набора

    Правило из шапки этого файла — «на каждый код ядра здесь обязан быть
    случай» — до сих пор было пожеланием: соблюдать его приходилось
    глазами, и три кода про комментарии тихо выпали. Теперь оно
    проверяется механически по таблице из контракта.

    Инфраструктурные коды ядра (decided_by: core-runtime) в таблицу не
    входят: их порождает рантайм, а не решение, и требовать для них
    golden-случая было бы неверно.
    ================================================================ }

  Bad := 0;
  for I := 1 to ERR_DECISION_CODE_COUNT do
    if not Produced[I] then
    begin
      Inc(Bad);
      TestDiag('  не порождён ни одним случаем: ' +
               StrPas(ErrDecisionCodes[I]));
    end;
  TestEqInt('каждый код решения порождён golden-набором', Bad, 0);
  TestDiagInt('кодов решения в контракте', ERR_DECISION_CODE_COUNT);

  Halt(TestEnd);
end.
