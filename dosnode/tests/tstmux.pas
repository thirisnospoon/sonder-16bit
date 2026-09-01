{ ===================================================================
  Тесты мультиплексора.

  Главное здесь — конвейеризация и обратное давление, потому что именно
  ради них модуль существует. S2 измерил 13 мс накладных на круговой
  обмен: шестнадцать команд, каждая со своим ожиданием, стоили бы 208 мс
  чистых накладных, отправленные подряд — одни.

  Проверяется и то, что происходит на границах: ответ на закрытый канал,
  чужой канал, заполненное кольцо, пауза посреди кадра. Каждый из этих
  случаев на настоящей линии встретится, и ни один не должен ронять ноду.

  Тесты чистые: линии нет, байты из кольца сразу скармливаются обратно
  во вход. Это и есть петля, на которой удобно проверять маршрутизацию
  без транспорта.
  =================================================================== }
program TstMux;

{$MODE TP}
{$R-}

uses
  TcResult, TcTest, TcSched, TcFrame, TcMux;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  TapFile = 'TSTMUX.TAP';
{$ELSE}
  TapFile = 'tstmux.tap';
{$ENDIF}

  PlannedTests = 41;

var
  R: TResult;
  F, Reply: TFrame;
  Replies: array[1..MaxFibers] of TFrame;
  Chan: Byte;
  Chans: array[1..MaxFibers] of Byte;
  Owner: TFiberId;
  Owners: array[1..MaxFibers] of TFiberId;
  I, J: Integer;
  B: Byte;
  Moved: Integer;
  St: TMuxStats;
  Sent: Integer;
  Before: Word;

procedure MakeFrame(var Fr: TFrame; C: Byte; Len: Word; Seed: Byte);
var
  K: Word;
begin
  FillChar(Fr, SizeOf(Fr), 0);
  Fr.Channel := C;
  Fr.Flags := FlagNeedsReply;
  Fr.Len := Len;
  if Len > 0 then
    for K := 0 to Len - 1 do
      Fr.Payload[K] := Byte((K + Seed) and $FF);
end;

{ Петля: всё, что лежит в исходящем кольце, подаётся обратно на вход.
  Возвращает число перенесённых байт. }
function Loopback: Integer;
var
  N: Integer;
  Bt: Byte;
begin
  N := 0;
  while MuxOutByte(Bt) do
  begin
    MuxFeedByte(Bt);
    Inc(N);
  end;
  Loopback := N;
end;

function PayloadEq(const A, C: TFrame): Boolean;
var
  K: Word;
begin
  PayloadEq := False;
  if A.Len <> C.Len then Exit;
  if A.Len > 0 then
    for K := 0 to A.Len - 1 do
      if A.Payload[K] <> C.Payload[K] then Exit;
  PayloadEq := True;
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('мультиплексор');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('размер кольца исходящих', OutRingBytes);
  TestDiagInt('каналов данных', LastDataChan);

  { ================================================================
    Каналы
    ================================================================ }

  MuxReset;
  SchedReset;

  TestEqInt('после сброса каналы свободны',
            Ord(MuxChanState(1)), Ord(csFree));

  R := SchedSpawn('a', Owner);
  R := MuxOpen(Owner, @Reply, Chan);
  TestResultOk('канал открывается', R);
  TestTrue('номер канала в пределах данных',
           (Chan >= FirstDataChan) and (Chan <= LastDataChan));
  TestEqInt('открытый канал ждёт ответа',
            Ord(MuxChanState(Chan)), Ord(csWaiting));

  MuxClose(Chan);
  TestEqInt('закрытый канал свободен',
            Ord(MuxChanState(Chan)), Ord(csFree));

  { Без буфера ответ класть некуда, и выяснилось бы это только когда
    ответ придёт. Отказ выдаётся сразу. }
  R := MuxOpen(Owner, nil, Chan);
  TestResultErr('канал без буфера ответа отвергается', R, ERR_DECIDER_PANIC);

  { Предел каналов совпадает с пределом команд. }
  MuxReset;
  Sent := 0;
  for I := 1 to MaxFibers do
  begin
    R := MuxOpen(TFiberId(I), @Replies[I], Chans[I]);
    if R.Ok then Inc(Sent);
  end;
  TestEqInt('открылись все каналы данных', Sent, MaxFibers);
  R := MuxOpen(1, @Reply, Chan);
  TestResultErr('сверх предела каналов — отказ', R, ERR_DECIDER_UNAVAILABLE);

  { ================================================================
    Круг через петлю
    ================================================================ }

  MuxReset;
  SchedReset;
  R := SchedSpawn('a', Owner);
  R := MuxOpen(Owner, @Reply, Chan);

  MakeFrame(F, Chan, 128, 7);
  R := MuxSend(F);
  TestResultOk('кадр кладётся в кольцо', R);
  TestEqInt('кольцо содержит весь кадр',
            MuxOutPending, HeaderBytes + 128 + TrailerBytes);

  Moved := Loopback;
  TestEqInt('петля перенесла все байты',
            Moved, HeaderBytes + 128 + TrailerBytes);
  TestEqInt('кольцо опустело', MuxOutPending, 0);

  TestEqInt('канал получил ответ', Ord(MuxChanState(Chan)), Ord(csDone));
  TestTrue('ответ лёг в буфер владельца побайтно', PayloadEq(F, Reply));
  TestEqInt('номер канала в ответе сохранён', Reply.Channel, Chan);

  St := MuxGetStats;
  TestEqInt('счётчик доставленных', St.Delivered, 1);
  TestEqInt('неприкаянных нет', St.Unrouted, 0);

  { ================================================================
    Пробуждение владельца

    Файбер, ждущий ответа, обязан проснуться от его прихода — иначе
    он повиснет навсегда, а нода будет выглядеть просто медленной.
    ================================================================ }

  MuxReset;
  SchedReset;
  R := SchedSpawn('waiter', Owner);
  R := MuxOpen(Owner, @Reply, Chan);
  SchedWaitKey(Owner, wrReply, Chan);
  TestEqInt('владелец уснул в ожидании ответа',
            Ord(SchedInfo(Owner).State), Ord(fsWaiting));

  MakeFrame(F, Chan, 16, 1);
  R := MuxSend(F);
  Loopback;
  TestEqInt('приход ответа разбудил владельца',
            Ord(SchedInfo(Owner).State), Ord(fsReady));

  { ================================================================
    Конвейеризация — то, ради чего модуль существует
    ================================================================ }

  MuxReset;
  SchedReset;

  { Шестнадцать команд отправляют запросы, не дожидаясь ответов. }
  Sent := 0;
  for I := 1 to MaxFibers do
  begin
    R := SchedSpawn('c', Owners[I]);
    R := MuxOpen(Owners[I], @Replies[I], Chans[I]);
    if R.Ok then
    begin
      MakeFrame(F, Chans[I], 40, Byte(I));
      R := MuxSend(F);
      if R.Ok then Inc(Sent);
    end;
  end;

  TestEqInt('все шестнадцать запросов ушли в кольцо без ожидания',
            Sent, MaxFibers);
  TestEqInt('в кольце лежат все кадры сразу',
            MuxOutPending, MaxFibers * (HeaderBytes + 40 + TrailerBytes));

  Moved := Loopback;
  TestEqInt('петля перенесла всё', Moved,
            MaxFibers * (HeaderBytes + 40 + TrailerBytes));

  Sent := 0;
  for I := 1 to MaxFibers do
    if MuxChanState(Chans[I]) = csDone then Inc(Sent);
  TestEqInt('ответ получил каждый канал', Sent, MaxFibers);

  { Ответы не перепутались между каналами — самая дорогая ошибка
    мультиплексора, потому что она даёт правдоподобный неверный результат. }
  Sent := 0;
  for I := 1 to MaxFibers do
    if (Replies[I].Channel = Chans[I]) and
       (Replies[I].Payload[0] = Byte(I)) then Inc(Sent);
  TestEqInt('ответы не перепутались между каналами', Sent, MaxFibers);

  St := MuxGetStats;
  TestEqInt('доставлено ровно шестнадцать', St.Delivered, MaxFibers);
  TestEqInt('обратного давления не было', St.Backpressure, 0);
  TestDiagInt('пик занятости кольца, байт', St.OutHighMark);

  { ================================================================
    Обратное давление
    ================================================================ }

  MuxReset;
  Sent := 0;
  MakeFrame(F, 1, MaxPayload, 3);
  for I := 1 to 20 do
  begin
    R := MuxSend(F);
    if R.Ok then Inc(Sent);
  end;

  TestTrue('кольцо приняло несколько кадров', Sent > 0);
  TestTrue('кольцо переполнилось, а не выросло', Sent < 20);
  TestTrue('переполнение не превысило ёмкость', MuxOutPending <= OutRingBytes);

  St := MuxGetStats;
  TestTrue('отказы по обратному давлению посчитаны', St.Backpressure > 0);

  { Кадр кладётся целиком или не кладётся вовсе: половина кадра в линии —
    это мусор для приёмника и потерянная синхронизация. }
  Before := MuxOutPending;
  R := MuxSend(F);
  TestResultErr('при нехватке места отказ', R, ERR_DECIDER_UNAVAILABLE);
  TestEqInt('после отказа кольцо не изменилось', MuxOutPending, Before);

  { После разгрузки отправка снова проходит. }
  for I := 1 to 600 do
    if not MuxOutByte(B) then Break;
  R := MuxSend(F);
  TestResultOk('после разгрузки отправка проходит', R);

  { ================================================================
    Границы: чужие и закрытые каналы, пауза
    ================================================================ }

  MuxReset;
  SchedReset;

  { Ответ на канал, которого никто не ждёт. Не авария: запрос мог быть
    отменён по сроку, а ответ прийти позже. }
  MakeFrame(F, 5, 8, 1);
  R := MuxSend(F);
  Loopback;
  St := MuxGetStats;
  TestEqInt('ответ на неоткрытый канал посчитан как неприкаянный',
            St.Unrouted, 1);
  TestEqInt('неприкаянный ответ не доставлен', St.Delivered, 0);

  { Ответ после закрытия канала тоже неприкаянный, а не порча памяти:
    буфер владельца к этому моменту мог уже не существовать. }
  MuxReset;
  R := SchedSpawn('a', Owner);
  R := MuxOpen(Owner, @Reply, Chan);
  MuxClose(Chan);
  MakeFrame(F, Chan, 8, 2);
  R := MuxSend(F);
  Loopback;
  St := MuxGetStats;
  TestEqInt('ответ после закрытия канала не доставлен', St.Delivered, 0);
  TestEqInt('он посчитан как неприкаянный', St.Unrouted, 1);

  { Пауза посреди кадра бросает недособранное. }
  MuxReset;
  R := SchedSpawn('a', Owner);
  R := MuxOpen(Owner, @Reply, Chan);
  MakeFrame(F, Chan, 200, 5);
  R := MuxSend(F);
  { Половина кадра ушла, дальше линия замолчала. }
  for I := 1 to 100 do
    if not MuxOutByte(B) then Break else MuxFeedByte(B);
  MuxIdle;
  St := MuxGetStats;
  TestEqInt('пауза посреди кадра посчитана', St.IdleResets, 1);
  TestEqInt('недособранный кадр не доставлен', St.Delivered, 0);

  { И следующий кадр после паузы собирается. }
  MuxReset;
  R := SchedSpawn('a', Owner);
  R := MuxOpen(Owner, @Reply, Chan);
  MakeFrame(F, Chan, 32, 9);
  R := MuxSend(F);
  Loopback;
  TestEqInt('после паузы следующий кадр доставлен',
            Ord(MuxChanState(Chan)), Ord(csDone));

  { Пауза при пустом декодере ничего не значит. }
  MuxReset;
  MuxIdle;
  St := MuxGetStats;
  TestEqInt('пауза между кадрами не считается обрывом', St.IdleResets, 0);

  Halt(TestEnd);
end.
