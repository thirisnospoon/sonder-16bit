{ ===================================================================
  Тесты структурного журнала.

  Главное здесь — экранирование. В журнал попадают ники и тела постов,
  то есть текст, который пишет пользователь. Кавычка в нём без
  экранирования порвёт JSON-строку, и сборщик потеряет не одну запись,
  а всё до конца файла. Ровно так выглядит внедрение в лог, и проверять
  это надо явно, а не надеяться.

  Приёмник в тестах складывает строку в переменную, поэтому проверяется
  ровно то, что ушло бы наружу.
  =================================================================== }
program TstLog;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcTest, TcLog;

const
{$IFDEF CPU16}
  TapFile = 'TSTLOG.TAP';
{$ELSE}
  TapFile = 'tstlog.tap';
{$ENDIF}

  PlannedTests = 27;

var
  Captured: string;      { начало строки, до 255 байт }
  CapturedTail: string;  { последние 60 байт }
  CaptureCount: Integer;
  CapturedLen: Word;

{ Приёмник хранит и голову, и хвост.

  Только голова не годится: тип string не длиннее 255 байт, а маркер
  усечения и закрывающая скобка стоят в конце строки длиной под пятьсот.
  Первая версия этого приёмника хранила только начало, и два теста про
  усечение падали — не из-за журнала, а из-за самого приёмника. }
procedure Capture(Line: PChar; Len: Word); far;
var
  I, N, Start: Word;
  P: PChar;
begin
  Inc(CaptureCount);
  CapturedLen := Len;

  Captured := '';
  N := Len;
  if N > 255 then
    N := 255;
  P := Line;
  for I := 1 to N do
  begin
    Captured := Captured + P^;
    Inc(P);
  end;

  CapturedTail := '';
  if Len > 60 then Start := Len - 60 else Start := 0;
  P := Line;
  Inc(P, Start);
  for I := Start to Len - 1 do
  begin
    CapturedTail := CapturedTail + P^;
    Inc(P);
  end;
end;

function Contains(const Hay, Needle: string): Boolean;
begin
  Contains := Pos(Needle, Hay) > 0;
end;

var
  S: string;
  V: TStr;
  I: Integer;
  Long: string;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('структурный журнал');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}

  { ================================================================
    Базовая форма
    ================================================================ }

  CaptureCount := 0;
  LogInit(Capture, llDebug);

  LogBegin(llInfo, 'command.received');
  LogEnd;

  TestEqInt('запись ушла в приёмник', CaptureCount, 1);
  TestTrue('строка начинается объектом', Copy(Captured, 1, 1) = '{');
  TestTrue('строка заканчивается объектом',
           Copy(CapturedTail, Length(CapturedTail), 1) = '}');
  TestTrue('уровень записан', Contains(Captured, '"lvl":"info"'));
  TestTrue('событие записано', Contains(Captured, '"evt":"command.received"'));

  { ================================================================
    Поля
    ================================================================ }

  LogBegin(llInfo, 'decided');
  LogFieldPas('trace', 'tr-42');
  LogFieldInt('elapsed', 137);
  LogFieldBool('accepted', True);
  LogEnd;

  TestTrue('строковое поле записано', Contains(Captured, '"trace":"tr-42"'));
  TestTrue('целое поле без кавычек', Contains(Captured, '"elapsed":137'));
  TestTrue('логическое поле без кавычек',
           Contains(Captured, '"accepted":true'));

  S := 'u-andrey';
  V := StrView(S);
  LogBegin(llInfo, 'actor');
  LogFieldStr('userId', V);
  LogEnd;
  TestTrue('поле из TStr записано', Contains(Captured, '"userId":"u-andrey"'));

  { ================================================================
    Экранирование — то, ради чего этот тест существует
    ================================================================ }

  S := 'он сказал "привет"';
  LogBegin(llInfo, 'quoted');
  LogFieldPas('text', S);
  LogEnd;
  TestTrue('кавычка экранирована', Contains(Captured, '\"привет\"'));
  TestFalse('неэкранированной кавычки не осталось',
            Contains(Captured, '"text":"он сказал "'));

  S := 'путь C:\temp\file';
  LogBegin(llInfo, 'backslash');
  LogFieldPas('text', S);
  LogEnd;
  TestTrue('обратная косая экранирована', Contains(Captured, '\\temp\\file'));

  S := 'строка' + #10 + 'вторая';
  LogBegin(llInfo, 'newline');
  LogFieldPas('text', S);
  LogEnd;
  TestTrue('перевод строки экранирован', Contains(Captured, '\n'));
  TestFalse('сырого перевода строки в записи нет', Contains(Captured, #10));

  S := 'таб' + #9 + 'и возврат' + #13;
  LogBegin(llInfo, 'controls');
  LogFieldPas('text', S);
  LogEnd;
  TestTrue('табуляция экранирована', Contains(Captured, '\t'));
  TestTrue('возврат каретки экранирован', Contains(Captured, '\r'));

  S := 'нулевой' + #1 + 'символ';
  LogBegin(llInfo, 'ctrl1');
  LogFieldPas('text', S);
  LogEnd;
  TestTrue('редкий управляющий символ ушёл в \u00',
           Contains(Captured, '\u0001'));

  { Попытка внедрения: пользователь пишет текст, который выглядит как
    конец объекта и начало нового. Экранирование обязано это пресечь. }
  S := '","lvl":"error","evt":"поддельное';
  LogBegin(llInfo, 'injection');
  LogFieldPas('text', S);
  LogEnd;
  TestEqInt('внедрение не породило вторую запись', CaptureCount, 9);
  TestFalse('поддельный уровень не появился как поле',
            Contains(Captured, '{"lvl":"error"'));

  { ================================================================
    Порог уровня
    ================================================================ }

  LogInit(Capture, llWarn);
  CaptureCount := 0;
  LogBegin(llDebug, 'ignored');
  LogFieldPas('x', 'y');
  LogEnd;
  TestEqInt('запись ниже порога не выводится', CaptureCount, 0);

  LogBegin(llError, 'kept');
  LogEnd;
  TestEqInt('запись выше порога выводится', CaptureCount, 1);

  { ================================================================
    Усечение
    ================================================================ }

  LogInit(Capture, llDebug);
  Long := '';
  for I := 1 to 200 do
    Long := Long + 'x';

  CaptureCount := 0;
  LogBegin(llInfo, 'big');
  LogFieldPas('a', Long);
  LogFieldPas('b', Long);
  LogFieldPas('c', Long);
  LogFieldPas('d', Long);
  LogEnd;

  TestEqInt('усечённая запись всё равно выводится', CaptureCount, 1);
  TestDiagInt('длина усечённой записи', CapturedLen);
  TestTrue('усечение помечено', Contains(CapturedTail, '"trunc":true'));
  TestTrue('строка всё равно закрыта скобкой',
           Copy(CapturedTail, Length(CapturedTail), 1) = '}');
  TestTrue('запись не превысила буфер', CapturedLen <= LogBufSize);
  TestTrue('усечения посчитаны', LogTruncated > 0);

  { ================================================================
    Отсутствие приёмника не роняет программу
    ================================================================ }

  LogSilence;
  CaptureCount := 0;
  LogBegin(llError, 'nosink');
  LogFieldPas('x', 'y');
  LogEnd;
  TestEqInt('без приёмника ничего не выводится', CaptureCount, 0);

  Halt(TestEnd);
end.
