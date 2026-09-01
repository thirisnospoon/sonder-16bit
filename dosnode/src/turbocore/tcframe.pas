{ ===================================================================
  TurboCore · кадрирование поверх байтового потока.

  Последовательная линия не даёт границ сообщений: она отдаёт байты.
  Кадр восстанавливает границы, а заодно — целостность и адресацию по
  каналам.

      SYNC   LEN   CHAN  FLAGS   PAYLOAD    CRC16
      2 Б    2 Б   1 Б   1 Б     LEN Б      2 Б

  МАРКЕР НАЧАЛА обязателен. Подключиться к линии можно посреди чужого
  кадра, и без маркера декодер принял бы середину полезной нагрузки за
  заголовок. С маркером он отбрасывает мусор до ближайшего правдоподобного
  начала и продолжает.

  CRC покрывает всё, кроме маркера: сам маркер проверять незачем, он и
  есть признак начала.

  РАЗБОР ПОТОКОВЫЙ. Байты приходят из UART по одному, и декодер обязан
  работать, не имея кадра целиком. Отсюда конечный автомат, а не разбор
  готового буфера.

  ГЛАВНОЕ ТРЕБОВАНИЕ. На вход приходит то, что пришло по линии, включая
  обрывы, шум и намеренно испорченные данные. Декодер не имеет права
  ни упасть, ни выйти за буфер, ни выдать наверх кадр с неверной
  контрольной суммой. Отбрасывать — сколько угодно. Это проверяется
  фаззером, а не надеждой.

  О ПАКЕТИРОВАНИИ. Ранняя версия проекта требовала укладывать в один кадр
  сегменты нескольких каналов — из вывода, что 13 мс накладных берутся с
  кадра. Перечитанное измерение S2 говорит другое: 13 мс берутся с
  КРУГОВОГО ОБМЕНА. Значит лечит их конвейеризация — слать, не дожидаясь
  ответа, — а не упаковка каналов. Экономия на заголовке при 512 байтах
  полезной нагрузки составила бы полтора процента, а конвейеризация
  убирает 13 мс на команду целиком. Кадр остаётся одноканальным.
  =================================================================== }
unit TcFrame;

{$MODE TP}
{$R-}

interface

uses
  TcResult;

const
  { Маркер выбран так, чтобы не походить на текст и на длинные серии
    одинаковых бит: и то и другое встречается в шуме чаще случайного. }
  FrameSyncLo = $A5;
  FrameSyncHi = $C3;

  MaxPayload    = 512;
  HeaderBytes   = 6;
  TrailerBytes  = 2;
  MaxFrameBytes = HeaderBytes + MaxPayload + TrailerBytes;

  { Каналы. Ноль — управление, дальше команды в работе, верхние — служебные. }
  ChanControl = 0;
  ChanMetrics = 254;
  ChanLog     = 255;

  { Флаги. }
  FlagNeedsReply = $01;   { отправитель ждёт ответа на этот кадр }
  FlagMore       = $02;   { сообщение продолжается следующим кадром }
  FlagHello      = $04;   { объявление готовности, канал управления }

type
  TFrame = record
    Channel: Byte;
    Flags:   Byte;
    Len:     Word;
    Payload: array[0..MaxPayload - 1] of Byte;
  end;

  TDecodeState = (
    dsSync1,    { ищем первый байт маркера }
    dsSync2,    { ищем второй }
    dsLenLo,
    dsLenHi,
    dsChan,
    dsFlags,
    dsPayload,
    dsCrcLo,
    dsCrcHi
  );

  TDecoder = record
    State:   TDecodeState;
    Frame:   TFrame;
    Got:     Word;        { сколько байт полезной нагрузки принято }
    Crc:     Word;        { накопленная сумма по принятому }
    WantCrc: Word;        { сумма из кадра }

    { Метрики. Отброшенный мусор и битые суммы — это не «ничего не
      случилось», а признак состояния линии, и он обязан быть виден. }
    FramesOk:   LongInt;
    CrcErrors:  LongInt;
    Oversize:   LongInt;
    JunkBytes:  LongInt;
    Resyncs:    LongInt;
  end;

{ CRC-16/CCITT-FALSE: полином $1021, начальное значение $FFFF.
  Таблица строится один раз при инициализации модуля: 512 байт в
  сегменте данных против восьмикратно более медленного побитового
  счёта на каждом байте линии. }
function CrcStart: Word;
function CrcByte(Crc: Word; B: Byte): Word;
function CrcBuf(Crc: Word; const Buf; Len: Word): Word;

{ Собрать кадр в буфер. Written получает длину. }
function FrameEncode(const F: TFrame; var Buf; BufSize: Word;
                     var Written: Word): TResult;

procedure DecoderReset(var D: TDecoder);

{ Скормить один байт. True означает, что кадр собран целиком и лежит
  в D.Frame; до следующего вызова его надо забрать. }
function DecoderFeed(var D: TDecoder; B: Byte): Boolean;

{ Сообщить о паузе в потоке.

  Кадр передаётся непрерывно, поэтому пауза посреди него означает, что
  отправитель оборвался. Без этого сигнала декодер продолжает ждать
  недостающие байты и доедает их из начала СЛЕДУЮЩЕГО кадра — вместе с
  его маркером, — так что следующий кадр теряется целиком, а
  восстановление наступает только через один.

  Вызывается драйвером порта, когда линия молчала дольше времени
  передачи нескольких байт. Возвращает True, если пришлось бросить
  недособранный кадр. }
function DecoderIdle(var D: TDecoder): Boolean;

implementation

var
  CrcTable: array[0..255] of Word;

procedure BuildCrcTable;
var
  I, J: Integer;
  C: Word;
begin
  for I := 0 to 255 do
  begin
    C := Word(I) shl 8;
    for J := 1 to 8 do
      if (C and $8000) <> 0 then
        C := (C shl 1) xor $1021
      else
        C := C shl 1;
    CrcTable[I] := C;
  end;
end;

function CrcStart: Word;
begin
  CrcStart := $FFFF;
end;

function CrcByte(Crc: Word; B: Byte): Word;
begin
  CrcByte := (Crc shl 8) xor CrcTable[((Crc shr 8) xor B) and $FF];
end;

function CrcBuf(Crc: Word; const Buf; Len: Word): Word;
var
  P: ^Byte;
  I: Word;
begin
  P := @Buf;
  for I := 1 to Len do
  begin
    Crc := CrcByte(Crc, P^);
    Inc(P);
  end;
  CrcBuf := Crc;
end;

function FrameEncode(const F: TFrame; var Buf; BufSize: Word;
                     var Written: Word): TResult;
var
  P: ^Byte;
  Crc: Word;
  I: Word;
  Need: Word;
begin
  Written := 0;

  if F.Len > MaxPayload then
  begin
    FrameEncode := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  Need := HeaderBytes + F.Len + TrailerBytes;
  if BufSize < Need then
  begin
    FrameEncode := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  P := @Buf;

  P^ := FrameSyncLo; Inc(P);
  P^ := FrameSyncHi; Inc(P);

  { Контрольная сумма считается по тем же байтам и в том же порядке,
    в каком их увидит декодер: длина, канал, флаги, нагрузка. }
  Crc := CrcStart;

  P^ := Lo(F.Len); Crc := CrcByte(Crc, P^); Inc(P);
  P^ := Hi(F.Len); Crc := CrcByte(Crc, P^); Inc(P);
  P^ := F.Channel; Crc := CrcByte(Crc, P^); Inc(P);
  P^ := F.Flags;   Crc := CrcByte(Crc, P^); Inc(P);

  { Условие обязательно. F.Len имеет тип Word, и при нулевой длине
    выражение F.Len - 1 заворачивается в 65535: цикл записал бы
    шестьдесят пять тысяч байт за буфер. Кадр без нагрузки при этом
    совершенно законен — так выглядит подтверждение. }
  if F.Len > 0 then
    for I := 0 to F.Len - 1 do
    begin
      P^ := F.Payload[I];
      Crc := CrcByte(Crc, P^);
      Inc(P);
    end;

  P^ := Lo(Crc); Inc(P);
  P^ := Hi(Crc);

  Written := Need;
  FrameEncode := Ok;
end;

procedure DecoderReset(var D: TDecoder);
begin
  D.State := dsSync1;
  D.Got := 0;
  D.Crc := CrcStart;
  D.WantCrc := 0;
  D.Frame.Channel := 0;
  D.Frame.Flags := 0;
  D.Frame.Len := 0;
  D.FramesOk := 0;
  D.CrcErrors := 0;
  D.Oversize := 0;
  D.JunkBytes := 0;
  D.Resyncs := 0;
end;

{ Вернуться к поиску маркера. Вызывается на любой аномалии: битой сумме,
  невозможной длине, обрыве. Кадр при этом наверх не отдаётся. }
procedure Resync(var D: TDecoder);
begin
  D.State := dsSync1;
  D.Got := 0;
  D.Crc := CrcStart;
  Inc(D.Resyncs);
end;

function DecoderIdle(var D: TDecoder): Boolean;
begin
  if D.State = dsSync1 then
  begin
    { Между кадрами пауза законна и ничего не значит. }
    DecoderIdle := False;
    Exit;
  end;
  Resync(D);
  DecoderIdle := True;
end;

function DecoderFeed(var D: TDecoder; B: Byte): Boolean;
begin
  DecoderFeed := False;

  case D.State of

    dsSync1:
      if B = FrameSyncLo then
        D.State := dsSync2
      else
        Inc(D.JunkBytes);

    dsSync2:
      if B = FrameSyncHi then
      begin
        D.State := dsLenLo;
        D.Crc := CrcStart;
        D.Got := 0;
      end
      else
      begin
        Inc(D.JunkBytes);
        { Второй байт мог оказаться началом настоящего маркера: серия
          вида A5 A5 C3 должна разбираться, а не отбрасываться целиком. }
        if B = FrameSyncLo then
          D.State := dsSync2
        else
          D.State := dsSync1;
      end;

    dsLenLo:
      begin
        D.Frame.Len := B;
        D.Crc := CrcByte(D.Crc, B);
        D.State := dsLenHi;
      end;

    dsLenHi:
      begin
        D.Frame.Len := D.Frame.Len or (Word(B) shl 8);
        D.Crc := CrcByte(D.Crc, B);
        { Длина проверяется ДО того, как под неё начнут писать байты.
          Иначе испорченное поле длины стало бы записью за буфер. }
        if D.Frame.Len > MaxPayload then
        begin
          Inc(D.Oversize);
          Resync(D);
        end
        else
          D.State := dsChan;
      end;

    dsChan:
      begin
        D.Frame.Channel := B;
        D.Crc := CrcByte(D.Crc, B);
        D.State := dsFlags;
      end;

    dsFlags:
      begin
        D.Frame.Flags := B;
        D.Crc := CrcByte(D.Crc, B);
        if D.Frame.Len = 0 then
          D.State := dsCrcLo
        else
          D.State := dsPayload;
      end;

    dsPayload:
      begin
        { Условие избыточно после проверки длины, но стоит здесь
          намеренно: запись за буфер — единственная ошибка в этом
          модуле, которая не проявится сразу. }
        if D.Got < MaxPayload then
          D.Frame.Payload[D.Got] := B;
        D.Crc := CrcByte(D.Crc, B);
        Inc(D.Got);
        if D.Got >= D.Frame.Len then
          D.State := dsCrcLo;
      end;

    dsCrcLo:
      begin
        D.WantCrc := B;
        D.State := dsCrcHi;
      end;

    dsCrcHi:
      begin
        D.WantCrc := D.WantCrc or (Word(B) shl 8);
        if D.WantCrc = D.Crc then
        begin
          Inc(D.FramesOk);
          D.State := dsSync1;
          D.Got := 0;
          D.Crc := CrcStart;
          DecoderFeed := True;
        end
        else
        begin
          Inc(D.CrcErrors);
          { Не Resync: пересинхронизация здесь была бы посчитана дважды,
            а причина у отброса другая и должна считаться отдельно. }
          D.State := dsSync1;
          D.Got := 0;
          D.Crc := CrcStart;
        end;
      end;

  end;
end;

begin
  BuildCrcTable;
end.
