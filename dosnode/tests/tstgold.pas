{ ===================================================================
  Эталонные конверты: разбирает ли ядро то, что на самом деле шлёт Java.

  Обе стороны contract-first порождаются из одного WSDL, но «порождаются
  из одного» и «понимают друг друга» — разные утверждения, и первое не
  влечёт второго. Разойтись они могут на порядке элементов, на префиксах,
  на том, как JAXB решит записать перечисление или число.

  Поэтому здесь читаются НЕ СОЧИНЁННЫЕ конверты, а файлы, которые записал
  маршалер JAXB на настоящих сгенерированных типах:

      contracts/generated/envelopes/*.xml

  Они закоммичены, и тест на стороне Java падает, если конверт изменился.
  Так вопрос «а поймут ли друг друга?» перестаёт быть вопросом веры.

  ПЕРВАЯ ЖЕ ПРОВЕРКА УЖЕ НАШЛА РАСХОЖДЕНИЕ. Рукописный конверт в tstsoap
  назывался <createPost>, а CXF шлёт <CreatePostRequest> — имя элемента из
  WSDL, а не имя операции. Ядро, настроенное на первое, не поняло бы ни
  одной настоящей команды.

  ДОЛГОЕ ВРЕМЯ ЭТАЛОН БЫЛ ОДИН, на CreatePost. Остальные семь операций
  держались ровно на том допущении, которое найденное расхождение уже
  опровергло: раз стороны порождены из одного WSDL, значит поймут. Теперь
  эталон есть у каждой операции, а тест на стороне Java красит сборку,
  если операцию завели без эталона.

  Что здесь проверяется сверх «разобралось»:

  * ЧИСЛО ПОЛЕЙ. Оно выведено из контракта — сумма полей меты, команды и
    каждого контекста — и проверяется с двух сторон независимо: там по
    разметке, здесь по числу вызовов обработчика. Пропавшее поле ядро
    получило бы нулевым и решило бы по неполным данным (R5).
  * ОДНОИМЁННЫЕ УРОВНИ. В конверте регистрации поле nick лежит внутри
    команды, а группа nick — рядом с ней. Разборщик, различающий имена
    без учёта уровня, спутал бы их.
  * PING БЕЗ ГРУПП. У него нет ни меты, ни контекстов: поле лежит прямо
    под операцией, конверт на уровень мельче остальных. Разборщик,
    считающий группу обязательной, сломался бы именно здесь.
  * ЧИСЛО ЗА ПРЕДЕЛАМИ LONGINT. issuedAtMillis не помещается в 32 бита,
    и на шестнадцатибитном таргете это отдельный риск, а не формальность.
  =================================================================== }
program TstGold;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, TcTest, TcFrame, TcXml, TcSoap,
  DcdTypes, DcdSrv;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile   = 'TSTGOLD.TAP';
  FnReg     = 'REGUSER.XML';
  FnPost    = 'CRPOST.XML';
  FnComm    = 'CRCOMM.XML';
  FnDel     = 'DELPOST.XML';
  FnFol     = 'FOLLOW.XML';
  FnUnf     = 'UNFOLLOW.XML';
  FnBan     = 'BANUSER.XML';
  FnPing    = 'PING.XML';
{$ELSE}
  TapFile   = 'tstgold.tap';
  FnReg     = 'register-user.xml';
  FnPost    = 'create-post.xml';
  FnComm    = 'create-comment.xml';
  FnDel     = 'delete-post.xml';
  FnFol     = 'follow-user.xml';
  FnUnf     = 'unfollow-user.xml';
  FnBan     = 'ban-user.xml';
  FnPing    = 'ping.xml';
{$ENDIF}

  { Семь механических проверок на операцию плюс тридцать три сверки
    значений плюс арена. }
  PlannedTests = 90;

  { Кусками по тринадцать байт: по линии конверт приедет кадрами, и
    граница куска ляжет где попало — в том числе посреди кириллической
    буквы, которая занимает два байта. }
  ChunkSize = 13;

type
  TOpKind = (okRegister, okPost, okComment, okDelete,
             okFollow, okUnfollow, okBan, okPing);

var
  A: TArena;
  R: TResult;
  Rd: TSoapReader;

  { По одному запросу на операцию. Разбор идёт по очереди, но записи
    держатся все: обработчик поля выбирает нужную по виду операции. }
  RqReg:  TRegisterUserRequest;
  RqPost: TCreatePostRequest;
  RqComm: TCreateCommentRequest;
  RqDel:  TDeletePostRequest;
  RqFol:  TFollowUserRequest;
  RqUnf:  TUnfollowUserRequest;
  RqBan:  TBanUserRequest;
  RqPing: TPingRequest;

  Kind: TOpKind;

  Buf: array[0..4095] of Byte;
  Len: Word;

  { Итог последнего разбора. }
  OpSeen: string;
  Fields, Unknowns, BadValues: Integer;
  Fed: Boolean;
  FinishRes: TResult;

{ Обработчики. far обязателен: в модели large переменная процедурного
  типа — дальний указатель. }
procedure OnOp(const Op: TStr); far;
begin
  OpSeen := StrHead(Op);
end;

procedure OnField(const Group, Field, Value: TStr); far;
var
  O: TFillOutcome;
begin
  Inc(Fields);
  case Kind of
    okRegister: O := DcdSrv.FillRegisterUser(RqReg, Group, Field, Value);
    okPost:     O := DcdSrv.FillCreatePost(RqPost, Group, Field, Value);
    okComment:  O := DcdSrv.FillCreateComment(RqComm, Group, Field, Value);
    okDelete:   O := DcdSrv.FillDeletePost(RqDel, Group, Field, Value);
    okFollow:   O := DcdSrv.FillFollowUser(RqFol, Group, Field, Value);
    okUnfollow: O := DcdSrv.FillUnfollowUser(RqUnf, Group, Field, Value);
    okBan:      O := DcdSrv.FillBanUser(RqBan, Group, Field, Value);
    okPing:     O := DcdSrv.FillPing(RqPing, Group, Field, Value);
  else
    O := foUnknown;
  end;
  case O of
    foUnknown:  Inc(Unknowns);
    foBadValue: Inc(BadValues);
  end;
end;

{ Прочитать эталон целиком. Возвращает False, если файла нет: это не
  «тест провалился», а «эталон не подложили», и различать эти исходы
  важнее, чем кажется. }
function ReadGold(const FileName: string): Boolean;
var
  F: file;
  Got: Word;
begin
  ReadGold := False;
  Len := 0;
{$I-}
  Assign(F, FileName);
  Reset(F, 1);
{$I+}
  if IOResult <> 0 then
    Exit;
  BlockRead(F, Buf, SizeOf(Buf), Got);
  Close(F);
  Len := Got;
  ReadGold := Got > 0;
end;

{ Разобрать один эталон. Арена сбрасывается ПЕРЕД разбором, а не после:
  строки разобранной записи лежат в ней, и сверять значения можно только
  пока она цела. }
procedure ParseGold(const FileName: string; K: TOpKind);
var
  I, Chunk: Word;
begin
  ArenaReset(A);
  Kind := K;
  OpSeen := '';
  Fields := 0;
  Unknowns := 0;
  BadValues := 0;
  Fed := False;
  FinishRes := Err(ERR_MALFORMED_ENVELOPE);

  if not ReadGold(FileName) then
    Exit;

  SoapReaderInit(Rd, A, OnOp, OnField);

  Fed := True;
  Chunk := ChunkSize;
  I := 0;
  while I < Len do
  begin
    if Len - I < Chunk then
      Chunk := Len - I;
    if not SoapFeed(Rd, Buf[I], Chunk) then
    begin
      Fed := False;
      Break;
    end;
    Inc(I, Chunk);
  end;

  FinishRes := SoapFinish(Rd);
end;

{ Механические проверки, одинаковые для всех операций. Ради них тест и
  устроен таблицей: свойство «конверт разобран целиком и без остатка»
  не должно зависеть от того, какая это операция. }
procedure CheckMechanics(const Title, WantOp: string; WantFields: Integer);
begin
  TestTrue(Title + ': эталон прочитан', Len > 0);
  TestTrue(Title + ': скормлен разборщику без отказа', Fed);
  TestResultOk(Title + ': разобран целиком', FinishRes);
  TestEqStr(Title + ': имя операции из настоящего конверта', OpSeen, WantOp);
  TestEqInt(Title + ': неизвестных полей нет', Unknowns, 0);
  TestEqInt(Title + ': негодных значений нет', BadValues, 0);
  TestEqInt(Title + ': полей столько, сколько объявил контракт',
            Fields, WantFields);
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('эталонные конверты из Java, все операции контракта');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}

  R := ArenaCreate(A, 16384, 'gold');
  TestResultOk('арена создана', R);

  FillChar(RqReg,  SizeOf(RqReg),  0);
  FillChar(RqPost, SizeOf(RqPost), 0);
  FillChar(RqComm, SizeOf(RqComm), 0);
  FillChar(RqDel,  SizeOf(RqDel),  0);
  FillChar(RqFol,  SizeOf(RqFol),  0);
  FillChar(RqUnf,  SizeOf(RqUnf),  0);
  FillChar(RqBan,  SizeOf(RqBan),  0);
  FillChar(RqPing, SizeOf(RqPing), 0);

  { --- RegisterUser -------------------------------------------------
    Здесь поле nick лежит внутри команды, а одноимённая группа — рядом
    с ней. Разборщик, различающий имена без учёта уровня, положил бы
    признак занятости ника в имя пользователя. }
  ParseGold(FnReg, okRegister);
  CheckMechanics('регистрация', 'RegisterUserRequest', 7);
  TestEqStr('регистрация: идентификатор',
            StrHead(RqReg.command.userId), 'u-andrey');
  TestEqStr('регистрация: ник из команды, а не из одноимённой группы',
            StrHead(RqReg.command.nick), 'andrey');
  TestEqStr('регистрация: отображаемое имя с кириллицей и амперсандом',
            StrHead(RqReg.command.displayName), 'Андрей & Ко');
  TestFalse('регистрация: ник не занят', RqReg.nick.taken);

  { --- CreatePost --------------------------------------------------- }
  ParseGold(FnPost, okPost);
  CheckMechanics('пост', 'CreatePostRequest', 10);
  TestEqStr('пост: идентификатор', StrHead(RqPost.command.postId), 'p-1001');
  { Кириллица и амперсанд: первое проверяет кодировку, второе —
    разрешение сущностей. И то и другое пишет пользователь. }
  TestEqStr('пост: тело с кириллицей и амперсандом',
            StrHead(RqPost.command.body), 'Первый пост & последний');
  TestEqStr('пост: идентификатор автора',
            StrHead(RqPost.actor.userId), 'u-andrey');
  TestEqInt('пост: роль разобрана', Ord(RqPost.actor.role), Ord(Role_USER));
  TestEqInt('пост: статус разобран',
            Ord(RqPost.actor.status), Ord(UserStatus_ACTIVE));
  TestEqInt('пост: счётчик постов разобран', RqPost.actor.postsLastHour, 0);
  TestEqStr('пост: идентификатор трассировки',
            StrHead(RqPost.meta.traceId), 't-1');

  { --- CreateComment ------------------------------------------------ }
  ParseGold(FnComm, okComment);
  CheckMechanics('комментарий', 'CreateCommentRequest', 16);
  TestEqStr('комментарий: идентификатор',
            StrHead(RqComm.command.commentId), 'k-2002');
  TestEqStr('комментарий: пост, к которому он написан',
            StrHead(RqComm.command.postId), 'p-1001');
  TestEqStr('комментарий: тело с угловой скобкой',
            StrHead(RqComm.command.body), 'Ответ < ответа');
  TestEqStr('комментарий: автор поста из контекста',
            StrHead(RqComm.post.authorId), 'u-andrey');
  TestEqInt('комментарий: статус поста разобран',
            Ord(RqComm.post.status), Ord(PostStatus_VISIBLE));

  { --- DeletePost --------------------------------------------------- }
  ParseGold(FnDel, okDelete);
  CheckMechanics('удаление', 'DeletePostRequest', 14);
  TestEqStr('удаление: идентификатор поста',
            StrHead(RqDel.command.postId), 'p-1001');
  TestEqInt('удаление: роль модератора разобрана',
            Ord(RqDel.actor.role), Ord(Role_MODERATOR));
  TestTrue('удаление: пост существует', RqDel.post.exists);
  TestEqInt('удаление: версия поста разобрана', RqDel.post.version, 9);

  { --- FollowUser ---------------------------------------------------
    Здесь же проверяется число, не помещающееся в LongInt: на
    шестнадцати битах это Int64, и его разбор — отдельный риск. }
  ParseGold(FnFol, okFollow);
  CheckMechanics('подписка', 'FollowUserRequest', 15);
  TestEqStr('подписка: цель', StrHead(RqFol.command.targetUserId), 'u-boris');
  TestEqInt('подписка: версия цели разобрана', RqFol.target.version, 5);
  TestFalse('подписка: ребра ещё нет', RqFol.follow.alreadyFollowing);
  TestTrue('подписка: время команды за пределами LongInt разобрано',
           RqFol.meta.issuedAtMillis = Int64(1756684800000));

  { --- UnfollowUser -------------------------------------------------
    Зеркало подписки: там ребра нет, здесь оно есть. }
  ParseGold(FnUnf, okUnfollow);
  CheckMechanics('отписка', 'UnfollowUserRequest', 15);
  TestEqStr('отписка: цель', StrHead(RqUnf.command.targetUserId), 'u-boris');
  TestTrue('отписка: цель существует', RqUnf.target.exists);
  TestTrue('отписка: ребро есть', RqUnf.follow.alreadyFollowing);
  TestEqInt('отписка: счётчик комментариев разобран',
            RqUnf.actor.commentsLastHour, 11);

  { --- BanUser ------------------------------------------------------ }
  ParseGold(FnBan, okBan);
  CheckMechanics('блокировка', 'BanUserRequest', 15);
  TestEqStr('блокировка: цель', StrHead(RqBan.command.targetUserId), 'u-boris');
  TestEqStr('блокировка: причина с кириллицей и амперсандом',
            StrHead(RqBan.command.reason), 'Спам & брань');
  TestEqInt('блокировка: роль модератора разобрана',
            Ord(RqBan.actor.role), Ord(Role_MODERATOR));
  TestEqInt('блокировка: версия цели разобрана', RqBan.target.version, 12);

  { --- Ping ---------------------------------------------------------
    Ни меты, ни контекстов: поле лежит прямо под операцией. Конверт на
    уровень мельче остальных, и разборщик, считающий группу
    обязательной, сломался бы именно здесь. }
  ParseGold(FnPing, okPing);
  CheckMechanics('пинг', 'PingRequest', 1);
  TestEqInt('пинг: значение поля без группы', RqPing.nonce, 20260901);

  ArenaDestroy(A);
  Halt(TestEnd);
end.
