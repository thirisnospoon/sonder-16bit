{ СГЕНЕРИРОВАНО. Не править руками.

  Источник:      contracts/soap/decider-v1.wsdl
  Генератор:     tools/wsdl2pas/wsdl2pas.py
  Перегенерация: ./sonder codegen

  Серверная сторона: разбор полей команды и запись решения. Java —
  клиент SOAP, NODE-7 — сервер (ADR-0011), поэтому здесь именно разбор
  запроса и запись ответа, а не наоборот.

  Отдельный модуль, а не добавка к DcdTypes: типы нужны всем, кто трогает
  контракт, а разбор конверта — только ноде. Тянуть TcSoap туда, где
  нужен один TStr, незачем.

  Разбор идёт по паре «группа, поле», а не по дереву: каждая операция в
  контракте объявляет группы из скалярных полей, глубже не заходит ни
  одна (см. TcSoap). Форма этих процедур повторяет ту, что была написана
  руками в tstsoap до появления генератора.

  С НЕРАЗОБРАННЫМ ЗНАЧЕНИЕМ НЕ ДЕЛАЕТСЯ НИЧЕГО МОЛЧА. Поле, которого нет
  в контракте, и поле, значение которого не разбирается, различаются и
  возвращаются вызывающему. Оставить в записи ноль и продолжить значило
  бы решить по неполным данным — тот самый R5.
}

unit DcdSrv;

{$MODE TP}
{$R-}

interface

uses
  TcStr, TcSoap, DcdTypes;

const
  { Пространство имён контракта. Отсюда, а не строкой в каждом
    месте: корень тела ответа обязан его объявлять, иначе
    связыватель на другой стороне не найдёт ни одного поля. }
  DeciderNs = 'urn:sonder:decider:v1';

type
  { Что стало с полем, пришедшим в конверте. }
  TFillOutcome = (
    foOk,        { поле известно и разобрано }
    foUnknown,   { такого поля нет в контракте }
    foBadValue   { поле есть, а значение не разбирается }
  );

function FillRegisterUser(var Req: TRegisterUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillCreatePost(var Req: TCreatePostRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillCreateComment(var Req: TCreateCommentRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillDeletePost(var Req: TDeletePostRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillFollowUser(var Req: TFollowUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillUnfollowUser(var Req: TUnfollowUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillBanUser(var Req: TBanUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
function FillPing(var Req: TPingRequest;
                 const Group, Field, Value: TStr): TFillOutcome;

{ Запись решения. Одна на все операции: решение по контракту
  всегда TDecision, различается только имя элемента ответа. }
procedure WriteDecision(var W: TSoapWriter;
                        const ResponseName: string;
                        const D: TDecision);

{ Запись прочих ответов. Каждый со своим набором полей,
  поэтому по писателю на ответ — но пишет их всё равно
  генератор: рукописный писатель это второй экземпляр
  контракта, и расходится он молча. }
procedure WritePingResponse(var W: TSoapWriter;
                        const P: TPingResponse);

implementation

function ParseRole(const S: TStr; var V: TRole): Boolean;
begin
  ParseRole := True;
  if StrEqPas(S, 'USER') then
  begin V := Role_USER; Exit; end;
  if StrEqPas(S, 'MODERATOR') then
  begin V := Role_MODERATOR; Exit; end;
  if StrEqPas(S, 'ADMIN') then
  begin V := Role_ADMIN; Exit; end;
  ParseRole := False;
end;

function ParseUserStatus(const S: TStr; var V: TUserStatus): Boolean;
begin
  ParseUserStatus := True;
  if StrEqPas(S, 'ACTIVE') then
  begin V := UserStatus_ACTIVE; Exit; end;
  if StrEqPas(S, 'BANNED') then
  begin V := UserStatus_BANNED; Exit; end;
  if StrEqPas(S, 'DELETED') then
  begin V := UserStatus_DELETED; Exit; end;
  ParseUserStatus := False;
end;

function ParsePostStatus(const S: TStr; var V: TPostStatus): Boolean;
begin
  ParsePostStatus := True;
  if StrEqPas(S, 'VISIBLE') then
  begin V := PostStatus_VISIBLE; Exit; end;
  if StrEqPas(S, 'DELETED') then
  begin V := PostStatus_DELETED; Exit; end;
  ParsePostStatus := False;
end;

function FillRegisterUser(var Req: TRegisterUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.command.userId := Value;
    end
    else if StrEqPas(Field, 'nick') then
    begin
      Req.command.nick := Value;
    end
    else if StrEqPas(Field, 'displayName') then
    begin
      Req.command.displayName := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'nick') then
  begin
    if StrEqPas(Field, 'taken') then
    begin
      if StrEqPas(Value, 'true') then Req.nick.taken := True
      else if StrEqPas(Value, 'false') then Req.nick.taken := False
      else Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillRegisterUser := Res;
end;

function FillCreatePost(var Req: TCreatePostRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'postId') then
    begin
      Req.command.postId := Value;
    end
    else if StrEqPas(Field, 'body') then
    begin
      Req.command.body := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillCreatePost := Res;
end;

function FillCreateComment(var Req: TCreateCommentRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'commentId') then
    begin
      Req.command.commentId := Value;
    end
    else if StrEqPas(Field, 'postId') then
    begin
      Req.command.postId := Value;
    end
    else if StrEqPas(Field, 'body') then
    begin
      Req.command.body := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'post') then
  begin
    if StrEqPas(Field, 'exists') then
    begin
      if StrEqPas(Value, 'true') then Req.post.exists := True
      else if StrEqPas(Value, 'false') then Req.post.exists := False
      else Res := foBadValue;
    end
    else if StrEqPas(Field, 'postId') then
    begin
      Req.post.postId := Value;
    end
    else if StrEqPas(Field, 'authorId') then
    begin
      Req.post.authorId := Value;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParsePostStatus(Value, Req.post.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'version') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.post.version := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillCreateComment := Res;
end;

function FillDeletePost(var Req: TDeletePostRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'postId') then
    begin
      Req.command.postId := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'post') then
  begin
    if StrEqPas(Field, 'exists') then
    begin
      if StrEqPas(Value, 'true') then Req.post.exists := True
      else if StrEqPas(Value, 'false') then Req.post.exists := False
      else Res := foBadValue;
    end
    else if StrEqPas(Field, 'postId') then
    begin
      Req.post.postId := Value;
    end
    else if StrEqPas(Field, 'authorId') then
    begin
      Req.post.authorId := Value;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParsePostStatus(Value, Req.post.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'version') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.post.version := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillDeletePost := Res;
end;

function FillFollowUser(var Req: TFollowUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'targetUserId') then
    begin
      Req.command.targetUserId := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'target') then
  begin
    if StrEqPas(Field, 'exists') then
    begin
      if StrEqPas(Value, 'true') then Req.target.exists := True
      else if StrEqPas(Value, 'false') then Req.target.exists := False
      else Res := foBadValue;
    end
    else if StrEqPas(Field, 'userId') then
    begin
      Req.target.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.target.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.target.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'version') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.target.version := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'follow') then
  begin
    if StrEqPas(Field, 'alreadyFollowing') then
    begin
      if StrEqPas(Value, 'true') then Req.follow.alreadyFollowing := True
      else if StrEqPas(Value, 'false') then Req.follow.alreadyFollowing := False
      else Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillFollowUser := Res;
end;

function FillUnfollowUser(var Req: TUnfollowUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'targetUserId') then
    begin
      Req.command.targetUserId := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'target') then
  begin
    if StrEqPas(Field, 'exists') then
    begin
      if StrEqPas(Value, 'true') then Req.target.exists := True
      else if StrEqPas(Value, 'false') then Req.target.exists := False
      else Res := foBadValue;
    end
    else if StrEqPas(Field, 'userId') then
    begin
      Req.target.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.target.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.target.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'version') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.target.version := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'follow') then
  begin
    if StrEqPas(Field, 'alreadyFollowing') then
    begin
      if StrEqPas(Value, 'true') then Req.follow.alreadyFollowing := True
      else if StrEqPas(Value, 'false') then Req.follow.alreadyFollowing := False
      else Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillUnfollowUser := Res;
end;

function FillBanUser(var Req: TBanUserRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if StrEqPas(Group, 'meta') then
  begin
    if StrEqPas(Field, 'traceId') then
    begin
      Req.meta.traceId := Value;
    end
    else if StrEqPas(Field, 'commandId') then
    begin
      Req.meta.commandId := Value;
    end
    else if StrEqPas(Field, 'issuedAtMillis') then
    begin
      if StrToInt64(Value, Tmp) then
        Req.meta.issuedAtMillis := Tmp
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'command') then
  begin
    if StrEqPas(Field, 'targetUserId') then
    begin
      Req.command.targetUserId := Value;
    end
    else if StrEqPas(Field, 'reason') then
    begin
      Req.command.reason := Value;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'actor') then
  begin
    if StrEqPas(Field, 'userId') then
    begin
      Req.actor.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.actor.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.actor.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'postsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.postsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'commentsLastHour') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.actor.commentsLastHour := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else if StrEqPas(Group, 'target') then
  begin
    if StrEqPas(Field, 'exists') then
    begin
      if StrEqPas(Value, 'true') then Req.target.exists := True
      else if StrEqPas(Value, 'false') then Req.target.exists := False
      else Res := foBadValue;
    end
    else if StrEqPas(Field, 'userId') then
    begin
      Req.target.userId := Value;
    end
    else if StrEqPas(Field, 'role') then
    begin
      if not ParseRole(Value, Req.target.role) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'status') then
    begin
      if not ParseUserStatus(Value, Req.target.status) then
        Res := foBadValue;
    end
    else if StrEqPas(Field, 'version') then
    begin
      if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
         and (Tmp <= 2147483647) then
        Req.target.version := LongInt(Tmp)
      else
        Res := foBadValue;
    end
    else
      Res := foUnknown;
  end
  else
    Res := foUnknown;
  FillBanUser := Res;
end;

function FillPing(var Req: TPingRequest;
                 const Group, Field, Value: TStr): TFillOutcome;
var
  Res: TFillOutcome;
  Tmp: Int64;
begin
  Res := foOk;
  if (Group.Len = 0) and StrEqPas(Field, 'nonce') then
  begin
    if StrToInt64(Value, Tmp) and (Tmp >= -2147483647)
       and (Tmp <= 2147483647) then
      Req.nonce := LongInt(Tmp)
    else
      Res := foBadValue;
  end
  else
    Res := foUnknown;
  FillPing := Res;
end;

procedure WriteDecision(var W: TSoapWriter;
                        const ResponseName: string;
                        const D: TDecision);
var
  Node: PDomainEventNode;
  Leaf: PEventFieldNode;
begin
  SoapOpenNs(W, ResponseName, 'urn:sonder:decider:v1');
  SoapElementBool(W, 'accepted', D.accepted);
  SoapElement(W, 'errorCode', D.errorCode);
  SoapElement(W, 'errorDetail', D.errorDetail);
  Node := D.event;
  while Node <> nil do
  begin
    SoapOpen(W, 'event');
    SoapElement(W, 'type', Node^.Value.type_);
    SoapElement(W, 'aggregateId', Node^.Value.aggregateId);
    Leaf := Node^.Value.field;
    while Leaf <> nil do
    begin
      SoapOpen(W, 'field');
      SoapElement(W, 'key', Leaf^.Value.key);
      SoapElement(W, 'value', Leaf^.Value.value);
      SoapClose(W, 'field');
      Leaf := Leaf^.Next;
    end;
    SoapClose(W, 'event');
    Node := Node^.Next;
  end;
  SoapClose(W, ResponseName);
end;

procedure WritePingResponse(var W: TSoapWriter; const P: TPingResponse);
begin
  SoapOpenNs(W, 'PingResponse', 'urn:sonder:decider:v1');
  SoapElementInt(W, 'nonce', P.nonce);
  SoapElementInt(W, 'fibersInUse', P.fibersInUse);
  SoapElementInt(W, 'arenaHighMark', P.arenaHighMark);
  SoapElementInt(W, 'arenaCapacity', P.arenaCapacity);
  SoapElementInt(W, 'commandsServed', P.commandsServed);
  SoapElementInt(W, 'commandsRefused', P.commandsRefused);
  SoapElementInt(W, 'commandsMalformed', P.commandsMalformed);
  SoapElementInt(W, 'lineErrors', P.lineErrors);
  SoapElementInt(W, 'rxBytes', P.rxBytes);
  SoapElementInt(W, 'txBytes', P.txBytes);
  SoapElementInt(W, 'logLinesLost', P.logLinesLost);
  SoapClose(W, 'PingResponse');
end;

end.
