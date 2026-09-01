{ ===================================================================
  TurboCore · потоковый разбор XML.

  Разборщик принимает байты по одному и вызывает обработчик на каждом
  событии. DOM здесь невозможен и не нужен: конверт с телом поста на
  тысячу символов — это несколько килобайт, которые пришли бы несколькими
  кадрами, и держать дерево в памяти незачем, когда его всё равно тут же
  разбирают в запись.

  ПОДДЕРЖИВАЕТСЯ РОВНО ТО, ЧТО ПОРОЖДАЕТ CXF: объявление, элементы с
  префиксами пространств имён, атрибуты, текст, пустые элементы,
  предопределённые сущности и числовые ссылки, комментарии, CDATA.

  DTD ОТВЕРГАЕТСЯ, И ЭТО ГЛАВНОЕ РЕШЕНИЕ ЗДЕСЬ. Внешние сущности — это
  чтение произвольных файлов чужими руками, а вложенные — рост объёма в
  разы на каждом уровне (та самая «атака миллиарда смешков»). Ни то ни
  другое не нужно ни одному конверту SOAP. Отказ выносится ГРОМКО:
  пропустить DOCTYPE молча значило бы разобрать документ, смысл которого
  задан невидимой для нас частью.

  ПРЕДЕЛЫ ЯВНЫЕ И КОНЕЧНЫЕ. Глубина, длина имени, длина значения атрибута.
  Разборщик, у которого нет предела, имеет предел — размер стека, и
  находит его в самый неподходящий момент.

  ТЕКСТ ВЫДАЁТСЯ КУСКАМИ. Одному текстовому узлу может соответствовать
  несколько событий xeText подряд: тело поста длиннее любого разумного
  буфера. Так же ведёт себя всякий SAX, и накопление — работа
  потребителя, у которого для этого есть арена команды.

  ПРОБЕЛЫ ВЫДАЮТСЯ КАК ЕСТЬ. Отличить незначащий отступ от значащего
  пробела внутри тела поста разборщик не может: для этого нужно знать
  схему. Решать за потребителя он не вправе, а потерять данные — тем
  более.

  ГДЕ ЖИВЁТ TXmlParser. Запись занимает около 1.4 КБ, из них тысяча —
  стек имён для сверки закрывающих тегов. Шестнадцать одновременных
  команд это 23 КБ, а весь сегмент данных — 64 КБ. Поэтому разборщик
  кладётся В АРЕНУ КОМАНДЫ, в дальней куче, а не в глобальные переменные.
  Модуль ничего не выделяет сам и не навязывает размещения: где положат,
  там и будет работать.
  =================================================================== }
unit TcXml;

{$MODE TP}
{$R-}

interface

uses
  TcResult, TcStr;

const
  { Конверт SOAP — пять-шесть уровней. Шестнадцать оставляют запас и
    при этом делают стек имён конечным: 16 × 64 байта. }
  MaxXmlDepth = 16;

  { Имена в конверте короткие: самое длинное у CXF — ns2:createPostResponse. }
  MaxXmlName = 63;

  { Значение атрибута не разбивается на куски: атрибуты в конверте — это
    объявления пространств имён и xsi:type, все с известным потолком.
    Самый длинный URI пространства имён SOAP — 41 байт. }
  MaxXmlAttrValue = 255;

  { Порог выдачи текстового куска. Ниже размера буфера значения, чтобы
    кодовая точка, дописанная из числовой ссылки, гарантированно влезла. }
  XmlTextChunk = 240;

type
  TXmlEvent = (
    xeStartElement,   { Name — имя с префиксом, Value пусто }
    xeAttribute,      { Name — имя атрибута, Value — значение }
    xeText,           { Name пусто, Value — кусок текста }
    xeEndElement      { Name — имя с префиксом, Value пусто }
  );

  { Почему разбор не дошёл до конца.

    В контракте на все случаи один код, MALFORMED_ENVELOPE: снаружи
    разница неразличима — конверт не разобрался. Внутри она нужна для
    лога, потому что «слишком глубоко» и «отвергнут DTD» требуют разных
    действий от того, кто это читает. }
  TXmlFault = (
    xfNone,
    xfSyntax,          { структура нарушена }
    xfNameTooLong,
    xfValueTooLong,
    xfTooDeep,
    xfMismatchedTag,   { закрывающий тег не тот, что открывающий }
    xfUnclosed,        { поток кончился внутри элемента }
    xfDoctype,         { DTD отвергнут намеренно }
    xfBadEntity,
    xfNoRoot,          { корневого элемента не было вовсе }
    xfTrailingContent  { после корневого элемента ещё что-то }
  );

  { Обработчик событий. Строки живут ровно до возврата из обработчика:
    они смотрят внутрь разборщика, который на следующем байте их
    перепишет. Нужно сохранить — копируйте в арену. }
  TXmlHandler = procedure(Ev: TXmlEvent; const Name, Value: TStr);

  TXmlState = (
    xsProlog,        { до корневого элемента }
    xsText,          { содержимое элемента }
    xsEpilog,        { после корневого элемента }
    xsTagOpen,       { прочитан < }
    xsName,          { имя открывающего тега }
    xsAfterName,     { пробелы перед атрибутами }
    xsAttrName,
    xsAfterAttrName, { ждём = }
    xsBeforeValue,   { ждём кавычку }
    xsAttrValue,
    xsEmptyTag,      { прочитан / внутри тега, ждём > }
    xsEndName,       { имя закрывающего тега }
    xsAfterEndName,  { ждём > }
    xsBang,          { прочитан <! }
    xsCommentOpen,   { прочитан <!-, ждём второй - }
    xsComment,
    xsCDataOpen,     { сверяем [CDATA[ }
    xsCData,
    xsPi,            { <? ... ?> }
    xsEntity,        { &...; }
    xsFailed
  );

  TXmlNameBuf = record
    Chars: array[0..MaxXmlName] of Char;
    Len:   Byte;
  end;

  TXmlParser = record
    State:    TXmlState;
    Ret:      TXmlState;   { куда вернуться после сущности }
    Fault:    TXmlFault;
    Handler:  TXmlHandler;

    Depth:    Integer;
    Stack:    array[1..MaxXmlDepth] of TXmlNameBuf;

    Name:     TXmlNameBuf;   { имя текущего элемента или атрибута }
    Attr:     TXmlNameBuf;
    Val:      array[0..MaxXmlAttrValue] of Char;
    ValLen:   Word;

    Ent:      array[0..15] of Char;
    EntLen:   Byte;

    Quote:    Char;
    Match:    Byte;        { счётчик совпадения для -->, ]]>, ?>, [CDATA[ }
    InAttr:   Boolean;     { сущность встретилась внутри значения атрибута }
    SeenRoot: Boolean;
  end;

{ Разборщик готов к новому документу. Обработчик обязателен. }
procedure XmlReset(var P: TXmlParser; H: TXmlHandler);

{ Скормить байт. False означает, что разбор отвергнут; дальнейшие байты
  игнорируются, а причина лежит в XmlFault. }
function XmlFeed(var P: TXmlParser; B: Byte): Boolean;

{ Документ кончился. Ok только если корень был и все теги закрыты. }
function XmlFinish(var P: TXmlParser): TResult;

function XmlFault(const P: TXmlParser): TXmlFault;
function XmlDepth(const P: TXmlParser): Integer;
function XmlFaultName(F: TXmlFault): string;

{ Имя без префикса пространства имён.

  Префикс выбирает отправитель: CXF ставит ns2, другой клиент поставит
  что угодно. Сверяться с полным именем поэтому хрупко. Но и решать за
  потребителя разборщик не вправе — события несут имя как есть, а это
  вспомогательная функция для тех, кому нужен только локальный кусок. }
function XmlLocalName(const S: TStr): TStr;

implementation

{ ------------------------------------------------------------------
  Классификация байт
  ------------------------------------------------------------------ }

function IsSpace(B: Byte): Boolean;
begin
  IsSpace := (B = 32) or (B = 9) or (B = 13) or (B = 10);
end;

function IsNameStart(B: Byte): Boolean;
begin
  IsNameStart := ((B >= Ord('a')) and (B <= Ord('z'))) or
                 ((B >= Ord('A')) and (B <= Ord('Z'))) or
                 (B = Ord('_')) or (B = Ord(':')) or
                 (B >= $80);
end;

function IsNameChar(B: Byte): Boolean;
begin
  IsNameChar := IsNameStart(B) or
                ((B >= Ord('0')) and (B <= Ord('9'))) or
                (B = Ord('-')) or (B = Ord('.'));
end;

{ ------------------------------------------------------------------
  Вспомогательное
  ------------------------------------------------------------------ }

function Fail(var P: TXmlParser; F: TXmlFault): Boolean;
begin
  { Первая причина сохраняется: последующие байты уже разбираются в
    сломанном состоянии, и их жалобы ничего не объясняют. }
  if P.Fault = xfNone then
    P.Fault := F;
  P.State := xsFailed;
  Fail := False;
end;

function NameView(const N: TXmlNameBuf): TStr;
var
  R: TStr;
begin
  R.Ptr := PChar(@N.Chars[0]);
  R.Len := N.Len;
  NameView := R;
end;

function ValView(var P: TXmlParser): TStr;
var
  R: TStr;
begin
  R.Ptr := PChar(@P.Val[0]);
  R.Len := P.ValLen;
  ValView := R;
end;

procedure NameClear(var N: TXmlNameBuf);
begin
  N.Len := 0;
end;

function NamePush(var N: TXmlNameBuf; B: Byte): Boolean;
begin
  if N.Len > MaxXmlName then
  begin
    NamePush := False;
    Exit;
  end;
  N.Chars[N.Len] := Chr(B);
  Inc(N.Len);
  NamePush := True;
end;

function NameSame(const A, B: TXmlNameBuf): Boolean;
var
  I: Byte;
begin
  NameSame := False;
  if A.Len <> B.Len then Exit;
  if A.Len > 0 then
    for I := 0 to A.Len - 1 do
      if A.Chars[I] <> B.Chars[I] then Exit;
  NameSame := True;
end;

function ValPush(var P: TXmlParser; B: Byte): Boolean;
begin
  if P.ValLen > MaxXmlAttrValue then
  begin
    ValPush := False;
    Exit;
  end;
  P.Val[P.ValLen] := Chr(B);
  Inc(P.ValLen);
  ValPush := True;
end;

procedure Emit(var P: TXmlParser; Ev: TXmlEvent;
               const Nm, Vl: TStr);
begin
  if @P.Handler <> nil then
    P.Handler(Ev, Nm, Vl);
end;

{ Выдать накопленный текст и опустошить буфер. Пустой кусок не выдаётся:
  событие без содержимого потребителю ничего не сообщает. }
procedure FlushText(var P: TXmlParser);
begin
  if P.ValLen = 0 then
    Exit;
  Emit(P, xeText, StrNil, ValView(P));
  P.ValLen := 0;
end;

{ ------------------------------------------------------------------
  Сущности
  ------------------------------------------------------------------ }

{ Кодовая точка в UTF-8. Суррогаты и всё за U+10FFFF отвергаются — теми
  же правилами, что и в StrCharLen: принимать запись, которую сами же
  потом признаем испорченной, было бы непоследовательно. }
function EmitCodepoint(var P: TXmlParser; CP: LongInt): Boolean;
begin
  EmitCodepoint := False;

  if (CP < 0) or (CP > $10FFFF) then Exit;
  if (CP >= $D800) and (CP <= $DFFF) then Exit;

  if CP < $80 then
  begin
    if not ValPush(P, Byte(CP)) then Exit;
  end
  else if CP < $800 then
  begin
    if not ValPush(P, $C0 or Byte(CP shr 6)) then Exit;
    if not ValPush(P, $80 or (Byte(CP) and $3F)) then Exit;
  end
  else if CP < $10000 then
  begin
    if not ValPush(P, $E0 or Byte(CP shr 12)) then Exit;
    if not ValPush(P, $80 or (Byte(CP shr 6) and $3F)) then Exit;
    if not ValPush(P, $80 or (Byte(CP) and $3F)) then Exit;
  end
  else
  begin
    if not ValPush(P, $F0 or Byte(CP shr 18)) then Exit;
    if not ValPush(P, $80 or (Byte(CP shr 12) and $3F)) then Exit;
    if not ValPush(P, $80 or (Byte(CP shr 6) and $3F)) then Exit;
    if not ValPush(P, $80 or (Byte(CP) and $3F)) then Exit;
  end;

  EmitCodepoint := True;
end;

function EntSame(const P: TXmlParser; const S: string): Boolean;
var
  I: Byte;
begin
  EntSame := False;
  if P.EntLen <> Byte(Length(S)) then Exit;
  for I := 1 to Length(S) do
    if P.Ent[I - 1] <> S[I] then Exit;
  EntSame := True;
end;

{ Разрешить накопленную сущность. Своих сущностей нет и быть не может:
  их объявляет DTD, а DTD отвергается. }
function ResolveEntity(var P: TXmlParser): Boolean;
var
  I: Byte;
  CP: LongInt;
  Hex: Boolean;
  D: Byte;
  C: Char;
begin
  ResolveEntity := False;

  if P.EntLen = 0 then Exit;

  if EntSame(P, 'amp')  then begin ResolveEntity := ValPush(P, Ord('&')); Exit; end;
  if EntSame(P, 'lt')   then begin ResolveEntity := ValPush(P, Ord('<')); Exit; end;
  if EntSame(P, 'gt')   then begin ResolveEntity := ValPush(P, Ord('>')); Exit; end;
  if EntSame(P, 'quot') then begin ResolveEntity := ValPush(P, Ord('"')); Exit; end;
  if EntSame(P, 'apos') then begin ResolveEntity := ValPush(P, Ord('''')); Exit; end;

  if P.Ent[0] <> '#' then Exit;

  Hex := (P.EntLen > 1) and ((P.Ent[1] = 'x') or (P.Ent[1] = 'X'));
  if Hex then I := 2 else I := 1;
  if I >= P.EntLen then Exit;   { &#; и &#x; — цифр нет }

  CP := 0;
  while I < P.EntLen do
  begin
    C := P.Ent[I];
    if (C >= '0') and (C <= '9') then
      D := Ord(C) - Ord('0')
    else if Hex and (C >= 'a') and (C <= 'f') then
      D := Ord(C) - Ord('a') + 10
    else if Hex and (C >= 'A') and (C <= 'F') then
      D := Ord(C) - Ord('A') + 10
    else
      Exit;

    if Hex then CP := CP * 16 + D else CP := CP * 10 + D;

    { Проверка на каждом шаге, а не в конце: LongInt переполнился бы
      раньше, чем мы успели заметить слишком большое число. }
    if CP > $10FFFF then Exit;

    Inc(I);
  end;

  ResolveEntity := EmitCodepoint(P, CP);
end;

{ ------------------------------------------------------------------
  Открытие и закрытие элементов
  ------------------------------------------------------------------ }

function OpenElement(var P: TXmlParser): Boolean;
begin
  if P.Depth >= MaxXmlDepth then
  begin
    OpenElement := Fail(P, xfTooDeep);
    Exit;
  end;
  Inc(P.Depth);
  P.Stack[P.Depth] := P.Name;
  P.SeenRoot := True;
  Emit(P, xeStartElement, NameView(P.Name), StrNil);
  OpenElement := True;
end;

{ Закрыть текущий элемент. Name — имя из закрывающего тега; при пустом
  элементе это имя из открывающего, и сверка тривиально проходит. }
function CloseElement(var P: TXmlParser; const Nm: TXmlNameBuf): Boolean;
begin
  if P.Depth <= 0 then
  begin
    CloseElement := Fail(P, xfSyntax);
    Exit;
  end;
  if not NameSame(P.Stack[P.Depth], Nm) then
  begin
    { Несовпадение тегов — не мелочь: оно означает, что документ понят
      не так, как задуман, и молча продолжать нельзя. }
    CloseElement := Fail(P, xfMismatchedTag);
    Exit;
  end;
  Emit(P, xeEndElement, NameView(Nm), StrNil);
  Dec(P.Depth);
  if P.Depth = 0 then
    P.State := xsEpilog
  else
    P.State := xsText;
  CloseElement := True;
end;

{ ------------------------------------------------------------------
  Разбор
  ------------------------------------------------------------------ }

procedure XmlReset(var P: TXmlParser; H: TXmlHandler);
begin
  FillChar(P, SizeOf(P), 0);
  P.State := xsProlog;
  P.Ret := xsProlog;
  P.Fault := xfNone;
  P.Handler := H;
  P.Depth := 0;
  P.ValLen := 0;
  P.SeenRoot := False;
end;

function XmlFeed(var P: TXmlParser; B: Byte): Boolean;
const
  CDataTail = '[CDATA[';
begin
  XmlFeed := True;

  case P.State of

    xsFailed:
      begin
        XmlFeed := False;
        Exit;
      end;

    { --- вне корневого элемента --- }
    xsProlog, xsEpilog:
      begin
        if B = Ord('<') then
        begin
          P.State := xsTagOpen;
          Exit;
        end;
        if IsSpace(B) then
          Exit;
        { Текст вне корня — не документ, а склейка двух документов или
          мусор в линии. }
        if P.State = xsProlog then
          XmlFeed := Fail(P, xfSyntax)
        else
          XmlFeed := Fail(P, xfTrailingContent);
      end;

    { --- содержимое элемента --- }
    xsText:
      begin
        if B = Ord('<') then
        begin
          FlushText(P);
          P.State := xsTagOpen;
          Exit;
        end;
        if B = Ord('&') then
        begin
          P.EntLen := 0;
          P.InAttr := False;
          P.Ret := xsText;
          P.State := xsEntity;
          Exit;
        end;
        if not ValPush(P, B) then
        begin
          XmlFeed := Fail(P, xfValueTooLong);
          Exit;
        end;
        { Кусок выдаётся по достижении порога, а не по заполнении буфера:
          иначе кодовая точка из числовой ссылки могла бы не влезть. }
        if P.ValLen >= XmlTextChunk then
          FlushText(P);
      end;

    { --- прочитан < --- }
    xsTagOpen:
      begin
        if B = Ord('/') then
        begin
          if P.Depth = 0 then
          begin
            { Закрывающий тег вне всякого элемента. Если корень уже был —
              это содержимое после корня, а не абстрактная ошибка
              структуры: причина должна называться самая конкретная из
              верных, иначе лог не подскажет, что чинить. }
            if P.SeenRoot then
              XmlFeed := Fail(P, xfTrailingContent)
            else
              XmlFeed := Fail(P, xfSyntax);
            Exit;
          end;
          NameClear(P.Name);
          P.State := xsEndName;
          Exit;
        end;
        if B = Ord('!') then
        begin
          P.State := xsBang;
          Exit;
        end;
        if B = Ord('?') then
        begin
          P.Match := 0;
          P.State := xsPi;
          Exit;
        end;
        if not IsNameStart(B) then
        begin
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        if P.SeenRoot and (P.Depth = 0) then
        begin
          { Второй корень. }
          XmlFeed := Fail(P, xfTrailingContent);
          Exit;
        end;
        NameClear(P.Name);
        if not NamePush(P.Name, B) then
        begin
          XmlFeed := Fail(P, xfNameTooLong);
          Exit;
        end;
        P.State := xsName;
      end;

    xsName:
      begin
        if IsNameChar(B) then
        begin
          if not NamePush(P.Name, B) then
            XmlFeed := Fail(P, xfNameTooLong);
          Exit;
        end;
        if IsSpace(B) then
        begin
          if not OpenElement(P) then XmlFeed := False
          else P.State := xsAfterName;
          Exit;
        end;
        if B = Ord('>') then
        begin
          if not OpenElement(P) then XmlFeed := False
          else begin P.ValLen := 0; P.State := xsText; end;
          Exit;
        end;
        if B = Ord('/') then
        begin
          if not OpenElement(P) then XmlFeed := False
          else P.State := xsEmptyTag;
          Exit;
        end;
        XmlFeed := Fail(P, xfSyntax);
      end;

    xsAfterName:
      begin
        if IsSpace(B) then Exit;
        if B = Ord('>') then
        begin
          P.ValLen := 0;
          P.State := xsText;
          Exit;
        end;
        if B = Ord('/') then
        begin
          P.State := xsEmptyTag;
          Exit;
        end;
        if not IsNameStart(B) then
        begin
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        NameClear(P.Attr);
        if not NamePush(P.Attr, B) then
        begin
          XmlFeed := Fail(P, xfNameTooLong);
          Exit;
        end;
        P.State := xsAttrName;
      end;

    xsAttrName:
      begin
        if IsNameChar(B) then
        begin
          if not NamePush(P.Attr, B) then
            XmlFeed := Fail(P, xfNameTooLong);
          Exit;
        end;
        if B = Ord('=') then
        begin
          P.State := xsBeforeValue;
          Exit;
        end;
        if IsSpace(B) then
        begin
          P.State := xsAfterAttrName;
          Exit;
        end;
        XmlFeed := Fail(P, xfSyntax);
      end;

    xsAfterAttrName:
      begin
        if IsSpace(B) then Exit;
        if B = Ord('=') then
        begin
          P.State := xsBeforeValue;
          Exit;
        end;
        { Атрибут без значения допустим в HTML, но не в XML. }
        XmlFeed := Fail(P, xfSyntax);
      end;

    xsBeforeValue:
      begin
        if IsSpace(B) then Exit;
        if (B = Ord('"')) or (B = Ord('''')) then
        begin
          P.Quote := Chr(B);
          P.ValLen := 0;
          P.State := xsAttrValue;
          Exit;
        end;
        XmlFeed := Fail(P, xfSyntax);
      end;

    xsAttrValue:
      begin
        if B = Ord(P.Quote) then
        begin
          Emit(P, xeAttribute, NameView(P.Attr), ValView(P));
          P.ValLen := 0;
          P.State := xsAfterName;
          Exit;
        end;
        if B = Ord('&') then
        begin
          P.EntLen := 0;
          P.InAttr := True;
          P.Ret := xsAttrValue;
          P.State := xsEntity;
          Exit;
        end;
        if B = Ord('<') then
        begin
          { Голый < в значении атрибута запрещён спецификацией и почти
            всегда означает потерянную кавычку. }
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        if not ValPush(P, B) then
          XmlFeed := Fail(P, xfValueTooLong);
      end;

    xsEmptyTag:
      begin
        if B <> Ord('>') then
        begin
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        { Пустой элемент закрывается собственным именем: сверка
          тривиальна, но проходит через тот же путь, что и обычная. }
        if not CloseElement(P, P.Stack[P.Depth]) then
          XmlFeed := False
        else
          P.ValLen := 0;
      end;

    xsEndName:
      begin
        if IsNameChar(B) then
        begin
          if not NamePush(P.Name, B) then
            XmlFeed := Fail(P, xfNameTooLong);
          Exit;
        end;
        if B = Ord('>') then
        begin
          if not CloseElement(P, P.Name) then XmlFeed := False
          else P.ValLen := 0;
          Exit;
        end;
        if IsSpace(B) then
        begin
          P.State := xsAfterEndName;
          Exit;
        end;
        XmlFeed := Fail(P, xfSyntax);
      end;

    xsAfterEndName:
      begin
        if IsSpace(B) then Exit;
        if B = Ord('>') then
        begin
          if not CloseElement(P, P.Name) then XmlFeed := False
          else P.ValLen := 0;
          Exit;
        end;
        XmlFeed := Fail(P, xfSyntax);
      end;

    { --- <! --- }
    xsBang:
      begin
        if B = Ord('-') then
        begin
          P.State := xsCommentOpen;
          Exit;
        end;
        if B = Ord('[') then
        begin
          P.Match := 1;   { первый символ [CDATA[ уже сошёлся }
          P.State := xsCDataOpen;
          Exit;
        end;
        { Всё прочее после <! — это DOCTYPE или иная часть DTD.
          Отвергается намеренно и громко. }
        XmlFeed := Fail(P, xfDoctype);
      end;

    xsCommentOpen:
      begin
        if B <> Ord('-') then
        begin
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        P.Match := 0;
        P.State := xsComment;
      end;

    xsComment:
      begin
        if B = Ord('-') then
          Inc(P.Match)
        else if (B = Ord('>')) and (P.Match >= 2) then
        begin
          P.Match := 0;
          if P.Depth > 0 then P.State := xsText
          else if P.SeenRoot then P.State := xsEpilog
          else P.State := xsProlog;
        end
        else
          P.Match := 0;
      end;

    xsCDataOpen:
      begin
        if B <> Ord(CDataTail[P.Match + 1]) then
        begin
          XmlFeed := Fail(P, xfSyntax);
          Exit;
        end;
        Inc(P.Match);
        if P.Match = Byte(Length(CDataTail)) then
        begin
          if P.Depth = 0 then
          begin
            { CDATA вне элемента — это текст вне корня. }
            XmlFeed := Fail(P, xfSyntax);
            Exit;
          end;
          P.Match := 0;
          P.State := xsCData;
        end;
      end;

    xsCData:
      begin
        if B = Ord(']') then
        begin
          Inc(P.Match);
          { Скобки не выдаём сразу: они могут оказаться началом ]]>.
            Больше двух подряд — первая точно принадлежит тексту. }
          if P.Match > 2 then
          begin
            if not ValPush(P, Ord(']')) then
            begin
              XmlFeed := Fail(P, xfValueTooLong);
              Exit;
            end;
            P.Match := 2;
          end;
          Exit;
        end;
        if (B = Ord('>')) and (P.Match >= 2) then
        begin
          P.Match := 0;
          P.State := xsText;
          Exit;
        end;
        { Накопленные скобки оказались обычным текстом. }
        while P.Match > 0 do
        begin
          if not ValPush(P, Ord(']')) then
          begin
            XmlFeed := Fail(P, xfValueTooLong);
            Exit;
          end;
          Dec(P.Match);
        end;
        if not ValPush(P, B) then
        begin
          XmlFeed := Fail(P, xfValueTooLong);
          Exit;
        end;
        if P.ValLen >= XmlTextChunk then
          FlushText(P);
      end;

    xsPi:
      begin
        if B = Ord('?') then
          P.Match := 1
        else if (B = Ord('>')) and (P.Match = 1) then
        begin
          P.Match := 0;
          if P.Depth > 0 then P.State := xsText
          else if P.SeenRoot then P.State := xsEpilog
          else P.State := xsProlog;
        end
        else
          P.Match := 0;
      end;

    xsEntity:
      begin
        if B = Ord(';') then
        begin
          if not ResolveEntity(P) then
          begin
            XmlFeed := Fail(P, xfBadEntity);
            Exit;
          end;
          P.State := P.Ret;
          { Порог проверяется и здесь: сущность могла добить кусок до
            предела, а следующий байт мог бы уже не влезть. }
          if (P.State = xsText) and (P.ValLen >= XmlTextChunk) then
            FlushText(P);
          Exit;
        end;
        if P.EntLen > 15 then
        begin
          { Имя сущности длиннее любого допустимого. Своих сущностей нет,
            значит это либо мусор, либо потерянная точка с запятой. }
          XmlFeed := Fail(P, xfBadEntity);
          Exit;
        end;
        if IsSpace(B) or (B = Ord('<')) or (B = Ord('&')) then
        begin
          XmlFeed := Fail(P, xfBadEntity);
          Exit;
        end;
        P.Ent[P.EntLen] := Chr(B);
        Inc(P.EntLen);
      end;

  end;
end;

function XmlFinish(var P: TXmlParser): TResult;
begin
  if P.State = xsFailed then
  begin
    XmlFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;

  { Обрыв определяется по СОСТОЯНИЮ, а не по одной лишь глубине.

    Поток мог кончиться посреди имени, атрибута или сущности, так и не
    открыв ни одного элемента: <abc — это обрыв, а не «корня не было».
    Разница видна в логе и подсказывает разное: обрыв означает потерянный
    хвост, отсутствие корня — что пришло вообще не то. }
  if (P.State <> xsProlog) and (P.State <> xsEpilog) then
  begin
    P.Fault := xfUnclosed;
    P.State := xsFailed;
    XmlFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;

  if not P.SeenRoot then
  begin
    P.Fault := xfNoRoot;
    P.State := xsFailed;
    XmlFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;

  if P.Depth <> 0 then
  begin
    { Разобранная часть недостоверна: недостающий хвост мог отменить всё,
      что уже прочитано. }
    P.Fault := xfUnclosed;
    P.State := xsFailed;
    XmlFinish := Err('MALFORMED_ENVELOPE');
    Exit;
  end;

  XmlFinish := Ok;
end;

function XmlFault(const P: TXmlParser): TXmlFault;
begin
  XmlFault := P.Fault;
end;

function XmlDepth(const P: TXmlParser): Integer;
begin
  XmlDepth := P.Depth;
end;

function XmlFaultName(F: TXmlFault): string;
begin
  case F of
    xfNone:             XmlFaultName := 'none';
    xfSyntax:           XmlFaultName := 'syntax';
    xfNameTooLong:      XmlFaultName := 'name-too-long';
    xfValueTooLong:     XmlFaultName := 'value-too-long';
    xfTooDeep:          XmlFaultName := 'too-deep';
    xfMismatchedTag:    XmlFaultName := 'mismatched-tag';
    xfUnclosed:         XmlFaultName := 'unclosed';
    xfDoctype:          XmlFaultName := 'doctype-rejected';
    xfBadEntity:        XmlFaultName := 'bad-entity';
    xfNoRoot:           XmlFaultName := 'no-root';
    xfTrailingContent:  XmlFaultName := 'trailing-content';
  else
    XmlFaultName := 'unknown';
  end;
end;

function XmlLocalName(const S: TStr): TStr;
var
  I: Word;
  R: TStr;
begin
  R := S;
  if S.Len > 0 then
    for I := 0 to S.Len - 1 do
      if S.Ptr[I] = ':' then
      begin
        R.Ptr := @S.Ptr[I + 1];
        R.Len := S.Len - I - 1;
      end;
  XmlLocalName := R;
end;

end.
