{ ===================================================================
  Эталонный конверт: разбирает ли ядро то, что на самом деле шлёт Java.

  Обе стороны contract-first порождаются из одного WSDL, но «порождаются
  из одного» и «понимают друг друга» — разные утверждения, и первое не
  влечёт второго. Разойтись они могут на порядке элементов, на префиксах,
  на том, как JAXB решит записать перечисление или число.

  Поэтому здесь читается НЕ СОЧИНЁННЫЙ конверт, а файл, который записал
  маршалер JAXB на настоящих сгенерированных типах:

      contracts/generated/envelopes/create-post.xml

  Он закоммичен, и тест на стороне Java падает, если конверт изменился.
  Так вопрос «а поймут ли друг друга?» перестаёт быть вопросом веры.

  ПЕРВАЯ ЖЕ ПРОВЕРКА УЖЕ НАШЛА РАСХОЖДЕНИЕ. Рукописный конверт в tstsoap
  назывался <createPost>, а CXF шлёт <CreatePostRequest> — имя элемента из
  WSDL, а не имя операции. Ядро, настроенное на первое, не поняло бы ни
  одной настоящей команды.
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
  GoldFile  = 'CRPOST.XML';
{$ELSE}
  TapFile   = 'tstgold.tap';
  GoldFile  = 'create-post.xml';
{$ENDIF}

  PlannedTests = 16;

var
  A: TArena;
  R: TResult;
  Rd: TSoapReader;
  Req: TCreatePostRequest;

  Buf: array[0..4095] of Byte;
  Len: Word;

  OpSeen: string;
  Fields, Unknowns, BadValues: Integer;

{ Обработчики. far обязателен: в модели large переменная процедурного
  типа — дальний указатель. }
procedure OnOp(const Op: TStr); far;
begin
  OpSeen := StrHead(Op);
end;

procedure OnField(const Group, Field, Value: TStr); far;
begin
  Inc(Fields);
  case DcdSrv.FillCreatePost(Req, Group, Field, Value) of
    foUnknown:  Inc(Unknowns);
    foBadValue: Inc(BadValues);
  end;
end;

{ Прочитать эталон целиком. Возвращает False, если файла нет: это не
  «тест провалился», а «эталон не подложили», и различать эти исходы
  важнее, чем кажется. }
function ReadGold: Boolean;
var
  F: file;
  Got: Word;
begin
  ReadGold := False;
  Len := 0;
{$I-}
  Assign(F, GoldFile);
  Reset(F, 1);
{$I+}
  if IOResult <> 0 then
    Exit;
  BlockRead(F, Buf, SizeOf(Buf), Got);
  Close(F);
  Len := Got;
  ReadGold := Got > 0;
end;

var
  I, Chunk: Word;
  Fed: Boolean;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('эталонный конверт из Java');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}

  R := ArenaCreate(A, 16384, 'gold');
  TestResultOk('арена создана', R);

  TestTrue('эталон прочитан', ReadGold);
  TestTrue('эталон непустой', Len > 100);
  TestDiagInt('байт в эталоне', Len);

  FillChar(Req, SizeOf(Req), 0);
  OpSeen := '';
  Fields := 0;
  Unknowns := 0;
  BadValues := 0;
  SoapReaderInit(Rd, A, OnOp, OnField);

  { Кусками по тринадцать байт: по линии конверт приедет кадрами, и
    граница куска ляжет где попало — в том числе посреди кириллической
    буквы, которая занимает два байта. }
  Fed := True;
  Chunk := 13;
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

  TestTrue('эталон скормлен разборщику без отказа', Fed);
  R := SoapFinish(Rd);
  TestResultOk('эталон разобран целиком', R);

  { Имя операции — то, что реально стоит в конверте. Ядро выбирает по нему
    разбор, и ошибка здесь означала бы, что не понята ни одна команда. }
  TestEqStr('имя операции из настоящего конверта',
            OpSeen, 'CreatePostRequest');

  TestEqInt('неизвестных полей нет', Unknowns, 0);
  TestEqInt('негодных значений нет', BadValues, 0);
  TestTrue('полей разобрано достаточно', Fields >= 10);
  TestDiagInt('полей в конверте', Fields);

  TestEqStr('идентификатор поста', StrHead(Req.command.postId), 'p-1001');

  { Кириллица и амперсанд: первое проверяет кодировку, второе —
    разрешение сущностей. И то и другое пишет пользователь. }
  TestEqStr('тело с кириллицей и амперсандом',
            StrHead(Req.command.body), 'Первый пост & последний');

  TestEqStr('идентификатор автора', StrHead(Req.actor.userId), 'u-andrey');
  TestEqInt('роль разобрана', Ord(Req.actor.role), Ord(Role_USER));
  TestEqInt('статус разобран', Ord(Req.actor.status), Ord(UserStatus_ACTIVE));
  TestEqInt('счётчик постов разобран', Req.actor.postsLastHour, 0);
  TestEqStr('идентификатор трассировки', StrHead(Req.meta.traceId), 't-1');

  ArenaDestroy(A);
  Halt(TestEnd);
end.
