{ ===================================================================
  Домен · принятие решений.

  Здесь живёт вся бизнес-логика системы. Каждая функция — чистая: она
  получает команду вместе с состоянием и возвращает решение, не обращаясь
  никуда наружу. Ввода-вывода нет не по стилю, а потому что под DOS его
  сделать нечем (ADR-0011).

  ДВА РОДА ОТКАЗА, и они намеренно разделены.

    TResult   — инфраструктурный исход. Не хватило арены под события,
                состояние пришло неполным. Это дефект системы.
    TDecision — доменное решение. Принято с перечнем событий либо отказ
                с кодом из errors.yaml. Отказ здесь — нормальный
                результат работы, а не исключительная ситуация.

  ПОРЯДОК ПРОВЕРОК одинаков во всех командах и выбран не случайно:

    1. полнота состояния  — иначе решаем по неполным данным (R5);
    2. существование      — нечего проверять у несуществующего объекта;
    3. права              — не сообщаем деталей тому, кому нельзя;
    4. форма команды      — валидация только для тех, кому можно;
    5. состояние объекта  — конфликты и идемпотентность;
    6. ограничения частоты — последними, они самые дорогие по смыслу.

  Порядок влияет на то, какой код увидит пользователь, когда нарушены
  сразу два правила. Менять его — изменение поведения, а не рефакторинг.
  =================================================================== }
unit DmDecide;

{$MODE TP}

interface

uses
  TcResult, TcStr, TcArena, DcdTypes, DmRules;

{$I errcodes.inc}
{$I dmlimits.inc}

function DecideRegisterUser(var A: TArena; const Req: TRegisterUserRequest;
                            var D: TDecision): TResult;

function DecideCreatePost(var A: TArena; const Req: TCreatePostRequest;
                          var D: TDecision): TResult;

function DecideDeletePost(var A: TArena; const Req: TDeletePostRequest;
                          var D: TDecision): TResult;

function DecideFollowUser(var A: TArena; const Req: TFollowUserRequest;
                          var D: TDecision): TResult;

function DecideBanUser(var A: TArena; const Req: TBanUserRequest;
                       var D: TDecision): TResult;

{ Доступно тестам: они проверяют сборку событий отдельно от правил. }
function EmitEvent(var A: TArena; var D: TDecision;
                   const EvType: string; const AggId: TStr;
                   var Node: PDomainEventNode): TResult;

function EmitField(var A: TArena; Node: PDomainEventNode;
                   const Key: string; const Value: TStr): TResult;

implementation

{ ------------------------------------------------------------------
  Сборка решения
  ------------------------------------------------------------------ }

procedure ClearDecision(var D: TDecision);
begin
  D.accepted := False;
  D.errorCode := StrNil;
  D.errorDetail := StrNil;
  D.event := nil;
end;

{ Отказ. Код копируется в арену не всегда: он приходит константой из
  errcodes.inc, которая живёт в сегменте кода и переживает решение. }
procedure Reject(var D: TDecision; const Code: string);
begin
  ClearDecision(D);
  D.accepted := False;
  D.errorCode := StrView(Code);
end;

procedure AcceptEmpty(var D: TDecision);
begin
  ClearDecision(D);
  D.accepted := True;
end;

function EmitEvent(var A: TArena; var D: TDecision;
                   const EvType: string; const AggId: TStr;
                   var Node: PDomainEventNode): TResult;
var
  R: TResult;
  P: Pointer;
  Cur: PDomainEventNode;
begin
  Node := nil;

  R := ArenaAllocZero(A, SizeOf(TDomainEventNode), P);
  if not R.Ok then
  begin
    EmitEvent := R;
    Exit;
  end;

  Node := PDomainEventNode(P);

  { Тип события копируется в арену: литерал живёт в сегменте кода и
    пережил бы решение, но проверять это на каждом вызове дороже, чем
    скопировать двадцать байт. Единообразие важнее экономии. }
  R := ArenaDupStr(A, StrView(EvType), Node^.Value.type_);
  if not R.Ok then
  begin
    EmitEvent := R;
    Exit;
  end;

  R := ArenaDupStr(A, AggId, Node^.Value.aggregateId);
  if not R.Ok then
  begin
    EmitEvent := R;
    Exit;
  end;

  Node^.Value.field := nil;
  Node^.Next := nil;

  { В хвост, а не в голову: порядок событий в решении обязан совпадать
    с порядком, в котором домен их породил. Оболочка запишет их в outbox
    как есть, а порядок внутри агрегата гарантирован контрактом. }
  if D.event = nil then
    D.event := Node
  else
  begin
    Cur := D.event;
    while Cur^.Next <> nil do
      Cur := Cur^.Next;
    Cur^.Next := Node;
  end;

  EmitEvent := Ok;
end;

function EmitField(var A: TArena; Node: PDomainEventNode;
                   const Key: string; const Value: TStr): TResult;
var
  R: TResult;
  P: Pointer;
  FN, Cur: PEventFieldNode;
begin
  if Node = nil then
  begin
    EmitField := Err(ERR_DECIDER_PANIC);
    Exit;
  end;

  R := ArenaAllocZero(A, SizeOf(TEventFieldNode), P);
  if not R.Ok then
  begin
    EmitField := R;
    Exit;
  end;
  FN := PEventFieldNode(P);

  R := ArenaDupStr(A, StrView(Key), FN^.Value.key);
  if not R.Ok then
  begin
    EmitField := R;
    Exit;
  end;

  R := ArenaDupStr(A, Value, FN^.Value.value);
  if not R.Ok then
  begin
    EmitField := R;
    Exit;
  end;

  FN^.Next := nil;
  if Node^.Value.field = nil then
    Node^.Value.field := FN
  else
  begin
    Cur := Node^.Value.field;
    while Cur^.Next <> nil do
      Cur := Cur^.Next;
    Cur^.Next := FN;
  end;

  EmitField := Ok;
end;

{ ------------------------------------------------------------------
  Полнота состояния

  Ядро решает только по тому, что ему прислали. Если оболочка объявленное
  контрактом состояние не заполнила, единственный правильный ответ —
  отказать с INSUFFICIENT_CONTEXT, а не додумать значение по умолчанию.
  Молчаливое допущение здесь даёт формально корректное и фактически
  неверное решение, которое всплывёт на данных, а не в тестах (R5).
  ------------------------------------------------------------------ }

function ActorComplete(const Actor: TActorContext): Boolean;
begin
  { Отрицательный счётчик означает «оболочка не считала», а не «ноль». }
  ActorComplete := IdWellFormed(Actor.userId) and
                   (Actor.postsLastHour >= 0) and
                   (Actor.commentsLastHour >= 0);
end;

function TargetComplete(const T: TTargetUserContext): Boolean;
begin
  { У несуществующего пользователя остальные поля не осмысленны, и
    требовать их заполнения было бы неверно. }
  if not T.exists then
    TargetComplete := True
  else
    TargetComplete := IdWellFormed(T.userId) and (T.version >= 0);
end;

function PostComplete(const P: TPostContext): Boolean;
begin
  if not P.exists then
    PostComplete := True
  else
    PostComplete := IdWellFormed(P.postId) and
                    IdWellFormed(P.authorId) and
                    (P.version >= 0);
end;

{ ------------------------------------------------------------------
  RegisterUser
  ------------------------------------------------------------------ }

function DecideRegisterUser(var A: TArena; const Req: TRegisterUserRequest;
                            var D: TDecision): TResult;
var
  Ev: PDomainEventNode;
  R: TResult;
begin
  ClearDecision(D);
  DecideRegisterUser := Ok;

  if not IdWellFormed(Req.command.userId) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  { Прав здесь не проверяем: регистрируется тот, у кого их ещё нет. }

  if not NickWellFormed(Req.command.nick) then
  begin
    Reject(D, ERR_NICK_FORMAT_INVALID);
    Exit;
  end;

  if not DisplayNameWellFormed(Req.command.displayName) then
  begin
    Reject(D, ERR_DISPLAY_NAME_INVALID);
    Exit;
  end;

  if Req.nick.taken then
  begin
    Reject(D, ERR_NICK_TAKEN);
    Exit;
  end;

  AcceptEmpty(D);
  R := EmitEvent(A, D, 'user.registered', Req.command.userId, Ev);
  if not R.Ok then
  begin
    DecideRegisterUser := R;
    Exit;
  end;
  R := EmitField(A, Ev, 'nick', Req.command.nick);
  if not R.Ok then
    DecideRegisterUser := R;
end;

{ ------------------------------------------------------------------
  CreatePost
  ------------------------------------------------------------------ }

function DecideCreatePost(var A: TArena; const Req: TCreatePostRequest;
                          var D: TDecision): TResult;
var
  Ev: PDomainEventNode;
  R: TResult;
begin
  ClearDecision(D);
  DecideCreatePost := Ok;

  if not ActorComplete(Req.actor) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  if not IdWellFormed(Req.command.postId) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  { Права раньше формы: заблокированному незачем сообщать, что его текст
    к тому же слишком длинный. }
  if IsBanned(Req.actor.status) or IsGone(Req.actor.status) then
  begin
    Reject(D, ERR_ACTOR_BANNED);
    Exit;
  end;

  if StrIsBlank(Req.command.body) then
  begin
    Reject(D, ERR_POST_BODY_EMPTY);
    Exit;
  end;

  if Req.command.body.Len > LIM_POST_BODY_MAX_LEN then
  begin
    Reject(D, ERR_POST_BODY_TOO_LONG);
    Exit;
  end;

  if Req.actor.postsLastHour >= LIM_POSTS_PER_HOUR then
  begin
    Reject(D, ERR_POST_RATE_EXCEEDED);
    Exit;
  end;

  AcceptEmpty(D);
  R := EmitEvent(A, D, 'post.created', Req.command.postId, Ev);
  if not R.Ok then
  begin
    DecideCreatePost := R;
    Exit;
  end;
  R := EmitField(A, Ev, 'authorId', Req.actor.userId);
  if not R.Ok then
    DecideCreatePost := R;
end;

{ ------------------------------------------------------------------
  DeletePost

  Удаление идемпотентно: повторный запрос принимается и не порождает
  событий. Это не снисходительность, а требование к повторам по линии,
  которая может доставить команду дважды.
  ------------------------------------------------------------------ }

function DecideDeletePost(var A: TArena; const Req: TDeletePostRequest;
                          var D: TDecision): TResult;
var
  Ev: PDomainEventNode;
  R: TResult;
  IsOwner: Boolean;
begin
  ClearDecision(D);
  DecideDeletePost := Ok;

  if (not ActorComplete(Req.actor)) or (not PostComplete(Req.post)) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  if not Req.post.exists then
  begin
    Reject(D, ERR_POST_NOT_FOUND);
    Exit;
  end;

  { Блокировка запрещает создавать содержимое, но не убирать своё:
    заблокированный вправе удалить собственный пост. }
  IsOwner := StrEq(Req.actor.userId, Req.post.authorId);
  if (not IsOwner) and (not RoleAtLeast(Req.actor.role, Role_MODERATOR)) then
  begin
    Reject(D, ERR_NOT_OWNER);
    Exit;
  end;

  if Req.post.status = PostStatus_DELETED then
  begin
    { Уже удалён — принимаем без событий. }
    AcceptEmpty(D);
    Exit;
  end;

  AcceptEmpty(D);
  R := EmitEvent(A, D, 'post.deleted', Req.post.postId, Ev);
  if not R.Ok then
  begin
    DecideDeletePost := R;
    Exit;
  end;
  R := EmitField(A, Ev, 'deletedBy', Req.actor.userId);
  if not R.Ok then
    DecideDeletePost := R;
end;

{ ------------------------------------------------------------------
  FollowUser
  ------------------------------------------------------------------ }

function DecideFollowUser(var A: TArena; const Req: TFollowUserRequest;
                          var D: TDecision): TResult;
var
  Ev: PDomainEventNode;
  R: TResult;
begin
  ClearDecision(D);
  DecideFollowUser := Ok;

  if (not ActorComplete(Req.actor)) or (not TargetComplete(Req.target)) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  if (not Req.target.exists) or IsGone(Req.target.status) then
  begin
    Reject(D, ERR_USER_NOT_FOUND);
    Exit;
  end;

  { Подписка — это создание связи, то есть содержимого. }
  if IsBanned(Req.actor.status) then
  begin
    Reject(D, ERR_ACTOR_BANNED);
    Exit;
  end;

  if StrEq(Req.actor.userId, Req.target.userId) then
  begin
    Reject(D, ERR_SELF_FOLLOW);
    Exit;
  end;

  if Req.follow.alreadyFollowing then
  begin
    Reject(D, ERR_ALREADY_FOLLOWING);
    Exit;
  end;

  AcceptEmpty(D);
  R := EmitEvent(A, D, 'follow.created', Req.actor.userId, Ev);
  if not R.Ok then
  begin
    DecideFollowUser := R;
    Exit;
  end;
  R := EmitField(A, Ev, 'targetUserId', Req.target.userId);
  if not R.Ok then
    DecideFollowUser := R;
end;

{ ------------------------------------------------------------------
  BanUser
  ------------------------------------------------------------------ }

function DecideBanUser(var A: TArena; const Req: TBanUserRequest;
                       var D: TDecision): TResult;
var
  Ev: PDomainEventNode;
  R: TResult;
begin
  ClearDecision(D);
  DecideBanUser := Ok;

  if (not ActorComplete(Req.actor)) or (not TargetComplete(Req.target)) then
  begin
    Reject(D, ERR_INSUFFICIENT_CONTEXT);
    Exit;
  end;

  if not RoleAtLeast(Req.actor.role, Role_MODERATOR) then
  begin
    Reject(D, ERR_ROLE_INSUFFICIENT);
    Exit;
  end;

  if not Req.target.exists then
  begin
    Reject(D, ERR_USER_NOT_FOUND);
    Exit;
  end;

  { Строго старше: равный по роли неприкосновенен, иначе двое модераторов
    заблокируют друг друга. Себя это правило тоже покрывает — сам себя
    никто не старше. }
  if not RoleOutranks(Req.actor.role, Req.target.role) then
  begin
    Reject(D, ERR_CANNOT_MODERATE_PEER);
    Exit;
  end;

  if StrIsBlank(Req.command.reason) then
  begin
    Reject(D, ERR_BAN_REASON_EMPTY);
    Exit;
  end;

  if Req.command.reason.Len > LIM_BAN_REASON_MAX_LEN then
  begin
    Reject(D, ERR_BAN_REASON_TOO_LONG);
    Exit;
  end;

  if IsBanned(Req.target.status) then
  begin
    Reject(D, ERR_ALREADY_BANNED);
    Exit;
  end;

  AcceptEmpty(D);
  R := EmitEvent(A, D, 'user.banned', Req.target.userId, Ev);
  if not R.Ok then
  begin
    DecideBanUser := R;
    Exit;
  end;

  { Причина обязательно попадает в событие: она нужна аудиту, а аудит
    строится из outbox, а не из журнала оболочки. }
  R := EmitField(A, Ev, 'reason', Req.command.reason);
  if not R.Ok then
  begin
    DecideBanUser := R;
    Exit;
  end;
  R := EmitField(A, Ev, 'bannedBy', Req.actor.userId);
  if not R.Ok then
    DecideBanUser := R;
end;

end.
