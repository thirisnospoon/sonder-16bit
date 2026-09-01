{ ===================================================================
  Тесты конверта SOAP.

  Здесь же приземляется гейт фазы 5: полный круг «конверт с командой →
  решение ядра → конверт с ответом → разбор ответа», на обоих таргетах.
  Ради него всё и строилось — до этого места ни один модуль не был
  проверен вместе с остальными.

  Обработчик полей написан руками, хотя порождать его будет wsdl2pas.
  Это не времянка: он задаёт форму, в которую генератору предстоит
  попасть, и до тех пор пока формы нет, генерировать нечего.

  Отдельно проверяется то, что видно только в связке: значение поля,
  пришедшее несколькими кусками, и ответ, не поместившийся в один кадр.
  По отдельности оба модуля эти случаи проходят, а вместе могли бы и не
  пройти — куски приходят из разбора, а кадры уходят в запись.
  =================================================================== }
program TstSoap;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, TcTest, TcFrame, TcXml, TcSoap,
  DcdTypes, DcdSrv, DmDecide;

{$I errcodes.inc}
{$I dmlimits.inc}

const
{$IFDEF CPU16}
  TapFile = 'TSTSOAP.TAP';
{$ELSE}
  TapFile = 'tstsoap.tap';
{$ENDIF}

  PlannedTests = 70;

var
  A: TArena;
  R: TResult;
  Rd: TSoapReader;
  W: TSoapWriter;
  D: TDecision;
  Req: TCreatePostRequest;

  { След разбора: по одной записи на поле, «группа.поле=значение». }
  Trace: string;
  Overflow: Boolean;
  OpSeen: string;
  Fields: Integer;
  Unknowns, BadValues: Integer;

  { Что записал писатель. }
  Out_: array[0..8191] of Char;
  OutLen: Word;
  OutFrames: Integer;
  OutMore: Integer;      { кадров с FlagMore }
  OutLast: Integer;      { кадров без FlagMore }

  { Четыре килобайта, а не два: длинный ответ ниже занимает больше двух
    тысяч байт, и в буфер поменьше он не влезал. }
  Doc: array[0..4095] of Char;
  DocLen: Word;

  I: Integer;

{ ------------------------------------------------------------------
  Сбор следа
  ------------------------------------------------------------------ }

procedure Add(const S: string);
begin
  if Length(Trace) + Length(S) > 240 then
  begin
    Overflow := True;
    Exit;
  end;
  Trace := Trace + S;
end;

procedure OnOp(const Op: TStr); far;
begin
  OpSeen := StrHead(Op);
end;

procedure OnField(const Group, Field, Value: TStr); far;
begin
  Inc(Fields);
  if Group.Len > 0 then
    Add(StrHead(Group) + '.' + StrHead(Field) + '=' + StrHead(Value) + ';')
  else
    Add(StrHead(Field) + '=' + StrHead(Value) + ';');
end;

{ Набивка записи идёт СГЕНЕРИРОВАННЫМ кодом. Раньше здесь лежала ручная
  заготовка: она задавала форму, в которую предстояло попасть генератору.
  Форма есть, генератор в неё попал, и держать заготовку рядом значило бы
  проверять её вместо него.

  Обёртка нужна только затем, что обработчик TcSoap — процедура без
  результата, а сгенерированная функция исход возвращает. Исход считается:
  неизвестное поле и неразобранное значение — разные вещи, и молчать ни о
  той ни о другой нельзя (R5). }
procedure OnCreatePostField(const Group, Field, Value: TStr); far;
begin
  Inc(Fields);
  case DcdSrv.FillCreatePost(Req, Group, Field, Value) of
    foUnknown:  Inc(Unknowns);
    foBadValue: Inc(BadValues);
  end;
end;

{ ------------------------------------------------------------------
  Документ
  ------------------------------------------------------------------ }

procedure DocClear;
begin
  DocLen := 0;
end;

procedure DocAdd(const S: string);
var
  K: Integer;
begin
  for K := 1 to Length(S) do
  begin
    if DocLen >= SizeOf(Doc) then Exit;
    Doc[DocLen] := S[K];
    Inc(DocLen);
  end;
end;

procedure DocEnvelope(const Body: string);
begin
  DocClear;
  DocAdd('<?xml version="1.0" encoding="UTF-8"?>');
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Body>');
  DocAdd(Body);
  DocAdd('</soap:Body></soap:Envelope>');
end;

procedure StartRead(Fld: TSoapFieldHandler);
begin
  ArenaReset(A);
  Trace := '';
  Overflow := False;
  OpSeen := '';
  Fields := 0;
  SoapReaderInit(Rd, A, OnOp, Fld);
end;

{ Скормить документ кусками по Chunk байт: конверт приходит кадрами, и
  граница куска может лечь где угодно. }
function FeedDoc(Chunk: Word): Boolean;
var
  Pos, N: Word;
begin
  FeedDoc := True;
  Pos := 0;
  while Pos < DocLen do
  begin
    N := DocLen - Pos;
    if N > Chunk then N := Chunk;
    if not SoapFeed(Rd, Doc[Pos], N) then
    begin
      FeedDoc := False;
      Exit;
    end;
    Inc(Pos, N);
  end;
end;

procedure CheckTrace(const Name, Body, Want: string);
begin
  StartRead(OnField);
  DocEnvelope(Body);
  FeedDoc(DocLen);
  R := SoapFinish(Rd);

  if not R.Ok then
  begin
    TestOk(Name, False);
    TestDiag('  отвергнуто: ' + SoapFaultName(SoapFault(Rd)));
    Exit;
  end;
  if Overflow then
  begin
    TestOk(Name, False);
    TestDiag('  след не поместился');
    Exit;
  end;
  TestOk(Name, Trace = Want);
  if Trace <> Want then
  begin
    TestDiag('  получено: ' + Trace);
    TestDiag('  ожидалось: ' + Want);
  end;
end;

procedure CheckFault(const Name, Doc_: string; Want: TSoapFault);
begin
  StartRead(OnField);
  DocClear;
  DocAdd(Doc_);
  FeedDoc(DocLen);
  R := SoapFinish(Rd);

  if R.Ok then
  begin
    TestOk(Name, False);
    TestDiag('  принято, а ожидался отказ ' + SoapFaultName(Want));
    Exit;
  end;
  TestOk(Name, SoapFault(Rd) = Want);
  if SoapFault(Rd) <> Want then
  begin
    TestDiag('  причина: ' + SoapFaultName(SoapFault(Rd)));
    TestDiag('  ожидалась: ' + SoapFaultName(Want));
  end;
end;

{ ------------------------------------------------------------------
  Приёмник кадров писателя
  ------------------------------------------------------------------ }

function Collect(const F: TFrame; More: Boolean): Boolean; far;
var
  K: Word;
begin
  Inc(OutFrames);
  if More then Inc(OutMore) else Inc(OutLast);
  if F.Len > 0 then
    for K := 0 to F.Len - 1 do
    begin
      if OutLen >= SizeOf(Out_) then Break;
      Out_[OutLen] := Chr(F.Payload[K]);
      Inc(OutLen);
    end;
  Collect := True;
end;

function Refuse(const F: TFrame; More: Boolean): Boolean; far;
begin
  Refuse := False;
end;

procedure StartWrite;
begin
  OutLen := 0;
  OutFrames := 0;
  OutMore := 0;
  OutLast := 0;
  SoapWriterInit(W, 7, Collect);
end;

function OutAsString: string;
var
  K: Word;
  S: string;
begin
  S := '';
  K := 0;
  while (K < OutLen) and (Length(S) < 250) do
  begin
    S := S + Out_[K];
    Inc(K);
  end;
  OutAsString := S;
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('конверт SOAP');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('SizeOf(TSoapReader)', SizeOf(TSoapReader));
  TestDiagInt('полезная нагрузка кадра', MaxPayload);

  R := ArenaCreate(A, 16384, 'soap');
  TestResultOk('арена создана', R);

  { ================================================================
    Разбор конверта
    ================================================================ }

  StartRead(OnField);
  DocEnvelope('<createPost/>');
  TestTrue('пустая операция разбирается', FeedDoc(DocLen));
  R := SoapFinish(Rd);
  TestResultOk('пустая операция завершена', R);
  TestEqStr('имя операции получено', OpSeen, 'createPost');
  TestEqInt('полей не было', Fields, 0);

  CheckTrace('поле прямо в операции',
             '<ping><nonce>7</nonce></ping>', 'nonce=7;');

  CheckTrace('группа со скалярами',
             '<createPost><command><postId>p-1</postId>' +
             '<body>x</body></command></createPost>',
             'command.postId=p-1;command.body=x;');

  CheckTrace('две группы подряд',
             '<createPost><meta><traceId>t</traceId></meta>' +
             '<command><postId>p</postId></command></createPost>',
             'meta.traceId=t;command.postId=p;');

  { Заголовок пропускается целиком: трассировка приходит в meta, и
    содержимое заголовка не должно приехать как поля операции. }
  StartRead(OnField);
  DocClear;
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Header><wsse:Security xmlns:wsse="urn:x">' +
         '<user>admin</user></wsse:Security></soap:Header>' +
         '<soap:Body><createPost><command><postId>p</postId>' +
         '</command></createPost></soap:Body></soap:Envelope>');
  FeedDoc(DocLen);
  R := SoapFinish(Rd);
  TestResultOk('конверт с заголовком разбирается', R);
  TestEqStr('из заголовка не приехало ничего',
            Trace, 'command.postId=p;');

  { Префикс пространства имён у операции произволен: CXF ставит ns2,
    другой клиент поставит что угодно. }
  CheckTrace('префикс операции не мешает',
             '<ns2:createPost xmlns:ns2="' + DeciderNs +
             '"><ns2:command><ns2:postId>p</ns2:postId>' +
             '</ns2:command></ns2:createPost>',
             'command.postId=p;');

  { Отступы между элементами не превращаются в значения групп. }
  CheckTrace('отступы не становятся значениями',
             '<createPost> <command> <postId>p</postId> </command> ' +
             '</createPost>',
             'command.postId=p;');

  { Сущности разрешает TcXml, но проверить надо на этом уровне: тело
    поста пишет пользователь, и амперсанд в нём — обычное дело. }
  CheckTrace('сущности в значении разрешены',
             '<createPost><command><body>a&amp;b&lt;c</body>' +
             '</command></createPost>',
             'command.body=a&b<c;');

  CheckTrace('пустое значение поля',
             '<createPost><command><body></body></command></createPost>',
             'command.body=;');

  { ================================================================
    Значение, пришедшее кусками

    Видно только в связке: TcXml режет длинный текст на куски по 240
    байт, а собирает их арена. По отдельности ни один модуль этого
    случая не проходит.
    ================================================================ }

  StartRead(OnField);
  DocClear;
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Body><createPost><command><body>');
  for I := 1 to 1000 do
  begin
    if DocLen >= SizeOf(Doc) then Break;
    Doc[DocLen] := 'x';
    Inc(DocLen);
  end;
  DocAdd('</body></command></createPost></soap:Body></soap:Envelope>');

  { Скармливаем по семь байт: граница куска ложится где попало, в том
    числе посреди имени тега и посреди значения. }
  Overflow := False;
  TestTrue('длинное значение разбирается кусками по семь байт',
           FeedDoc(7));
  R := SoapFinish(Rd);
  TestResultOk('длинное значение завершено', R);
  TestEqInt('поле выдано ровно один раз', Fields, 1);
  TestTrue('след переполнился, значит значение длинное', Overflow);

  { ================================================================
    Отказы
    ================================================================ }

  CheckFault('корень не Envelope', '<html><body/></html>', sfNotEnvelope);
  CheckFault('вместо Body посторонний элемент',
             '<soap:Envelope xmlns:soap="' + SoapNs +
             '"><other/></soap:Envelope>', sfNoBody);
  CheckFault('тело пустое',
             '<soap:Envelope xmlns:soap="' + SoapNs +
             '"><soap:Body></soap:Body></soap:Envelope>', sfNoOperation);
  CheckFault('глубже контракта',
             '<soap:Envelope xmlns:soap="' + SoapNs +
             '"><soap:Body><op><g><f><deeper>x</deeper></f></g></op>' +
             '</soap:Body></soap:Envelope>', sfTooDeep);
  CheckFault('сломанный XML',
             '<soap:Envelope xmlns:soap="' + SoapNs +
             '"><soap:Body><op></soap:Body></soap:Envelope>', sfXml);
  CheckFault('DTD в конверте',
             '<!DOCTYPE soap:Envelope><soap:Envelope/>', sfXml);

  { ================================================================
    Запись ответа
    ================================================================ }

  StartWrite;
  SoapBeginEnvelope(W);
  SoapElementPas(W, 'accepted', 'true');
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);
  TestResultOk('короткий ответ пишется', R);
  TestEqInt('короткий ответ уместился в один кадр', OutFrames, 1);
  TestEqInt('кадров с продолжением не было', OutMore, 0);
  TestEqInt('последний кадр ровно один', OutLast, 1);
  TestTrue('ответ начинается объявлением',
           Copy(OutAsString, 1, 5) = '<?xml');

  { Экранирование. Тело поста пишет пользователь, и неэкранированный
    амперсанд сделал бы ответ неразбираемым. }
  StartWrite;
  SoapBeginEnvelope(W);
  { Обёртка обязательна: элемент прямо в теле — это операция, а не поле.
    Первая редакция теста писала значение на уровень операции и получала
    пустой след, потому что полей в конверте не было вовсе. }
  SoapOpen(W, 'resp');
  SoapElementPas(W, 'v', 'a&b<c>d');
  SoapClose(W, 'resp');
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);
  TestResultOk('ответ с опасными символами пишется', R);
  StartRead(OnField);
  DocClear;
  for I := 0 to OutLen - 1 do
  begin
    Doc[DocLen] := Out_[I];
    Inc(DocLen);
  end;
  FeedDoc(DocLen);
  R := SoapFinish(Rd);
  TestResultOk('записанный ответ разбирается обратно', R);
  TestEqStr('экранирование не потеряло символов', Trace, 'v=a&b<c>d;');

  { Незакрытый элемент — дефект того, кто писал ответ. Отправить такое
    значило бы послать клиенту неразбираемый конверт. }
  StartWrite;
  SoapBeginEnvelope(W);
  SoapOpen(W, 'oops');
  R := SoapWriterFlush(W);
  TestResultErr('незакрытый элемент не отправляется', R, ERR_DECIDER_PANIC);

  { Приёмник, который отказал, не должен остаться незамеченным. }
  OutLen := 0; OutFrames := 0; OutMore := 0; OutLast := 0;
  SoapWriterInit(W, 7, Refuse);
  SoapBeginEnvelope(W);
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);
  TestResultErr('отказ приёмника доходит до вызывающего',
                R, ERR_DECIDER_UNAVAILABLE);
  TestTrue('писатель помечен сломанным', SoapWriterFailed(W));

  { ================================================================
    Ответ, не поместившийся в кадр
    ================================================================ }

  StartWrite;
  SoapBeginEnvelope(W);
  SoapOpen(W, 'body');
  for I := 1 to 2000 do
    SoapTextPas(W, 'x');
  SoapClose(W, 'body');
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);

  TestResultOk('длинный ответ пишется', R);
  TestTrue('длинный ответ занял несколько кадров', OutFrames > 1);
  TestEqInt('последний кадр ровно один', OutLast, 1);
  TestEqInt('остальные помечены продолжением', OutMore, OutFrames - 1);
  TestTrue('байт вышло больше двух тысяч', OutLen > 2000);
  TestDiagInt('кадров на длинном ответе', OutFrames);

  { И он разбирается обратно целиком. }
  StartRead(OnField);
  DocLen := 0;
  for I := 0 to OutLen - 1 do
  begin
    if DocLen >= SizeOf(Doc) then Break;
    Doc[DocLen] := Out_[I];
    Inc(DocLen);
  end;
  TestTrue('длинный ответ помещается в буфер разбора', DocLen = OutLen);
  Overflow := False;
  FeedDoc(64);
  R := SoapFinish(Rd);
  TestResultOk('длинный ответ разбирается обратно', R);

  { ================================================================
    ГЕЙТ Ф5: полный круг

    Конверт с командой приходит кусками, ядро принимает решение, ответ
    уходит кадрами, и разбирается обратно. Ни один модуль до этого места
    не проверялся вместе с остальными.
    ================================================================ }

  ArenaReset(A);
  FillChar(Req, SizeOf(Req), 0);
  Trace := ''; Overflow := False; OpSeen := ''; Fields := 0;
  Unknowns := 0; BadValues := 0;
  SoapReaderInit(Rd, A, OnOp, OnCreatePostField);

  { Документ собирается кусками, а не одной конкатенацией: string в
    диалекте TP не длиннее 255 байт и обрезается МОЛЧА. Первая редакция
    этого теста так и сделала, получила усечённый конверт и отказ
    MALFORMED_ENVELOPE — на ровном месте и не там, где искал. }
  DocClear;
  DocAdd('<?xml version="1.0" encoding="UTF-8"?>');
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Body>');
  DocAdd('<ns2:createPost xmlns:ns2="');
  DocAdd(DeciderNs);
  DocAdd('">');
  DocAdd('<meta><traceId>t-1</traceId><commandId>c-1</commandId></meta>');
  DocAdd('<command><postId>p-1001</postId>');
  DocAdd('<body>Первый пост &amp; последний</body></command>');
  DocAdd('<actor><userId>u-andrey</userId><role>USER</role>');
  DocAdd('<status>ACTIVE</status></actor>');
  DocAdd('</ns2:createPost>');
  DocAdd('</soap:Body></soap:Envelope>');

  TestTrue('команда пришла кусками по девять байт', FeedDoc(9));
  R := SoapFinish(Rd);
  TestResultOk('команда разобрана', R);
  TestEqStr('операция опознана', OpSeen, 'createPost');
  TestEqInt('все поля команды известны генератору', Unknowns, 0);
  TestEqInt('все значения разобрались', BadValues, 0);

  TestEqStr('идентификатор поста дошёл', StrHead(Req.command.postId), 'p-1001');
  TestEqStr('тело дошло с разрешённой сущностью',
            StrHead(Req.command.body), 'Первый пост & последний');
  TestEqStr('автор дошёл', StrHead(Req.actor.userId), 'u-andrey');
  TestEqInt('роль разобрана', Ord(Req.actor.role), Ord(Role_USER));
  TestEqInt('статус разобран', Ord(Req.actor.status), Ord(UserStatus_ACTIVE));

  { Решение принимает ядро. Арена под события — та же, что под значения:
    значения уже собраны, и наращиваемых строк в полёте нет. }
  R := DecideCreatePost(A, Req, D);
  TestResultOk('ядро вынесло решение', R);
  TestTrue('команда принята', D.accepted);
  TestTrue('решение породило событие', D.event <> nil);

  { Ответ. }
  StartWrite;
  SoapBeginEnvelope(W);
  WriteDecision(W, 'createPostResponse', D);
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);
  TestResultOk('ответ записан сгенерированным кодом', R);

  { И разбирается обратно — уже другим разборщиком, как это сделал бы
    гейтвей на своей стороне. }
  ArenaReset(A);
  Trace := ''; Overflow := False; OpSeen := ''; Fields := 0;
  SoapReaderInit(Rd, A, OnOp, OnField);
  DocLen := 0;
  for I := 0 to OutLen - 1 do
  begin
    if DocLen >= SizeOf(Doc) then Break;
    Doc[DocLen] := Out_[I];
    Inc(DocLen);
  end;
  FeedDoc(11);
  R := SoapFinish(Rd);

  TestResultOk('ответ разобран обратно', R);
  TestEqStr('операция ответа опознана', OpSeen, 'createPostResponse');
  TestEqStr('решение и событие дошли целиком', Trace,
            'accepted=true;errorCode=;errorDetail=;' +
            'event.type=post.created;event.aggregateId=p-1001;');

  { Неизвестное поле и негодное значение — разные вещи, и генератор
    обязан их различать. Молча оставить в записи ноль значило бы решить
    по неполным данным (R5). }
  ArenaReset(A);
  FillChar(Req, SizeOf(Req), 0);
  Fields := 0; Unknowns := 0; BadValues := 0;
  SoapReaderInit(Rd, A, OnOp, OnCreatePostField);
  DocClear;
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Body><createPost>');
  DocAdd('<actor><userId>u-a</userId><role>КОРОЛЬ</role>');
  DocAdd('<postsLastHour>не число</postsLastHour>');
  DocAdd('<неведомое>x</неведомое></actor>');
  DocAdd('</createPost></soap:Body></soap:Envelope>');
  FeedDoc(DocLen);
  R := SoapFinish(Rd);
  TestResultOk('конверт с негодными полями всё же разобран', R);
  TestEqInt('неизвестное поле посчитано отдельно', Unknowns, 1);
  TestEqInt('негодные значения посчитаны отдельно', BadValues, 2);
  TestEqInt('годное поле при этом дошло', Req.actor.userId.Len, 3);

  { Круг с отказом: ядро отвергает, и код отказа доезжает до клиента. }
  ArenaReset(A);
  FillChar(Req, SizeOf(Req), 0);
  Fields := 0; Unknowns := 0; BadValues := 0;
  SoapReaderInit(Rd, A, OnOp, OnCreatePostField);
  DocClear;
  DocAdd('<soap:Envelope xmlns:soap="');
  DocAdd(SoapNs);
  DocAdd('"><soap:Body><createPost>');
  DocAdd('<meta><traceId>t-2</traceId><commandId>c-2</commandId></meta>');
  DocAdd('<command><postId>p-2</postId><body>   </body></command>');
  DocAdd('<actor><userId>u-a</userId><role>USER</role>');
  DocAdd('<status>ACTIVE</status></actor>');
  DocAdd('</createPost></soap:Body></soap:Envelope>');
  FeedDoc(DocLen);
  R := SoapFinish(Rd);
  TestResultOk('команда с пустым телом разобрана', R);

  R := DecideCreatePost(A, Req, D);
  TestResultOk('ядро вынесло решение по пустому телу', R);
  TestFalse('команда отвергнута', D.accepted);
  TestEqStr('код отказа тот, что ожидается',
            StrHead(D.errorCode), 'POST_BODY_EMPTY');

  StartWrite;
  SoapBeginEnvelope(W);
  WriteDecision(W, 'createPostResponse', D);
  SoapEndEnvelope(W);
  R := SoapWriterFlush(W);
  TestResultOk('ответ с отказом записан', R);

  ArenaReset(A);
  Trace := ''; Overflow := False; OpSeen := '';
  SoapReaderInit(Rd, A, OnOp, OnField);
  DocLen := 0;
  for I := 0 to OutLen - 1 do
  begin
    if DocLen >= SizeOf(Doc) then Break;
    Doc[DocLen] := Out_[I];
    Inc(DocLen);
  end;
  FeedDoc(DocLen);
  R := SoapFinish(Rd);
  TestResultOk('ответ с отказом разобран', R);
  TestEqStr('код отказа доехал до клиента', Trace,
            'accepted=false;errorCode=POST_BODY_EMPTY;errorDetail=;');

  Halt(TestEnd);
end.
