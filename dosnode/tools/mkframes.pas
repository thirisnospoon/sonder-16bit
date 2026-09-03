{ ===================================================================
  Порождение эталонных кадров для другой стороны линии.

  Гейтвей на Java обязан кодировать и разбирать кадры ровно так же, как
  tcframe. «Обе стороны написаны по одному описанию» — не доказательство:
  ровно на этом уже обожглись с конвертами SOAP, где рукописный
  <createPost> разошёлся с настоящим <CreatePostRequest>, и понята была
  бы не одна команда.

  Поэтому здесь кадры не описываются, а ПОРОЖДАЮТСЯ настоящим
  FrameEncode, и байты кладутся в файл. Java их разбирает и кодирует
  обратно; совпадение байт в байт доказывает согласие обоих
  кодировщиков сразу, а не по очереди.

  Формат файла нарочно простейший: подряд идущие записи

      длина кадра (2 байта, младший первым)
      сами байты кадра

  Разбирать его надо уметь обеим сторонам, и всякая структура сложнее
  этой сама стала бы предметом расхождения.

  Набор случаев подобран по границам, а не по красоте: пустая нагрузка
  (так выглядит подтверждение), нагрузка в один байт, полная нагрузка в
  512 байт, все служебные каналы, все сочетания флагов, нагрузка из
  байтов, похожих на маркер кадра.
  =================================================================== }
program MkFrames;

{$MODE TP}
{$R-}

uses
  TcResult, TcFrame;

const
  OutName = 'frames.bin';

var
  F: file;
  Total: LongInt;

{ Записать один кадр: длину и байты. }
procedure Emit(Chan, Flags: Byte; Len: Word; FillMode: Byte);
var
  Fr: TFrame;
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  Written: Word;
  R: TResult;
  I: Word;
  LenBytes: array[0..1] of Byte;
begin
  FillChar(Fr, SizeOf(Fr), 0);
  Fr.Channel := Chan;
  Fr.Flags := Flags;
  Fr.Len := Len;

  if Len > 0 then
    for I := 0 to Len - 1 do
      case FillMode of
        { Возрастающие байты: ловит перепутанный порядок. }
        0: Fr.Payload[I] := Byte(I and $FF);
        { Байты маркера: разборщик не должен принять их за начало кадра. }
        1: if Odd(I) then Fr.Payload[I] := FrameSyncHi
           else Fr.Payload[I] := FrameSyncLo;
        { Старший бит везде: ловит знаковое расширение байта. }
        2: Fr.Payload[I] := $FF;
      else
        Fr.Payload[I] := 0;
      end;

  R := FrameEncode(Fr, Buf, SizeOf(Buf), Written);
  if not R.Ok then
  begin
    WriteLn('НЕ ЗАКОДИРОВАЛСЯ кадр: канал ', Chan, ' длина ', Len);
    Halt(1);
  end;

  LenBytes[0] := Lo(Written);
  LenBytes[1] := Hi(Written);
  BlockWrite(F, LenBytes, 2);
  BlockWrite(F, Buf, Written);
  Inc(Total);
end;

begin
  Assign(F, OutName);
  Rewrite(F, 1);
  Total := 0;

  { Пустая нагрузка на всех служебных каналах и без флагов. }
  Emit(ChanControl, 0, 0, 0);
  Emit(ChanControl, FlagHello, 0, 0);
  Emit(ChanMetrics, 0, 0, 0);
  Emit(ChanLog, 0, 0, 0);

  { Один байт: граница между «пусто» и «есть что считать». }
  Emit(1, FlagNeedsReply, 1, 0);

  { Все сочетания флагов на обычном канале. }
  Emit(7, 0, 16, 0);
  Emit(7, FlagNeedsReply, 16, 0);
  Emit(7, FlagMore, 16, 0);
  Emit(7, FlagNeedsReply or FlagMore, 16, 0);
  Emit(7, FlagNeedsReply or FlagMore or FlagHello, 16, 0);

  { Нагрузка из байтов маркера: самый опасный случай для разборщика. }
  Emit(3, 0, 64, 1);

  { Старший бит везде: на Java байт знаковый, и это классическая ловушка. }
  Emit(4, 0, 32, 2);

  { Полная нагрузка и на единицу меньше полной. }
  Emit(5, FlagMore, MaxPayload - 1, 0);
  Emit(5, 0, MaxPayload, 0);

  { Верхний номер обычного канала. }
  Emit(253, FlagNeedsReply, 8, 0);

  Close(F);
  WriteLn('кадров записано: ', Total);
  WriteLn('файл: ', OutName);
end.
