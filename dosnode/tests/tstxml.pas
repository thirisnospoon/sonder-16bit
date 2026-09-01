{ ===================================================================
  Тесты потокового разбора XML.

  Разборщик читает то, что пришло по линии, поэтому проверок здесь два
  рода и второй важнее первого.

  Первый — что корректный конверт разбирается в правильную
  последовательность событий. Она записывается в компактный след, и
  сравнение идёт со следом целиком: так видно не только «событие было»,
  но и порядок, и то, что лишних событий не появилось.

  Второй — что испорченный вход отвергается, причём с той причиной,
  которая соответствует поломке. Разборщик, отвергающий всё подряд с
  одним кодом, формально прав и бесполезен: по логу нельзя понять, чинить
  оболочку, поднимать предел или ловить порчу на линии.

  Отдельно — случайные входы. Разборщик не имеет права ни зациклиться,
  ни выйти за пределы буферов, ни закончить в состоянии, о котором нельзя
  сказать «разобрано» или «отвергнуто».
  =================================================================== }
program TstXml;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcTest, TcXml;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile    = 'TSTXML.TAP';
  FuzzRounds = 2000;
{$ELSE}
  TapFile    = 'tstxml.tap';
  FuzzRounds = 100000;
{$ENDIF}

  PlannedTests = 78;
  Seed0 = 20260905;

var
  P: TXmlParser;
  R: TResult;
  I, J, Rnd: LongInt;
  Chunks, Deepest, Failures, Successes: LongInt;
  { Отдельная переменная, а не Chunks: Start обнуляет счётчики каждой
    итерации, а обработчик считает Chunks текстовыми кусками. Первая
    версия мерила ими же выход за границы и намерила чепуху. }
  OutOfBounds, Swept: LongInt;
  Trace: string;
  Overflow: Boolean;
  CountEvents: Boolean;
  TextBytes: LongInt;
  Fuzz: array[0..255] of Byte;
  FuzzLen: Integer;

  { Настоящий конверт в том виде, в каком его порождает CXF. Массив, а не
    константа: строка в диалекте TP не длиннее 255 байт, а конверт длиннее.
    Индексация с нуля. }
  Env: array[0..511] of Char;
  EnvLen: Integer;
  { Где кончается объявление <?xml ... ?>. Внутри инструкции обработки
    символ < ничего не значит по спецификации, поэтому свипы, которые
    его подставляют, туда не заходят. }
  EnvDeclLen: Integer;

procedure EnvAdd(const S: string);
var
  K: Integer;
begin
  for K := 1 to Length(S) do
  begin
    Env[EnvLen] := S[K];
    Inc(EnvLen);
  end;
end;

procedure BuildEnvelope;
begin
  EnvLen := 0;
  EnvAdd('<?xml version="1.0" encoding="UTF-8"?>');
  EnvDeclLen := EnvLen;
  EnvAdd('<soap:Envelope xmlns:soap=');
  EnvAdd('"http://schemas.xmlsoap.org/soap/envelope/">');
  EnvAdd('<soap:Body>');
  EnvAdd('<ns2:createPost xmlns:ns2="urn:sonder:decider:v1">');
  EnvAdd('<postId>p-1001</postId>');
  EnvAdd('<body>Привет</body>');
  EnvAdd('</ns2:createPost>');
  EnvAdd('</soap:Body>');
  EnvAdd('</soap:Envelope>');
end;

{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

procedure Add(const S: string);
begin
  if Length(Trace) + Length(S) > 250 then
  begin
    Overflow := True;
    Exit;
  end;
  Trace := Trace + S;
end;

{ Обработчик событий. far обязателен: в модели large переменная
  процедурного типа — дальний указатель. }
procedure Rec(Ev: TXmlEvent; const Name, Value: TStr); far;
begin
  if CountEvents then
  begin
    if Ev = xeText then
    begin
      Inc(Chunks);
      Inc(TextBytes, Value.Len);
    end;
    if XmlDepth(P) > Deepest then
      Deepest := XmlDepth(P);
    Exit;
  end;

  case Ev of
    xeStartElement: Add('>' + StrHead(Name));
    xeEndElement:   Add('<' + StrHead(Name));
    xeAttribute:    Add('@' + StrHead(Name) + '=' + StrHead(Value));
    xeText:         Add('#' + StrHead(Value));
  end;
end;

procedure Start;
begin
  Trace := '';
  Overflow := False;
  Chunks := 0;
  TextBytes := 0;
  Deepest := 0;
  XmlReset(P, Rec);
end;

{ Скормить паскалевскую строку. Возвращает False, если разбор отвергнут
  на каком-то байте. }
function Feed(const S: string): Boolean;
var
  K: Integer;
begin
  Feed := True;
  for K := 1 to Length(S) do
    if not XmlFeed(P, Byte(S[K])) then
    begin
      Feed := False;
      Exit;
    end;
end;

{ Разобрать документ целиком и сверить след. }
procedure CheckTrace(const Name, Doc, Want: string);
begin
  Start;
  CountEvents := False;
  Feed(Doc);
  R := XmlFinish(P);

  if not R.Ok then
  begin
    TestOk(Name, False);
    TestDiag('  отвергнуто: ' + XmlFaultName(XmlFault(P)));
    TestDiag('  след до отказа: ' + Trace);
    Exit;
  end;
  if Overflow then
  begin
    TestOk(Name, False);
    TestDiag('  след не поместился, документ для этой проверки велик');
    Exit;
  end;
  TestOk(Name, Trace = Want);
  if Trace <> Want then
  begin
    TestDiag('  получено: ' + Trace);
    TestDiag('  ожидалось: ' + Want);
  end;
end;

{ Разобрать документ и убедиться, что он отвергнут ИМЕННО с этой
  причиной. Причина проверяется, а не только сам отказ: иначе тест
  прошёл бы и на разборщике, который отвергает всё. }
procedure CheckFault(const Name, Doc: string; Want: TXmlFault);
begin
  Start;
  CountEvents := False;
  Feed(Doc);
  R := XmlFinish(P);

  if R.Ok then
  begin
    TestOk(Name, False);
    TestDiag('  принято, а ожидался отказ ' + XmlFaultName(Want));
    Exit;
  end;
  TestOk(Name, XmlFault(P) = Want);
  if XmlFault(P) <> Want then
  begin
    TestDiag('  причина: ' + XmlFaultName(XmlFault(P)));
    TestDiag('  ожидалась: ' + XmlFaultName(Want));
  end;
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('потоковый разбор XML');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('SizeOf(TXmlParser)', SizeOf(TXmlParser));
  TestDiagInt('предел глубины', MaxXmlDepth);
  TestDiagInt('предел имени', MaxXmlName);
  TestDiagInt('порог текстового куска', XmlTextChunk);
  TestDiagInt('случайных входов', FuzzRounds);

  { ================================================================
    Корректные документы
    ================================================================ }

  CheckTrace('пустой элемент', '<a/>', '>a<a');
  CheckTrace('элемент с текстом', '<a>x</a>', '>a#x<a');
  CheckTrace('вложение', '<a><b>x</b></a>', '>a>b#x<b<a');
  CheckTrace('два соседних элемента', '<a><b/><c/></a>', '>a>b<b>c<c<a');
  CheckTrace('атрибут', '<a k="v"/>', '>a@k=v<a');
  CheckTrace('два атрибута', '<a k="v" m="w"/>', '>a@k=v@m=w<a');
  CheckTrace('атрибут в одинарных кавычках', '<a k=''v''/>', '>a@k=v<a');
  CheckTrace('пробелы вокруг знака равенства', '<a k = "v" />', '>a@k=v<a');
  CheckTrace('пустой атрибут', '<a k=""/>', '>a@k=<a');
  CheckTrace('префикс пространства имён сохраняется',
             '<ns2:a/>', '>ns2:a<ns2:a');

  CheckTrace('объявление пропускается',
             '<?xml version="1.0"?><a/>', '>a<a');
  CheckTrace('комментарий пропускается',
             '<a><!-- x --><b/></a>', '>a>b<b<a');
  CheckTrace('комментарий с дефисами внутри',
             '<a><!-- -- - --></a>', '>a<a');
  CheckTrace('комментарий до корня', '<!--x--><a/>', '>a<a');
  CheckTrace('комментарий после корня', '<a/><!--x-->', '>a<a');

  CheckTrace('CDATA выдаётся как текст',
             '<a><![CDATA[<b>&z]]></a>', '>a#<b>&z<a');
  CheckTrace('скобки внутри CDATA',
             '<a><![CDATA[x]]]></a>', '>a#x]<a');

  { Пробелы между тегами выдаются как есть: отличить незначащий отступ
    от значащего пробела разборщик не может, а терять данные не вправе. }
  CheckTrace('пробелы между тегами выдаются',
             '<a> <b/> </a>', '>a# >b<b# <a');

  { ================================================================
    Сущности
    ================================================================ }

  CheckTrace('сущность amp', '<a>&amp;</a>', '>a#&<a');
  CheckTrace('сущность lt', '<a>&lt;</a>', '>a#<<a');
  CheckTrace('сущность gt', '<a>&gt;</a>', '>a#><a');
  CheckTrace('сущность quot', '<a>&quot;</a>', '>a#"<a');
  CheckTrace('сущность apos', '<a>&apos;</a>', '>a#''<a');
  CheckTrace('сущность в значении атрибута',
             '<a k="&lt;&amp;"/>', '>a@k=<&<a');
  CheckTrace('сущности вперемешку с текстом',
             '<a>x&amp;y</a>', '>a#x&y<a');

  CheckTrace('числовая ссылка десятичная', '<a>&#65;</a>', '>a#A<a');
  CheckTrace('числовая ссылка шестнадцатеричная',
             '<a>&#x41;</a>', '>a#A<a');
  { U+0416 — Ж, две единицы в UTF-8. }
  CheckTrace('числовая ссылка вне ASCII', '<a>&#x416;</a>', '>a#Ж<a');

  { ================================================================
    Отказы — каждый со своей причиной
    ================================================================ }

  CheckFault('DTD отвергается', '<!DOCTYPE a><a/>', xfDoctype);
  CheckFault('DTD отвергается и внутри документа',
             '<a><!DOCTYPE b></a>', xfDoctype);

  CheckFault('несовпадение тегов', '<a></b>', xfMismatchedTag);
  CheckFault('несовпадение во вложении',
             '<a><b></a></b>', xfMismatchedTag);
  CheckFault('закрывающий тег без открывающего', '<a/></a>', xfTrailingContent);

  CheckFault('незакрытый элемент', '<a>', xfUnclosed);
  CheckFault('незакрытый вложенный', '<a><b></a>', xfMismatchedTag);
  CheckFault('обрыв посреди тега', '<a k="v"', xfUnclosed);
  CheckFault('обрыв посреди имени', '<abc', xfUnclosed);

  CheckFault('пустой поток', '', xfNoRoot);
  CheckFault('только объявление', '<?xml version="1.0"?>', xfNoRoot);
  CheckFault('текст до корня', 'x<a/>', xfSyntax);
  CheckFault('текст после корня', '<a/>x', xfTrailingContent);
  CheckFault('второй корень', '<a/><b/>', xfTrailingContent);

  CheckFault('неизвестная сущность', '<a>&nbsp;</a>', xfBadEntity);
  CheckFault('сущность без имени', '<a>&;</a>', xfBadEntity);
  CheckFault('числовая ссылка без цифр', '<a>&#;</a>', xfBadEntity);
  CheckFault('пробел внутри сущности', '<a>&am p;</a>', xfBadEntity);
  { Суррогаты и всё за верхней границей отвергаются теми же правилами,
    что и в StrCharLen: принять запись, которую сами же потом признаем
    испорченной, было бы непоследовательно. }
  CheckFault('числовая ссылка на суррогат', '<a>&#xD800;</a>', xfBadEntity);
  CheckFault('числовая ссылка за U+10FFFF',
             '<a>&#x110000;</a>', xfBadEntity);

  CheckFault('голый < в значении атрибута', '<a k="<"/>', xfSyntax);
  CheckFault('атрибут без значения', '<a k/>', xfSyntax);
  CheckFault('значение атрибута без кавычек', '<a k=v/>', xfSyntax);
  CheckFault('тег без имени', '<>', xfSyntax);
  CheckFault('слэш не перед закрытием', '<a/b>', xfSyntax);

  { ================================================================
    Пределы

    У разборщика без предела предел всё равно есть — размер стека,
    и находит он его в самый неподходящий момент.
    ================================================================ }

  Trace := '';
  for I := 1 to MaxXmlDepth + 1 do
    Trace := '';
  Start;
  CountEvents := True;
  for I := 1 to MaxXmlDepth do
    Feed('<a>');
  TestEqInt('глубина набирается до предела', XmlDepth(P), MaxXmlDepth);
  TestFalse('уровнем глубже — отказ', XmlFeed(P, Ord('<')) and
                                      XmlFeed(P, Ord('a')) and
                                      XmlFeed(P, Ord('>')));
  TestOk('причина отказа — глубина', XmlFault(P) = xfTooDeep);

  Start;
  CountEvents := True;
  Feed('<');
  for I := 1 to MaxXmlName + 2 do
    XmlFeed(P, Ord('a'));
  TestOk('слишком длинное имя отвергается',
         XmlFault(P) = xfNameTooLong);

  Start;
  CountEvents := True;
  Feed('<a k="');
  for I := 1 to MaxXmlAttrValue + 2 do
    XmlFeed(P, Ord('x'));
  TestOk('слишком длинное значение атрибута отвергается',
         XmlFault(P) = xfValueTooLong);

  { ================================================================
    Текст кусками

    Тело поста длиннее любого разумного буфера, поэтому одному узлу
    соответствует несколько событий xeText. Проверяется, что кусков
    больше одного и что суммарно вышло ровно столько байт, сколько
    вошло: разбиение не имеет права ничего потерять.
    ================================================================ }

  Start;
  CountEvents := True;
  Feed('<a>');
  for I := 1 to 1000 do
    XmlFeed(P, Ord('x'));
  Feed('</a>');
  R := XmlFinish(P);

  TestResultOk('длинный текст разбирается', R);
  TestTrue('длинный текст выдан несколькими кусками', Chunks > 1);
  TestEqInt('ни один байт текста не потерян', TextBytes, 1000);
  TestDiagInt('кусков на тысяче байт', Chunks);

  { ================================================================
    Настоящий конверт
    ================================================================ }

  BuildEnvelope;
  Start;
  CountEvents := True;
  Failures := 0;
  for J := 0 to EnvLen - 1 do
    if not XmlFeed(P, Byte(Env[J])) then
    begin
      Failures := 1;
      Break;
    end;
  TestEqInt('конверт CXF разбирается без отказа', Failures, 0);
  R := XmlFinish(P);
  TestResultOk('конверт CXF завершён корректно', R);
  TestEqInt('после конверта глубина нулевая', XmlDepth(P), 0);
  TestEqInt('самая глубокая точка конверта', Deepest, 4);
  TestEqInt('кириллица в теле дошла целиком', TextBytes, 12 + 6);

  { Локальное имя без префикса. }
  Trace := 'ns2:createPost';
  TestEqStr('локальное имя отрезает префикс',
            StrHead(XmlLocalName(StrView(Trace))), 'createPost');
  Trace := 'plain';
  TestEqStr('имя без префикса не меняется',
            StrHead(XmlLocalName(StrView(Trace))), 'plain');

  { ================================================================
    Случайные входы

    Чистый шум — это сеть безопасности, а не покрытие, и притворяться
    иначе нечестно: из ста тысяч случайных строк корректным документом
    не оказывается НИ ОДНА, и весь этот свип проверяет ровно одно —
    что разборщик не падает и не зависает на произвольных байтах.

    Настоящее покрытие дают мутации настоящего конверта ниже: замена
    байта, лишний «<», выпадение байта, усечение. Они заходят в разбор
    на всю глубину, потому что стартуют с корректного документа.
    ================================================================ }

  Rnd := Seed0;
  Failures := 0;
  Successes := 0;

  for I := 1 to FuzzRounds do
  begin
    Start;
    CountEvents := True;

    FuzzLen := Integer(NextRnd mod 64) + 1;
    for J := 0 to FuzzLen - 1 do
      case NextRnd mod 3 of
        0: Fuzz[J] := Byte(NextRnd and $FF);
        1: Fuzz[J] := Byte(Ord('<') + (NextRnd mod 3));
      else
        { Символы, которые для разборщика что-то значат. }
        case NextRnd mod 8 of
          0: Fuzz[J] := Ord('<');
          1: Fuzz[J] := Ord('>');
          2: Fuzz[J] := Ord('/');
          3: Fuzz[J] := Ord('&');
          4: Fuzz[J] := Ord(';');
          5: Fuzz[J] := Ord('"');
          6: Fuzz[J] := Ord('=');
        else
          Fuzz[J] := Ord('a');
        end;
      end;

    for J := 0 to FuzzLen - 1 do
      if not XmlFeed(P, Fuzz[J]) then
        Break;

    R := XmlFinish(P);
    if R.Ok then Inc(Successes) else Inc(Failures);

    { Глубина обязана оставаться в объявленных границах при любом входе:
      выход за неё означал бы запись мимо стека имён. }
    if (XmlDepth(P) < 0) or (XmlDepth(P) > MaxXmlDepth) then
    begin
      TestDiagInt('глубина вышла за границы на раунде', I);
      Break;
    end;
  end;

  TestEqInt('все случайные входы обработаны',
            Successes + Failures, FuzzRounds);
  TestTrue('часть случайных входов отвергнута', Failures > 0);
  TestDiagInt('отвергнуто случайных входов', Failures);
  TestDiagInt('принято случайных входов', Successes);

  { Испорченный конверт: каждый байт по очереди заменяется на случайный.

    Утверждать здесь «замечено больше половины» было бы проверкой
    совпадения, а не свойства. Порча внутри текста или значения атрибута
    XML не ломает: «Приает» — такой же корректный документ, как
    «Привет». От неё защищает CRC-16 кадра, а не разборщик, и требовать
    от разборщика чужой работы значит написать тест, который держится на
    соотношении разметки и текста в этом конкретном конверте.

    Что действительно обязано выполняться при ЛЮБОЙ порче: разбор
    заканчивается определённым вердиктом и глубина не выходит за
    объявленные границы. Выход за них означал бы запись мимо стека имён. }
  Failures := 0;
  Successes := 0;
  OutOfBounds := 0;
  for I := 0 to EnvLen - 1 do
  begin
    Start;
    CountEvents := True;
    for J := 0 to EnvLen - 1 do
      if J = I then
      begin
        if not XmlFeed(P, Byte(NextRnd and $FF)) then Break;
      end
      else
        if not XmlFeed(P, Byte(Env[J])) then Break;

    R := XmlFinish(P);
    if R.Ok then Inc(Successes) else Inc(Failures);
    if (XmlDepth(P) < 0) or (XmlDepth(P) > MaxXmlDepth) then
      Inc(OutOfBounds);
  end;

  TestEqInt('все порчи конверта обработаны',
            Successes + Failures, EnvLen);
  TestEqInt('ни одна порча не вывела глубину за границы', OutOfBounds, 0);
  TestDiagInt('порч замечено', Failures);
  TestDiagInt('порч прошло незамеченными (текст и значения)', Successes);

  { А вот это уже свойство, а не совпадение: лишний «<» обязан быть
    замечен. Внутри разметки он ломает структуру, внутри текста —
    открывает тег, который не закроется тем же именем.

    Два исключения, и оба настоящие, а не поблажки разборщику.

    Позиции внутри объявления <?xml ... ?> пропускаются: по спецификации
    внутри инструкции обработки «<» ничего не значит, и документ остаётся
    корректным. Пропускаются и позиции, где «<» уже стоит: подставить
    его туда значит ничего не менять.

    Первая редакция этого теста утверждала «в любой позиции» и
    провалилась на сорока пяти случаях. Все сорок пять оказались этими
    двумя исключениями — то есть неточным было утверждение, а не код. }
  Successes := 0;
  Swept := 0;
  for I := EnvDeclLen to EnvLen - 1 do
  begin
    if Env[I] = '<' then Continue;
    Inc(Swept);
    Start;
    CountEvents := True;
    for J := 0 to EnvLen - 1 do
      if J = I then
      begin
        if not XmlFeed(P, Ord('<')) then Break;
      end
      else
        if not XmlFeed(P, Byte(Env[J])) then Break;
    R := XmlFinish(P);
    if R.Ok then
    begin
      Inc(Successes);
      TestDiagInt('  уцелела позиция', I);
    end;
  end;
  TestEqInt('лишний < вне объявления замечается всегда', Successes, 0);
  TestTrue('свип по «<» осмысленно широк', Swept > EnvLen div 2);
  TestDiagInt('позиций просвипано', Swept);

  { Удаление байта. Инварианты те же; счёт замеченного — диагностика. }
  Failures := 0;
  Successes := 0;
  OutOfBounds := 0;
  for I := 0 to EnvLen - 1 do
  begin
    Start;
    CountEvents := True;
    for J := 0 to EnvLen - 1 do
      if J <> I then
        if not XmlFeed(P, Byte(Env[J])) then Break;
    R := XmlFinish(P);
    if R.Ok then Inc(Successes) else Inc(Failures);
    if (XmlDepth(P) < 0) or (XmlDepth(P) > MaxXmlDepth) then
      Inc(OutOfBounds);
  end;

  TestEqInt('все выпадения байта обработаны',
            Successes + Failures, EnvLen);
  TestEqInt('ни одно выпадение не вывело глубину за границы',
            OutOfBounds, 0);
  TestDiagInt('выпадений замечено', Failures);
  TestDiagInt('выпадений прошло незамеченными', Successes);

  { Усечение: конверт обрывается в каждой возможной точке. Ни одно
    усечение не имеет права выглядеть как полный документ — иначе
    оборванный кадр дал бы правдоподобное неверное решение. }
  Failures := 0;
  Successes := 0;
  for I := 1 to EnvLen - 1 do
  begin
    Start;
    CountEvents := True;
    for J := 0 to I - 1 do
      if not XmlFeed(P, Byte(Env[J])) then Break;
    R := XmlFinish(P);
    if R.Ok then Inc(Successes) else Inc(Failures);
  end;

  TestEqInt('ни одно усечение конверта не принято', Successes, 0);
  TestEqInt('все усечения отвергнуты', Failures, EnvLen - 1);

  Halt(TestEnd);
end.
