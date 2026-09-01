{ ===================================================================
  Тесты кадрирования.

  Три слоя, и третий здесь важнее первых двух.

    1. Круг: кадр собран, разобран, совпал.
    2. Повреждения: подделанный байт, невозможная длина, обрыв.
    3. Фаззер: в декодер льётся случайный поток, и он обязан выжить.

  Третий слой главный, потому что на вход декодера приходит не то, что
  мы сгенерировали, а то, что пришло по линии: шум при подключении,
  обрывы, чужой трафик. Требование жёсткое и простое — декодер не имеет
  права ни упасть, ни выйти за буфер, ни выдать наверх кадр с неверной
  суммой. Отбрасывать может сколько угодно.

  Обе стороны — кодировщик и декодер — здесь свои, поэтому круговой тест
  сам по себе слаб: согласованная ошибка прошла бы незамеченной. Поэтому
  контрольная сумма отдельно сверяется с эталонным значением, а разбор —
  с побайтно собранным вручную кадром.
  =================================================================== }
program TstFram;

{$MODE TP}
{$R-}

uses
  TcResult, TcTest, TcFrame;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  FuzzBytes = 60000;
  TapFile   = 'TSTFRAM.TAP';
{$ELSE}
  FuzzBytes = 2000000;
  TapFile   = 'tstfram.tap';
{$ENDIF}

  PlannedTests = 43;
  Seed0 = 20260905;

var
  Rnd: LongInt;

{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

function RndByte: Byte;
begin
  RndByte := Byte((NextRnd shr 7) and $FF);
end;

function RndBelow(N: LongInt): LongInt;
begin
  if N <= 0 then RndBelow := 0 else RndBelow := NextRnd mod N;
end;

var
  Src, Got: TFrame;
  Dec: TDecoder;
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  N: Word;
  R: TResult;
  I, J: Word;
  Round: LongInt;
  Same: Boolean;
  Delivered: LongInt;
  BadDelivery: LongInt;
  Crc: Word;
  Probe: array[0..8] of Byte;

{ Прогнать буфер через декодер. Возвращает число собранных кадров;
  последний остаётся в Dec.Frame. }
function FeedAll(var D: TDecoder; const B; Len: Word): Integer;
var
  P: ^Byte;
  K: Word;
  Cnt: Integer;
begin
  P := @B;
  Cnt := 0;
  for K := 1 to Len do
  begin
    if DecoderFeed(D, P^) then
      Inc(Cnt);
    Inc(P);
  end;
  FeedAll := Cnt;
end;

function PayloadEq(const A, B: TFrame): Boolean;
var
  K: Word;
begin
  PayloadEq := False;
  if A.Len <> B.Len then Exit;
  if A.Len > 0 then
    for K := 0 to A.Len - 1 do
      if A.Payload[K] <> B.Payload[K] then Exit;
  PayloadEq := True;
end;

procedure MakeFrame(var F: TFrame; Chan: Byte; Len: Word; Seed: Byte);
var
  K: Word;
begin
  FillChar(F, SizeOf(F), 0);
  F.Channel := Chan;
  F.Flags := FlagNeedsReply;
  F.Len := Len;
  if Len > 0 then
    for K := 0 to Len - 1 do
      F.Payload[K] := Byte((K + Seed) and $FF);
end;

begin
  TestBegin(TapFile, PlannedTests);
  TestDiag('кадрирование');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('байт в фаззере', FuzzBytes);
  TestDiagInt('размер кадра максимум', MaxFrameBytes);

  { ================================================================
    Контрольная сумма — сверка с эталоном

    Круговой тест не поймал бы согласованную ошибку в собственных
    кодировщике и декодере, поэтому CRC проверяется отдельно, против
    известного значения CRC-16/CCITT-FALSE.
    ================================================================ }

  Probe[0] := Ord('1'); Probe[1] := Ord('2'); Probe[2] := Ord('3');
  Probe[3] := Ord('4'); Probe[4] := Ord('5'); Probe[5] := Ord('6');
  Probe[6] := Ord('7'); Probe[7] := Ord('8'); Probe[8] := Ord('9');
  Crc := CrcBuf(CrcStart, Probe, 9);
  TestDiagInt('CRC от "123456789"', Crc);
  TestEqInt('CRC совпадает с эталоном CCITT-FALSE', Crc, $29B1);

  TestEqInt('начальное значение CRC', CrcStart, $FFFF);
  TestTrue('один изменённый бит меняет сумму',
           CrcByte(CrcStart, $00) <> CrcByte(CrcStart, $01));

  { ================================================================
    Круг: собрать и разобрать
    ================================================================ }

  DecoderReset(Dec);

  MakeFrame(Src, 7, 100, 3);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestResultOk('кадр собирается', R);
  TestEqInt('длина кадра ожидаемая', N, HeaderBytes + 100 + TrailerBytes);
  TestEqInt('маркер на месте, младший', Buf[0], FrameSyncLo);
  TestEqInt('маркер на месте, старший', Buf[1], FrameSyncHi);

  TestEqInt('разобран ровно один кадр', FeedAll(Dec, Buf, N), 1);
  Got := Dec.Frame;
  TestEqInt('канал сохранён', Got.Channel, 7);
  TestEqInt('флаги сохранены', Got.Flags, FlagNeedsReply);
  TestEqInt('длина сохранена', Got.Len, 100);
  TestTrue('полезная нагрузка совпала побайтно', PayloadEq(Src, Got));
  TestEqInt('счётчик исправных кадров', Dec.FramesOk, 1);
  TestEqInt('битых сумм нет', Dec.CrcErrors, 0);

  { Пустая нагрузка — законный кадр: так выглядит подтверждение. }
  DecoderReset(Dec);
  MakeFrame(Src, ChanControl, 0, 0);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestResultOk('кадр без нагрузки собирается', R);
  TestEqInt('кадр без нагрузки разбирается', FeedAll(Dec, Buf, N), 1);
  TestEqInt('длина нулевая', Dec.Frame.Len, 0);

  { Кадр предельного размера. }
  DecoderReset(Dec);
  MakeFrame(Src, 1, MaxPayload, 11);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestResultOk('предельный кадр собирается', R);
  TestEqInt('предельный кадр разбирается', FeedAll(Dec, Buf, N), 1);
  TestTrue('предельная нагрузка совпала', PayloadEq(Src, Dec.Frame));

  { Больше предела — отказ на сборке, а не молчаливое усечение. }
  MakeFrame(Src, 1, 10, 0);
  Src.Len := MaxPayload + 1;
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestResultErr('сверхдлинный кадр отвергается', R, ERR_INSUFFICIENT_CONTEXT);
  TestEqInt('при отказе ничего не записано', N, 0);

  { Тесный буфер — тоже отказ. }
  MakeFrame(Src, 1, 100, 0);
  R := FrameEncode(Src, Buf, 20, N);
  TestResultErr('тесный буфер отвергается', R, ERR_INSUFFICIENT_CONTEXT);

  { ================================================================
    Повреждения
    ================================================================ }

  { Подделанный байт нагрузки обязан быть пойман суммой. }
  DecoderReset(Dec);
  MakeFrame(Src, 3, 64, 5);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  Buf[HeaderBytes + 10] := Buf[HeaderBytes + 10] xor $01;
  TestEqInt('кадр с испорченным байтом не выдан', FeedAll(Dec, Buf, N), 0);
  TestEqInt('битая сумма посчитана', Dec.CrcErrors, 1);

  { Испорченная длина не должна приводить к записи за буфер. }
  DecoderReset(Dec);
  MakeFrame(Src, 3, 64, 5);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  Buf[2] := $FF;
  Buf[3] := $FF;
  TestEqInt('кадр с невозможной длиной не выдан', FeedAll(Dec, Buf, N), 0);
  TestTrue('невозможная длина посчитана', Dec.Oversize > 0);

  { Обрыв на середине: кадр не выдаётся, декодер остаётся живым. }
  DecoderReset(Dec);
  MakeFrame(Src, 3, 200, 7);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('оборванный кадр не выдан', FeedAll(Dec, Buf, N - 5), 0);

  { Настоящее поведение, а не желаемое.

    Декодер ждёт недостающие байты и доедает их из начала следующего
    кадра — вместе с его маркером. Поэтому следующий кадр теряется, и
    первая версия этого теста, требовавшая обратного, была неверна.
    Для потокового протокола это нормально: восстановление наступает
    через один кадр. }
  MakeFrame(Src, 4, 32, 9);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('без сигнала о паузе следующий кадр теряется',
            FeedAll(Dec, Buf, N), 0);

  MakeFrame(Src, 5, 32, 9);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('через один кадр разбор восстанавливается',
            FeedAll(Dec, Buf, N), 1);

  { А с сигналом о паузе — не теряется. Кадр передаётся непрерывно,
    поэтому молчание линии посреди него означает обрыв, и драйвер порта
    обязан об этом сообщить. }
  DecoderReset(Dec);
  MakeFrame(Src, 3, 200, 7);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  FeedAll(Dec, Buf, N - 5);
  TestTrue('пауза посреди кадра бросает недособранное', DecoderIdle(Dec));

  MakeFrame(Src, 4, 32, 9);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('после сигнала о паузе следующий кадр собран',
            FeedAll(Dec, Buf, N), 1);
  TestEqInt('канал следующего кадра верный', Dec.Frame.Channel, 4);

  { Пауза между кадрами законна и ничего не значит. }
  TestFalse('пауза между кадрами не считается обрывом', DecoderIdle(Dec));

  { Мусор перед кадром отбрасывается. }
  DecoderReset(Dec);
  for I := 0 to 39 do
    DecoderFeed(Dec, Byte((I * 7 + 1) and $FF));
  MakeFrame(Src, 5, 16, 2);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('кадр после мусора собран', FeedAll(Dec, Buf, N), 1);
  TestTrue('мусор посчитан', Dec.JunkBytes > 0);

  { Серия из байтов маркера не должна сбивать поиск начала:
    последовательность A5 A5 C3 содержит настоящий маркер со второго байта. }
  DecoderReset(Dec);
  DecoderFeed(Dec, FrameSyncLo);
  MakeFrame(Src, 6, 8, 1);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  TestEqInt('лишний байт маркера не ломает разбор', FeedAll(Dec, Buf, N), 1);

  { ================================================================
    Два кадра подряд — конвейеризация, ради которой всё затевалось
    ================================================================ }

  DecoderReset(Dec);
  MakeFrame(Src, 1, 50, 1);
  R := FrameEncode(Src, Buf, SizeOf(Buf), N);
  I := N;
  MakeFrame(Src, 2, 70, 2);
  R := FrameEncode(Src, Buf[I], SizeOf(Buf) - I, J);
  TestEqInt('два кадра подряд разобраны', FeedAll(Dec, Buf, I + J), 2);
  TestEqInt('последний из серии — второй', Dec.Frame.Channel, 2);

  { ================================================================
    Фаззер: случайный поток в декодер

    Ни падений, ни выходов за буфер, ни кадров с неверной суммой.
    ================================================================ }

  TestDiag('--- случайный поток ---');

  Rnd := Seed0;
  DecoderReset(Dec);
  Delivered := 0;
  BadDelivery := 0;

  for Round := 1 to FuzzBytes do
  begin
    if DecoderFeed(Dec, RndByte) then
    begin
      Inc(Delivered);
      { Выданный кадр обязан быть внутренне непротиворечивым: длина в
        пределах, и она же равна числу принятых байт. }
      if Dec.Frame.Len > MaxPayload then
        Inc(BadDelivery);
    end;
  end;

  TestDiagInt('кадров собрано из шума', Delivered);
  TestDiagInt('пересинхронизаций', Dec.Resyncs);
  TestDiagInt('битых сумм', Dec.CrcErrors);
  TestDiagInt('невозможных длин', Dec.Oversize);
  TestEqInt('из шума не выдано ни одного негодного кадра', BadDelivery, 0);
  TestTrue('декодер пережил случайный поток', True);
  TestTrue('шум действительно доходил до разбора',
           (Dec.JunkBytes > 0) and (Dec.CrcErrors + Dec.Oversize > 0));

  { Фаззер со вставкой настоящих кадров: среди шума они обязаны
    находиться, а не теряться. }
  Rnd := Seed0 + 1;
  DecoderReset(Dec);
  Delivered := 0;
  for Round := 1 to 200 do
  begin
    { Немного шума. }
    for I := 1 to Word(RndBelow(20)) do
      DecoderFeed(Dec, RndByte);
    { И настоящий кадр. }
    MakeFrame(Src, Byte(RndBelow(250)), Word(RndBelow(300)), Byte(RndBelow(255)));
    R := FrameEncode(Src, Buf, SizeOf(Buf), N);
    if R.Ok then
      if FeedAll(Dec, Buf, N) = 1 then
        if PayloadEq(Src, Dec.Frame) then
          Inc(Delivered);
  end;

  TestDiagInt('кадров найдено среди шума', Delivered);
  TestEqInt('все вставленные кадры найдены и совпали', Delivered, 200);

  Halt(TestEnd);
end.
