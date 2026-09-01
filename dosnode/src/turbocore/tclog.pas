{ ===================================================================
  TurboCore · структурный журнал.

  Формат — NDJSON: по одному объекту на строку. Такой лог читается и
  глазами, и сборщиком, и переживает передачу по последовательной линии,
  где строка либо дошла целиком, либо не дошла вовсе.

  БЕЗ ВЫДЕЛЕНИЯ ПАМЯТИ. Строка собирается в статическом буфере
  фиксированного размера. Журналирование обязано работать в том числе
  когда арена исчерпана — а это как раз тот момент, когда лог нужнее
  всего.

  ПРИЁМНИК ПОДКЛЮЧАЕТСЯ. Куда уходит строка, модуль не знает: на
  нативном таргете в поток вывода, на DOS-ноде — в отдельный канал
  мультиплексора (docs/TURBOCORE.md §9). Без установленного приёмника
  журнал молча ничего не делает: отсутствие лога не должно ронять
  программу.

  ЭКРАНИРОВАНИЕ ОБЯЗАТЕЛЬНО. В журнал попадают ники и тела постов,
  то есть произвольный пользовательский текст. Кавычка в нём без
  экранирования порвёт JSON-строку, и сборщик потеряет не одну запись,
  а всё до конца файла. Это не гипотетическая аккуратность: ровно так
  выглядит внедрение в лог.

  ПОРЯДОК ВЫЗОВОВ. LogBegin, затем поля, затем LogEnd. Незакрытая
  запись не выводится вовсе: половина JSON-объекта хуже его отсутствия.
  =================================================================== }
unit TcLog;

{$MODE TP}

interface

uses
  TcStr;

type
  TLogLevel = (llDebug, llInfo, llWarn, llError);

  { Приёмник строки. Len не включает перевод строки: его добавляет
    приёмник, если он ему нужен. }
  TLogSink = procedure(Line: PChar; Len: Word);

const
  LogBufSize = 512;

{ Установить приёмник и порог. Записи ниже порога не собираются вовсе —
  не собираются, а не отбрасываются в конце: на 16 битах сборка строки
  стоит заметно. }
procedure LogInit(Sink: TLogSink; MinLevel: TLogLevel);

{ Отключить журнал. Полезно в тестах, где лог мешает вердикту. }
procedure LogSilence;

procedure LogBegin(Level: TLogLevel; const Event: string);
procedure LogFieldStr(const Key: string; const Value: TStr);
procedure LogFieldPas(const Key, Value: string);
procedure LogFieldInt(const Key: string; Value: LongInt);
procedure LogFieldBool(const Key: string; Value: Boolean);
procedure LogEnd;

{ Сколько записей не поместилось в буфер и было усечено. Усечение
  считается дефектом наблюдаемости и выносится метрикой, а не прячется. }
function LogTruncated: LongInt;

{ Сколько записей отброшено по порогу уровня. }
function LogSkipped: LongInt;

implementation

var
  Buf: array[0..LogBufSize - 1] of Char;
  Len: Word;
  Active: Boolean;         { запись начата и ещё не закрыта }
  Overflow: Boolean;       { в текущей записи буфер кончился }
  CurSink: TLogSink;
  Threshold: TLogLevel;
  Fields: Integer;
  TruncCount: LongInt;
  SkipCount: LongInt;

const
  LevelNames: array[TLogLevel] of string[5] =
    ('debug', 'info', 'warn', 'error');

procedure PutChar(C: Char);
begin
  if Len >= LogBufSize then
  begin
    Overflow := True;
    Exit;
  end;
  Buf[Len] := C;
  Inc(Len);
end;

procedure PutPas(const S: string);
var
  I: Integer;
begin
  for I := 1 to Length(S) do
    PutChar(S[I]);
end;

{ Экранирование по RFC 8259. Управляющие символы уходят в \u00XX:
  так безопаснее, чем выбрасывать их, потому что выброшенный символ
  меняет содержимое молча. }
procedure PutEscaped(C: Char);
const
  Hex: array[0..15] of Char = '0123456789abcdef';
begin
  case C of
    '"':  PutPas('\"');
    '\':  PutPas('\\');
    #8:   PutPas('\b');
    #9:   PutPas('\t');
    #10:  PutPas('\n');
    #12:  PutPas('\f');
    #13:  PutPas('\r');
  else
    if C < ' ' then
    begin
      PutPas('\u00');
      PutChar(Hex[Ord(C) shr 4]);
      PutChar(Hex[Ord(C) and $0F]);
    end
    else
      { Байты со старшим битом пропускаются как есть: это UTF-8, и
        разбивать его на символы здесь незачем. }
      PutChar(C);
  end;
end;

procedure PutEscapedPas(const S: string);
var
  I: Integer;
begin
  for I := 1 to Length(S) do
    PutEscaped(S[I]);
end;

procedure PutEscapedStr(const S: TStr);
var
  I: Word;
begin
  if StrIsEmpty(S) then
    Exit;
  for I := 0 to S.Len - 1 do
    PutEscaped(StrCharAt(S, I));
end;

procedure PutInt(V: LongInt);
var
  S: string;
begin
  Str(V, S);
  PutPas(S);
end;

procedure LogInit(Sink: TLogSink; MinLevel: TLogLevel);
begin
  CurSink := Sink;
  Threshold := MinLevel;
  Active := False;
  Len := 0;
  Fields := 0;
  Overflow := False;
end;

procedure LogSilence;
begin
  CurSink := nil;
  Active := False;
end;

procedure LogBegin(Level: TLogLevel; const Event: string);
begin
  { Незакрытая предыдущая запись — дефект вызывающего. Молча бросаем её,
    но считаем: половина объекта в потоке хуже, чем его отсутствие. }
  if Active then
    Inc(TruncCount);

  Active := False;
  Len := 0;
  Fields := 0;
  Overflow := False;

  if (@CurSink = nil) then
    Exit;
  if Ord(Level) < Ord(Threshold) then
  begin
    Inc(SkipCount);
    Exit;
  end;

  Active := True;
  PutChar('{');
  PutPas('"lvl":"');
  PutPas(LevelNames[Level]);
  PutPas('","evt":"');
  PutEscapedPas(Event);
  PutChar('"');
end;

procedure StartField(const Key: string);
begin
  PutChar(',');
  PutChar('"');
  PutEscapedPas(Key);
  PutPas('":');
  Inc(Fields);
end;

procedure LogFieldStr(const Key: string; const Value: TStr);
begin
  if not Active then Exit;
  StartField(Key);
  PutChar('"');
  PutEscapedStr(Value);
  PutChar('"');
end;

procedure LogFieldPas(const Key, Value: string);
begin
  if not Active then Exit;
  StartField(Key);
  PutChar('"');
  PutEscapedPas(Value);
  PutChar('"');
end;

procedure LogFieldInt(const Key: string; Value: LongInt);
begin
  if not Active then Exit;
  StartField(Key);
  PutInt(Value);
end;

procedure LogFieldBool(const Key: string; Value: Boolean);
begin
  if not Active then Exit;
  StartField(Key);
  if Value then PutPas('true') else PutPas('false');
end;

procedure LogEnd;
begin
  if not Active then
    Exit;

  { Усечённая запись всё равно выводится, но помечается: потерять факт
    события хуже, чем получить его без части полей. Закрывающая скобка
    ставится всегда, иначе строка не разберётся сборщиком. }
  if Overflow then
  begin
    Inc(TruncCount);
    { Освобождаем место под маркер и скобку принудительно. }
    if Len > LogBufSize - 16 then
      Len := LogBufSize - 16;
    PutPas(',"trunc":true');
  end;

  PutChar('}');

  Active := False;
  if @CurSink <> nil then
    CurSink(@Buf[0], Len);
  Len := 0;
end;

function LogTruncated: LongInt;
begin
  LogTruncated := TruncCount;
end;

function LogSkipped: LongInt;
begin
  LogSkipped := SkipCount;
end;

begin
  CurSink := nil;
  Threshold := llInfo;
  Active := False;
  Len := 0;
  TruncCount := 0;
  SkipCount := 0;
end.
