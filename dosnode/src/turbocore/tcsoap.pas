{ ===================================================================
  TurboCore · конверт SOAP.

  Тонкий слой поверх TcXml. Разбор XML здесь не повторяется: этот модуль
  знает только про форму конверта — Envelope, Body, элемент операции и
  два уровня под ним.

  ДВА УРОВНЯ, И ЭТО НЕ УПРОЩЕНИЕ, А КОНТРАКТ. Каждая операция в
  decider-v1.wsdl объявляет группы (meta, command, actor, post) из
  скалярных полей. Глубже вложенности нет ни в одной. Разборщик, готовый
  к произвольной глубине, был бы честнее выглядящим и ровно так же
  неспособным отдать данные наверх: у приложения нет типа под
  произвольное дерево.

  ЗАГОЛОВОК ПРОПУСКАЕТСЯ. soap:Header в контракте не несёт ничего, что
  нужно ядру: трассировка приходит в meta. Пропускать надо явно, иначе
  его содержимое приехало бы в приложение как поля операции.

  ТЕКУЩИЙ РАЗБОРЩИК ХРАНИТСЯ В ПЕРЕМЕННОЙ МОДУЛЯ. Обработчик TcXml —
  обычная процедура без контекста, а разборщиков одновременно
  шестнадцать. Это работает ровно потому, что планирование
  кооперативное: байты скармливаются в цикле событий, синхронно, и
  переключения контекста внутри разбора не происходит. Условие записано
  здесь и проверяется тестом.

  ЗНАЧЕНИЯ ПОЛЕЙ СОБИРАЮТСЯ В АРЕНЕ. Текст приходит кусками, а тело
  поста бывает в четыре килобайта: фиксированного буфера под него нет.
  Строка растёт прямо в арене (TcArena, TStrBuilder), поэтому пока поле
  собирается, из арены никто больше не выделяет.

  ОТВЕТ ПИШЕТСЯ СРАЗУ В КАДРЫ. Конверт решения не помещается в один
  кадр, и накапливать его целиком незачем: байты уходят в полезную
  нагрузку, а по её заполнении кадр отправляется с FlagMore.
  =================================================================== }
unit TcSoap;

{$MODE TP}
{$R-}

interface

uses
  TcResult, TcStr, TcArena, TcFrame, TcXml;

const
  { Пространства имён. Первое задано спецификацией SOAP 1.1, второе —
    наше, из decider-v1.wsdl. }
  SoapNs   = 'http://schemas.xmlsoap.org/soap/envelope/';
  DeciderNs = 'urn:sonder:decider:v1';

  { Глубины элементов конверта. Названы, а не вписаны числами: разница
    между «4» и «уровень группы» видна только со схемой в руках. }
  DepthEnvelope  = 1;
  DepthBody      = 2;
  DepthOperation = 3;
  DepthGroup     = 4;
  DepthField     = 5;

type
  TSoapFault = (
    sfNone,
    sfXml,           { сам XML не разобрался, подробность в TXmlFault }
    sfNotEnvelope,   { корень не soap:Envelope }
    sfNoBody,        { тела не было }
    sfTooDeep,       { глубже, чем допускает контракт }
    sfNoOperation,   { тело пустое }
    sfArena          { не хватило арены под значение поля }
  );

  { Начало операции: локальное имя без префикса. Приложение по нему
    выбирает разбор и заводит нужную запись. }
  TSoapOpHandler = procedure(const Op: TStr);

  { Поле. Group — локальное имя объемлющей группы; для поля прямо в
    операции пусто. }
  TSoapFieldHandler = procedure(const Group, Field, Value: TStr);

  TSoapName = record
    Chars: array[0..MaxXmlName] of Char;
    Len:   Byte;
  end;

  TSoapReader = record
    Xml:      TXmlParser;
    Arena:    PArena;

    InBody:   Boolean;
    InHeader: Boolean;
    SeenOp:   Boolean;

    Op:       TSoapName;
    Group:    TSoapName;
    Field:    TSoapName;

    Build:    TStrBuilder;
    Building: Boolean;
    SawChild: Boolean;   { у элемента глубины 4 оказался вложенный }

    OnOp:     TSoapOpHandler;
    OnField:  TSoapFieldHandler;
    Fault:    TSoapFault;
  end;

  PSoapReader = ^TSoapReader;

{ Приготовить разборщик. Арена обязана пережить все выданные значения:
  они смотрят внутрь неё. }
procedure SoapReaderInit(var R: TSoapReader; var A: TArena;
                         OnOp: TSoapOpHandler; OnField: TSoapFieldHandler);

{ Скормить кусок конверта. False — разбор отвергнут. }
function SoapFeed(var R: TSoapReader; const Buf; Len: Word): Boolean;

{ Конверт кончился. }
function SoapFinish(var R: TSoapReader): TResult;

function SoapFault(const R: TSoapReader): TSoapFault;
function SoapFaultName(F: TSoapFault): string;
function SoapOperation(const R: TSoapReader): TStr;

{ ------------------------------------------------------------------
  Запись ответа
  ------------------------------------------------------------------ }

type
  { Куда уходит заполненный кадр. Отдельный тип, а не прямой вызов
    мультиплексора: так писателя можно проверить без транспорта, а
    заодно он не тянет за собой tcmux. }
  TSoapSink = function(const F: TFrame; More: Boolean): Boolean;

  TSoapWriter = record
    Frame:  TFrame;
    Sink:   TSoapSink;
    Chan:   Byte;
    Depth:  Integer;
    Frames: LongInt;
    Bytes:  LongInt;
    Failed: Boolean;
  end;

procedure SoapWriterInit(var W: TSoapWriter; Chan: Byte; S: TSoapSink);

{ Конверт целиком: Envelope и Body открываются и закрываются парой. }
procedure SoapBeginEnvelope(var W: TSoapWriter);
procedure SoapEndEnvelope(var W: TSoapWriter);

procedure SoapOpen(var W: TSoapWriter; const Name: string);

{ Открыть элемент, объявив на нём пространство имён по умолчанию.

  Нужно ровно одному элементу — корню тела ответа, — и без него ответ
  разбирается на той стороне в пустоту: связыватель ищет поля в
  объявленном пространстве, а находит их без пространства вовсе.
  Стоило это целого сквозного прогона: решение приезжало «не принято»
  без кода отказа. }
procedure SoapOpenNs(var W: TSoapWriter; const Name, Ns: string);

procedure SoapClose(var W: TSoapWriter; const Name: string);

{ Текст с экранированием. Неэкранированный амперсанд в теле поста сделал
  бы ответ неразбираемым — а тело поста пишет пользователь. }
procedure SoapText(var W: TSoapWriter; const S: TStr);
procedure SoapTextPas(var W: TSoapWriter; const S: string);

procedure SoapElement(var W: TSoapWriter; const Name: string; const S: TStr);
procedure SoapElementPas(var W: TSoapWriter; const Name, Value: string);
procedure SoapElementBool(var W: TSoapWriter; const Name: string; V: Boolean);
procedure SoapElementInt(var W: TSoapWriter; const Name: string; V: LongInt);

{ Отправить остаток. Пока не вызван, последний кадр не ушёл. }
function SoapWriterFlush(var W: TSoapWriter): TResult;

function SoapWriterFailed(const W: TSoapWriter): Boolean;

implementation

var
  { Разборщик, которому адресованы события TcXml. См. шапку модуля:
    единственность обеспечена кооперативностью, а не удачей. }
  Current: PSoapReader;

{ ------------------------------------------------------------------
  Имена
  ------------------------------------------------------------------ }

procedure NameSet(var N: TSoapName; const S: TStr);
var
  I: Word;
begin
  N.Len := 0;
  if S.Len = 0 then Exit;
  for I := 0 to S.Len - 1 do
  begin
    if N.Len > MaxXmlName then Exit;
    N.Chars[N.Len] := S.Ptr[I];
    Inc(N.Len);
  end;
end;

procedure NameClear(var N: TSoapName);
begin
  N.Len := 0;
end;

function NameView(const N: TSoapName): TStr;
var
  R: TStr;
begin
  R.Ptr := PChar(@N.Chars[0]);
  R.Len := N.Len;
  NameView := R;
end;

function NameIs(const S: TStr; const Want: string): Boolean;
var
  I: Byte;
  L: TStr;
begin
  NameIs := False;
  L := XmlLocalName(S);
  if L.Len <> Word(Length(Want)) then Exit;
  for I := 1 to Length(Want) do
    if L.Ptr[I - 1] <> Want[I] then Exit;
  NameIs := True;
end;

{ ------------------------------------------------------------------
  Разбор
  ------------------------------------------------------------------ }

procedure ReaderFail(var R: TSoapReader; F: TSoapFault);
begin
  if R.Fault = sfNone then
    R.Fault := F;
end;

{ Дописать кусок текста в собираемое значение. }
procedure Accumulate(var R: TSoapReader; const V: TStr);
var
  Rr: TResult;
begin
  if R.Arena = nil then Exit;

  if not R.Building then
  begin
    Rr := BuildBegin(R.Arena^, R.Build);
    if not Rr.Ok then
    begin
      ReaderFail(R, sfArena);
      Exit;
    end;
    R.Building := True;
  end;

  if V.Len = 0 then Exit;
  Rr := BuildAppend(R.Arena^, R.Build, Pointer(V.Ptr), V.Len);
  if not Rr.Ok then
    ReaderFail(R, sfArena);
end;

{ Закончить значение и отдать приложению. }
procedure EmitField(var R: TSoapReader; const Group, Field: TSoapName);
var
  V: TStr;
  Rr: TResult;
begin
  V := StrNil;
  if R.Building and (R.Arena <> nil) then
  begin
    Rr := BuildFinish(R.Arena^, R.Build, V);
    if not Rr.Ok then
      ReaderFail(R, sfArena);
  end;
  R.Building := False;

  if @R.OnField <> nil then
    R.OnField(NameView(Group), NameView(Field), V);
end;

procedure OnXml(Ev: TXmlEvent; const Name, Value: TStr); far;
var
  R: PSoapReader;
  D: Integer;
begin
  R := Current;
  if R = nil then Exit;
  if R^.Fault <> sfNone then Exit;

  D := XmlDepth(R^.Xml);

  case Ev of

    xeStartElement:
      begin
        case D of
          DepthEnvelope:
            if not NameIs(Name, 'Envelope') then
              ReaderFail(R^, sfNotEnvelope);

          DepthBody:
            begin
              R^.InHeader := NameIs(Name, 'Header');
              R^.InBody := NameIs(Name, 'Body');
              if (not R^.InHeader) and (not R^.InBody) then
                { Ни заголовок, ни тело. Отвергаем: молча пропустить
                  значило бы разобрать неизвестно что. }
                ReaderFail(R^, sfNoBody);
            end;

          DepthOperation:
            if R^.InBody then
            begin
              NameSet(R^.Op, XmlLocalName(Name));
              R^.SeenOp := True;
              NameClear(R^.Group);
              if @R^.OnOp <> nil then
                R^.OnOp(NameView(R^.Op));
            end;

          DepthGroup:
            if R^.InBody then
            begin
              NameSet(R^.Group, XmlLocalName(Name));
              R^.SawChild := False;
              { Значение не начинаем: ещё неизвестно, поле это или
                группа. Узнаем по тому, появится ли вложенный элемент. }
              if R^.Building and (R^.Arena <> nil) then
                BuildCancel(R^.Arena^, R^.Build);
              R^.Building := False;
            end;

          DepthField:
            if R^.InBody then
            begin
              { Вложенный элемент появился — значит уровень выше был
                группой, и накопленное там было отступом. Возвращаем
                арене эти байты. }
              if R^.Building and (R^.Arena <> nil) then
                BuildCancel(R^.Arena^, R^.Build);
              R^.Building := False;
              R^.SawChild := True;
              NameSet(R^.Field, XmlLocalName(Name));
            end;

        else
          if R^.InBody then
            { Глубже контракт не заходит ни в одной операции. }
            ReaderFail(R^, sfTooDeep);
        end;
      end;

    xeText:
      if R^.InBody and ((D = DepthGroup) or (D = DepthField)) then
        Accumulate(R^, Value);

    xeEndElement:
      begin
        case D of
          DepthBody:
            begin
              R^.InHeader := False;
              R^.InBody := False;
            end;

          DepthGroup:
            if R^.InBody then
            begin
              if R^.SawChild then
              begin
                { Это была группа: накопленного текста нет. }
                if R^.Building and (R^.Arena <> nil) then
                  BuildCancel(R^.Arena^, R^.Build);
                R^.Building := False;
              end
              else
              begin
                { Скалярное поле прямо в операции: группы нет. }
                NameClear(R^.Field);
                EmitField(R^, R^.Field, R^.Group);
              end;
              NameClear(R^.Group);
            end;

          DepthField:
            if R^.InBody then
            begin
              EmitField(R^, R^.Group, R^.Field);
              { Группа снова может получить собственный текст — но он
                будет отступом, и следующий вложенный элемент его
                отменит. }
              R^.SawChild := True;
            end;
        end;
      end;

    xeAttribute:
      { Атрибуты конверта — объявления пространств имён и xsi:type.
        Ядру они не нужны: операция определяется именем элемента, а типы
        заданы контрактом. }
      ;
  end;
end;

procedure SoapReaderInit(var R: TSoapReader; var A: TArena;
                         OnOp: TSoapOpHandler; OnField: TSoapFieldHandler);
begin
  FillChar(R, SizeOf(R), 0);
  R.Arena := @A;
  R.OnOp := OnOp;
  R.OnField := OnField;
  R.Fault := sfNone;
  R.InBody := False;
  R.InHeader := False;
  R.SeenOp := False;
  R.Building := False;
  R.SawChild := False;
  XmlReset(R.Xml, OnXml);
end;

function SoapFeed(var R: TSoapReader; const Buf; Len: Word): Boolean;
var
  P: PByte;
  I: Word;
  Save: PSoapReader;
begin
  SoapFeed := True;
  if Len = 0 then Exit;

  { Сохранение и восстановление, а не просто присваивание: так вложенный
    вызов — если он когда-нибудь появится — не потеряет внешний
    разборщик молча. }
  Save := Current;
  Current := @R;

  P := PByte(@Buf);
  for I := 1 to Len do
  begin
    if not XmlFeed(R.Xml, P^) then
    begin
      ReaderFail(R, sfXml);
      SoapFeed := False;
      Break;
    end;
    if R.Fault <> sfNone then
    begin
      SoapFeed := False;
      Break;
    end;
    Inc(P);
  end;

  Current := Save;
end;

function SoapFinish(var R: TSoapReader): TResult;
var
  Rr: TResult;
  Save: PSoapReader;
begin
  Save := Current;
  Current := @R;
  Rr := XmlFinish(R.Xml);
  Current := Save;

  if R.Fault <> sfNone then
  begin
    SoapFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;
  if not Rr.Ok then
  begin
    ReaderFail(R, sfXml);
    SoapFinish := Rr;
    Exit;
  end;
  if not R.SeenOp then
  begin
    ReaderFail(R, sfNoOperation);
    SoapFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;
  SoapFinish := Ok;
end;

function SoapFault(const R: TSoapReader): TSoapFault;
begin
  SoapFault := R.Fault;
end;

function SoapFaultName(F: TSoapFault): string;
begin
  case F of
    sfNone:        SoapFaultName := 'none';
    sfXml:         SoapFaultName := 'xml';
    sfNotEnvelope: SoapFaultName := 'not-envelope';
    sfNoBody:      SoapFaultName := 'no-body';
    sfTooDeep:     SoapFaultName := 'too-deep';
    sfNoOperation: SoapFaultName := 'no-operation';
    sfArena:       SoapFaultName := 'arena';
  else
    SoapFaultName := 'unknown';
  end;
end;

function SoapOperation(const R: TSoapReader): TStr;
begin
  SoapOperation := NameView(R.Op);
end;

{ ------------------------------------------------------------------
  Запись
  ------------------------------------------------------------------ }

procedure SoapWriterInit(var W: TSoapWriter; Chan: Byte; S: TSoapSink);
begin
  FillChar(W, SizeOf(W), 0);
  W.Sink := S;
  W.Chan := Chan;
  W.Frame.Channel := Chan;
  W.Frame.Len := 0;
  W.Depth := 0;
  W.Failed := False;
end;

{ Отправить накопленное. More означает, что сообщение продолжается. }
procedure Emit(var W: TSoapWriter; More: Boolean);
begin
  if W.Failed then Exit;
  W.Frame.Channel := W.Chan;
  if @W.Sink = nil then
  begin
    W.Failed := True;
    Exit;
  end;
  if not W.Sink(W.Frame, More) then
  begin
    W.Failed := True;
    Exit;
  end;
  Inc(W.Frames);
  W.Frame.Len := 0;
end;

procedure PutByte(var W: TSoapWriter; B: Byte);
begin
  if W.Failed then Exit;
  W.Frame.Payload[W.Frame.Len] := B;
  Inc(W.Frame.Len);
  Inc(W.Bytes);
  if W.Frame.Len >= MaxPayload then
    Emit(W, True);
end;

procedure PutPas(var W: TSoapWriter; const S: string);
var
  I: Integer;
begin
  for I := 1 to Length(S) do
    PutByte(W, Byte(S[I]));
end;

{ Экранирование. Кавычки в тексте экранировать не нужно — они значат
  что-то только внутри значения атрибута, а атрибуты мы пишем сами. }
procedure PutEscaped(var W: TSoapWriter; B: Byte);
begin
  case Chr(B) of
    '&': PutPas(W, '&amp;');
    '<': PutPas(W, '&lt;');
    '>': PutPas(W, '&gt;');
  else
    PutByte(W, B);
  end;
end;

procedure SoapText(var W: TSoapWriter; const S: TStr);
var
  I: Word;
begin
  if S.Len = 0 then Exit;
  for I := 0 to S.Len - 1 do
    PutEscaped(W, Byte(S.Ptr[I]));
end;

procedure SoapTextPas(var W: TSoapWriter; const S: string);
var
  I: Integer;
begin
  for I := 1 to Length(S) do
    PutEscaped(W, Byte(S[I]));
end;

procedure SoapOpen(var W: TSoapWriter; const Name: string);
begin
  PutByte(W, Ord('<'));
  PutPas(W, Name);
  PutByte(W, Ord('>'));
  Inc(W.Depth);
end;

procedure SoapOpenNs(var W: TSoapWriter; const Name, Ns: string);
begin
  PutByte(W, Ord('<'));
  PutPas(W, Name);
  PutPas(W, ' xmlns="');
  PutPas(W, Ns);
  PutPas(W, '">');
  Inc(W.Depth);
end;

procedure SoapClose(var W: TSoapWriter; const Name: string);
begin
  PutPas(W, '</');
  PutPas(W, Name);
  PutByte(W, Ord('>'));
  Dec(W.Depth);
end;

procedure SoapBeginEnvelope(var W: TSoapWriter);
begin
  PutPas(W, '<?xml version="1.0" encoding="UTF-8"?>');
  PutPas(W, '<soap:Envelope xmlns:soap="');
  PutPas(W, SoapNs);
  PutPas(W, '">');
  Inc(W.Depth);
  PutPas(W, '<soap:Body>');
  Inc(W.Depth);
end;

procedure SoapEndEnvelope(var W: TSoapWriter);
begin
  PutPas(W, '</soap:Body>');
  Dec(W.Depth);
  PutPas(W, '</soap:Envelope>');
  Dec(W.Depth);
end;

procedure SoapElement(var W: TSoapWriter; const Name: string; const S: TStr);
begin
  SoapOpen(W, Name);
  SoapText(W, S);
  SoapClose(W, Name);
end;

procedure SoapElementPas(var W: TSoapWriter; const Name, Value: string);
begin
  SoapOpen(W, Name);
  SoapTextPas(W, Value);
  SoapClose(W, Name);
end;

procedure SoapElementBool(var W: TSoapWriter; const Name: string; V: Boolean);
begin
  if V then
    SoapElementPas(W, Name, 'true')
  else
    SoapElementPas(W, Name, 'false');
end;

procedure SoapElementInt(var W: TSoapWriter; const Name: string; V: LongInt);
var
  S: string;
begin
  Str(V, S);
  SoapElementPas(W, Name, S);
end;

function SoapWriterFlush(var W: TSoapWriter): TResult;
begin
  if W.Failed then
  begin
    SoapWriterFlush := Err('DECIDER_UNAVAILABLE');
    Exit;
  end;
  if W.Depth <> 0 then
  begin
    { Незакрытый элемент означает дефект в том, кто писал ответ.
      Отправить такое значило бы послать клиенту неразбираемый конверт. }
    W.Failed := True;
    SoapWriterFlush := Err('DECIDER_PANIC');
    Exit;
  end;

  { Последний кадр уходит даже пустым: приёмнику нужен кадр без FlagMore,
    чтобы понять, что сообщение кончилось. }
  Emit(W, False);

  if W.Failed then
    SoapWriterFlush := Err('DECIDER_UNAVAILABLE')
  else
    SoapWriterFlush := Ok;
end;

function SoapWriterFailed(const W: TSoapWriter): Boolean;
begin
  SoapWriterFailed := W.Failed;
end;

begin
  Current := nil;
end.
