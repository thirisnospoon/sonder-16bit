{ ===================================================================
  Эталонные ОТВЕТЫ: то, что ядро на самом деле кладёт в линию.

  Эталонные конверты были только на запросы: Java порождает, Pascal
  разбирает. Обратное направление не проверялось ничем — и это стоило
  четырёх дефектов подряд, найденных не тестом, а сквозным прогоном:
  ответ без пространства имён, событие без полей, пинг без пространства
  имён, и круговой тест, читавший ответ не тем разборщиком.

  Каждый из них жил на стыке и был невидим поодиночке. Здесь стык
  закрывается с той же стороны, с какой уже закрыт для запросов: ответы
  порождает НАСТОЯЩИЙ писатель ядра, а разбирает их настоящий
  связыватель гейтвея.

  Формат файла тот же, что у эталонных кадров: подряд идущие записи
  «длина (2 байта, младший первым) + байты». Разбирать его должны обе
  стороны, и всякая структура сложнее этой сама стала бы предметом
  расхождения.
  =================================================================== }
program MkReplies;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, TcFrame, TcSoap, DcdTypes, DcdSrv, DmDecide;

{$I errcodes.inc}

const
  OutName = 'replies.bin';

var
  F: file;
  Total: LongInt;

  { Собираем кадры писателя в один буфер: в файл идёт конверт целиком,
    а не по кадрам — резать его на кадры умеет мультиплексор, и здесь
    это лишний слой. }
  Buf: array[0..8191] of Byte;
  Len: Word;
  Overflow: Boolean;

function Collect(const Fr: TFrame; More: Boolean): Boolean; far;
var
  I: Word;
begin
  if Fr.Len > 0 then
    for I := 0 to Fr.Len - 1 do
    begin
      if Len >= SizeOf(Buf) then
      begin
        Overflow := True;
        Collect := False;
        Exit;
      end;
      Buf[Len] := Fr.Payload[I];
      Inc(Len);
    end;
  Collect := True;
end;

procedure StartOne;
begin
  Len := 0;
  Overflow := False;
end;

procedure Emit(const What: string);
var
  LenBytes: array[0..1] of Byte;
begin
  if Overflow then
  begin
    WriteLn('ПЕРЕПОЛНЕНИЕ на ', What);
    Halt(1);
  end;
  LenBytes[0] := Lo(Len);
  LenBytes[1] := Hi(Len);
  BlockWrite(F, LenBytes, 2);
  BlockWrite(F, Buf, Len);
  Inc(Total);
end;

var
  A: TArena;
  W: TSoapWriter;
  D: TDecision;
  R: TResult;
  Ev: PDomainEventNode;

{ Решение с одним событием и двумя полями: самая обычная форма ответа. }
procedure AcceptedWithEvent;
begin
  ArenaReset(A);
  FillChar(D, SizeOf(D), 0);
  D.accepted := True;

  R := EmitEvent(A, D, 'post.created', StrView('p-1001'), Ev);
  if not R.Ok then Halt(1);
  R := EmitField(A, Ev, 'authorId', StrView('u-andrey'));
  if not R.Ok then Halt(1);
  { Кириллица и амперсанд в значении поля: и то и другое пишет
    пользователь, и на границе они ломаются первыми. }
  R := EmitField(A, Ev, 'note', StrView('Первый & последний'));
  if not R.Ok then Halt(1);

  StartOne;
  SoapWriterInit(W, 1, Collect);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
  Emit('решение с событием');
end;

{ Два события в одном решении: список, а не одиночка. }
procedure AcceptedWithTwoEvents;
begin
  ArenaReset(A);
  FillChar(D, SizeOf(D), 0);
  D.accepted := True;

  R := EmitEvent(A, D, 'follow.removed', StrView('u-andrey'), Ev);
  if not R.Ok then Halt(1);
  R := EmitField(A, Ev, 'targetUserId', StrView('u-boris'));
  if not R.Ok then Halt(1);

  R := EmitEvent(A, D, 'post.deleted', StrView('p-1001'), Ev);
  if not R.Ok then Halt(1);
  R := EmitField(A, Ev, 'deletedBy', StrView('u-andrey'));
  if not R.Ok then Halt(1);

  StartOne;
  SoapWriterInit(W, 2, Collect);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
  Emit('решение с двумя событиями');
end;

{ Отказ: код и подробность, событий нет. }
procedure Rejected;
begin
  ArenaReset(A);
  FillChar(D, SizeOf(D), 0);
  D.accepted := False;
  D.errorCode := StrView(ERR_POST_RATE_EXCEEDED);
  D.errorDetail := StrView('не больше десяти в час');

  StartOne;
  SoapWriterInit(W, 3, Collect);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
  Emit('отказ');
end;

{ Принято, но событий нет: так выглядит идемпотентный повтор. }
procedure AcceptedWithoutEvents;
begin
  ArenaReset(A);
  FillChar(D, SizeOf(D), 0);
  D.accepted := True;

  StartOne;
  SoapWriterInit(W, 4, Collect);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
  Emit('принято без событий');
end;

{ Ответ на пинг: другой корень и другие поля. }
procedure Pong;
begin
  StartOne;
  SoapWriterInit(W, 5, Collect);
  SoapBeginEnvelope(W);
  SoapOpenNs(W, 'PingResponse', DeciderNs);
  SoapElementInt(W, 'nonce', 4242);
  SoapElementInt(W, 'fibersInUse', 3);
  SoapElementInt(W, 'arenaHighMark', 1024);
  SoapClose(W, 'PingResponse');
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
  Emit('пинг');
end;

begin
  R := ArenaCreate(A, 8192, 'replies');
  if not R.Ok then
  begin
    WriteLn('не создалась арена');
    Halt(1);
  end;

  Assign(F, OutName);
  Rewrite(F, 1);
  Total := 0;

  AcceptedWithEvent;
  AcceptedWithTwoEvents;
  Rejected;
  AcceptedWithoutEvents;
  Pong;

  Close(F);
  ArenaDestroy(A);

  WriteLn('ответов записано: ', Total);
  WriteLn('файл: ', OutName);
end.
