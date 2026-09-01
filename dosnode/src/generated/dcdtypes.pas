{ СГЕНЕРИРОВАНО. Не править руками.

  Источник:      contracts/soap/decider-v1.wsdl
  Генератор:     tools/wsdl2pas/wsdl2pas.py
  Перегенерация: ./sonder codegen

  Правка этого файла будет затёрта, а расхождение с контрактом поймано
  проверкой дрейфа в CI.
}

unit DcdTypes;

{$MODE TP}

interface

uses
  TcStr;

{ TStr берётся из TcStr, а не объявляется здесь.
  Вокабуляр принадлежит фреймворку: если бы генератор
  объявлял свой тип строки, каждый сгенерированный модуль
  имел бы несовместимый с остальными. }

type
  TRole = (Role_USER, Role_MODERATOR, Role_ADMIN);
  TUserStatus = (UserStatus_ACTIVE, UserStatus_BANNED, UserStatus_DELETED);
  TPostStatus = (PostStatus_VISIBLE, PostStatus_DELETED);

  PDomainEventNode = ^TDomainEventNode;
  PEventFieldNode = ^TEventFieldNode;

  TCommandMeta = record
    traceId: TStr;
    commandId: TStr;
    issuedAtMillis: Int64;
  end;

  TActorContext = record
    userId: TStr;
    role: TRole;
    status: TUserStatus;
    postsLastHour: LongInt;
    commentsLastHour: LongInt;
  end;

  TTargetUserContext = record
    exists: Boolean;
    userId: TStr;
    role: TRole;
    status: TUserStatus;
    version: LongInt;
  end;

  TPostContext = record
    exists: Boolean;
    postId: TStr;
    authorId: TStr;
    status: TPostStatus;
    version: LongInt;
  end;

  TNickContext = record
    taken: Boolean;
  end;

  TFollowContext = record
    alreadyFollowing: Boolean;
  end;

  TEventField = record
    key: TStr;
    value: TStr;
  end;

  TDomainEvent = record
    type_: TStr;   { в контракте: type }
    aggregateId: TStr;
    field: PEventFieldNode;   { список, узлы из арены }
  end;

  TDecision = record
    accepted: Boolean;
    errorCode: TStr;   { необязательное }
    errorDetail: TStr;   { необязательное }
    event: PDomainEventNode;   { список, узлы из арены }
  end;

  TRegisterUserCommand = record
    userId: TStr;
    nick: TStr;
    displayName: TStr;
  end;

  TCreatePostCommand = record
    postId: TStr;
    body: TStr;
  end;

  TCreateCommentCommand = record
    commentId: TStr;
    postId: TStr;
    body: TStr;
  end;

  TDeletePostCommand = record
    postId: TStr;
  end;

  TFollowUserCommand = record
    targetUserId: TStr;
  end;

  TBanUserCommand = record
    targetUserId: TStr;
    reason: TStr;
  end;

  TRegisterUserRequest = record
    meta: TCommandMeta;
    command: TRegisterUserCommand;
    nick: TNickContext;
  end;

  TCreatePostRequest = record
    meta: TCommandMeta;
    command: TCreatePostCommand;
    actor: TActorContext;
  end;

  TCreateCommentRequest = record
    meta: TCommandMeta;
    command: TCreateCommentCommand;
    actor: TActorContext;
    post: TPostContext;
  end;

  TDeletePostRequest = record
    meta: TCommandMeta;
    command: TDeletePostCommand;
    actor: TActorContext;
    post: TPostContext;
  end;

  TFollowUserRequest = record
    meta: TCommandMeta;
    command: TFollowUserCommand;
    actor: TActorContext;
    target: TTargetUserContext;
    follow: TFollowContext;
  end;

  TBanUserRequest = record
    meta: TCommandMeta;
    command: TBanUserCommand;
    actor: TActorContext;
    target: TTargetUserContext;
  end;

  TPingRequest = record
    nonce: LongInt;
  end;

  TPingResponse = record
    nonce: LongInt;
    fibersInUse: LongInt;
    arenaHighMark: LongInt;
  end;

  TDomainEventNode = record
    Value: TDomainEvent;
    Next: PDomainEventNode;
  end;

  TEventFieldNode = record
    Value: TEventField;
    Next: PEventFieldNode;
  end;

const
  { Имена значений перечислений: по ним идёт разбор и сборка XML. }
  RoleNames: array[TRole] of PChar = ('USER', 'MODERATOR', 'ADMIN');
  UserStatusNames: array[TUserStatus] of PChar = ('ACTIVE', 'BANNED', 'DELETED');
  PostStatusNames: array[TPostStatus] of PChar = ('VISIBLE', 'DELETED');

  { Операции контракта. }
  OperationCount = 7;
  Op_RegisterUser = 'RegisterUser';
  Op_CreatePost = 'CreatePost';
  Op_CreateComment = 'CreateComment';
  Op_DeletePost = 'DeletePost';
  Op_FollowUser = 'FollowUser';
  Op_BanUser = 'BanUser';
  Op_Ping = 'Ping';

implementation

end.
