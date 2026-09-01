{ ===================================================================
  Долгий прогон фаззеров: XML и кадры.

  Отдельная программа, а не тест, по двум причинам. Тесты обязаны
  укладываться в секунды, иначе их перестают гонять. И вердикт здесь
  другой: не «столько-то проверок прошло», а «за столько-то миллионов
  случаев ничего не сломалось».

  ИТОГ ПИШЕТСЯ ФАЙЛОМ, а не только в стандартный вывод. Под DOSBox ни код
  возврата, ни перенаправление вывода не надёжны (RISKS, R3), а вердикт
  снимать с чего-то надо. Тесты выносят его по TAP-файлу — здесь по
  SOAK.OUT, по той же причине и тем же способом.

  ВРЕМЯ МЕРЯЕТ ОБОЛОЧКА, А НЕ ЭТА ПРОГРАММА. Она получает семя и число
  раундов, отрабатывает их и выходит; крутит её до нужного срока скрипт,
  меняя семя. Так прогон остаётся детерминированным: падение на
  восемнадцатом часу воспроизводится одной командой с тем же семенем, а
  не «попробуй ещё сутки». Ровно та же причина, по которой в tstfuzz
  свой генератор псевдослучайных чисел, а не Random из RTL.

  ЧТО ПРОВЕРЯЕТСЯ, КРОМЕ ОТСУТСТВИЯ ПАДЕНИЯ. Фаззер, у которого
  единственное требование «не упало», пропустит порчу памяти, которая
  проявится позже и в другом месте. Поэтому после каждого случая
  сверяются инварианты: глубина разбора не выходит за объявленные
  границы, отвергнутый документ имеет причину отказа, принятый —
  закрытые теги, а кадр после кодирования и обратного разбора совпадает
  с исходным побайтно.
  =================================================================== }
program Soak;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcFrame, TcXml;

const
  { Валидный конверт, из которого делаются мутации. Мутировать настоящий
    документ гораздо полезнее, чем сыпать шумом: шум отвергается на
    первом же байте и до разбора не доходит. }
  MaxDoc = 512;

type
  TMutation = (
    muNone,        { контроль: неиспорченный документ обязан проходить }
    muSubstitute,
    muInsert,
    muDelete,
    muTruncate,
    muDuplicate,   { кусок документа повторяется }
    muNoise,       { чистый шум }
    muTokens       { суп из значащих символов }
  );

var
  Env: array[0..MaxDoc - 1] of Char;
  EnvLen: Integer;

  Doc: array[0..MaxDoc * 2 - 1] of Byte;
  DocLen: Integer;

  P: TXmlParser;
  Rx: TDecoder;

  Rnd: LongInt;
  Seed, Rounds: LongInt;
  { Режим самопроверки: фаззер намеренно сообщает о нарушении, чтобы было
    видно, что обвязка умеет провалиться. Фаззер, который не умеет
    провалиться, зелен всегда и не значит ничего — та же причина, по
    которой у валидатора контрактов есть selftest с намеренными
    дефектами. }
  SelfTest: Boolean;

  { Счётчики. }
  XmlOk, XmlBad: LongInt;
  FrameOk, FrameBad: LongInt;
  Violations: LongInt;
  ByMutation: array[TMutation] of LongInt;

  I, J: LongInt;
  Code: Integer;
  S: string;

{$PUSH}{$Q-}
function NextRnd: LongInt;
begin
  Rnd := (Rnd * 1664525 + 1013904223) and $7FFFFFFF;
  NextRnd := Rnd;
end;
{$POP}

function Below(N: LongInt): LongInt;
begin
  if N <= 0 then Below := 0 else Below := NextRnd mod N;
end;

procedure EnvAdd(const T: string);
var
  K: Integer;
begin
  for K := 1 to Length(T) do
  begin
    if EnvLen >= MaxDoc then Exit;
    Env[EnvLen] := T[K];
    Inc(EnvLen);
  end;
end;

procedure BuildEnvelope;
begin
  EnvLen := 0;
  EnvAdd('<?xml version="1.0" encoding="UTF-8"?>');
  EnvAdd('<soap:Envelope xmlns:soap=');
  EnvAdd('"http://schemas.xmlsoap.org/soap/envelope/">');
  EnvAdd('<soap:Body>');
  EnvAdd('<ns2:createPost xmlns:ns2="urn:sonder:decider:v1">');
  EnvAdd('<meta><traceId>t-1</traceId></meta>');
  EnvAdd('<command><postId>p-1</postId>');
  EnvAdd('<body>Привет &amp; пока</body></command>');
  EnvAdd('<actor><userId>u-1</userId><role>USER</role></actor>');
  EnvAdd('</ns2:createPost>');
  EnvAdd('</soap:Body>');
  EnvAdd('</soap:Envelope>');
end;

{ Значащие для разборщика символы. Суп из них заходит в разбор глубже,
  чем равномерный шум. }
function TokenByte: Byte;
begin
  case Below(12) of
    0: TokenByte := Ord('<');
    1: TokenByte := Ord('>');
    2: TokenByte := Ord('/');
    3: TokenByte := Ord('&');
    4: TokenByte := Ord(';');
    5: TokenByte := Ord('"');
    6: TokenByte := Ord('=');
    7: TokenByte := Ord('!');
    8: TokenByte := Ord('?');
    9: TokenByte := Ord('[');
   10: TokenByte := Ord('-');
  else
    TokenByte := Ord('a') + Byte(Below(26));
  end;
end;

procedure MakeDoc(M: TMutation);
var
  K, Pos, Cut, Len: LongInt;
begin
  DocLen := 0;

  case M of
    muNoise:
      begin
        Len := Below(200) + 1;
        for K := 1 to Len do
        begin
          Doc[DocLen] := Byte(NextRnd and $FF);
          Inc(DocLen);
        end;
        Exit;
      end;
    muTokens:
      begin
        Len := Below(200) + 1;
        for K := 1 to Len do
        begin
          Doc[DocLen] := TokenByte;
          Inc(DocLen);
        end;
        Exit;
      end;
  end;

  { Остальные мутации делаются из настоящего конверта. }
  for K := 0 to EnvLen - 1 do
  begin
    Doc[DocLen] := Byte(Env[K]);
    Inc(DocLen);
  end;

  case M of
    muSubstitute:
      Doc[Below(DocLen)] := Byte(NextRnd and $FF);

    muInsert:
      begin
        Pos := Below(DocLen + 1);
        for K := DocLen downto Pos + 1 do
          Doc[K] := Doc[K - 1];
        Doc[Pos] := TokenByte;
        Inc(DocLen);
      end;

    muDelete:
      begin
        Pos := Below(DocLen);
        for K := Pos to DocLen - 2 do
          Doc[K] := Doc[K + 1];
        Dec(DocLen);
      end;

    muTruncate:
      DocLen := Below(DocLen);

    muDuplicate:
      begin
        Pos := Below(DocLen);
        Cut := Below(DocLen - Pos) + 1;
        if DocLen + Cut < MaxDoc * 2 then
        begin
          for K := 0 to Cut - 1 do
          begin
            Doc[DocLen] := Doc[Pos + K];
            Inc(DocLen);
          end;
        end;
      end;
  end;
end;

procedure Nothing(Ev: TXmlEvent; const Name, Value: TStr); far;
begin
end;

{ Один случай разбора XML. Возвращает False, если нарушен инвариант. }
function XmlCase(M: TMutation): Boolean;
var
  K: LongInt;
  R: TResult;
begin
  XmlCase := True;
  MakeDoc(M);

  XmlReset(P, Nothing);
  for K := 0 to DocLen - 1 do
    if not XmlFeed(P, Doc[K]) then Break;
  R := XmlFinish(P);

  { Глубина обязана оставаться в объявленных границах при любом входе:
    выход за них означал бы запись мимо стека имён. }
  if (XmlDepth(P) < 0) or (XmlDepth(P) > MaxXmlDepth) then
  begin
    XmlCase := False;
    Exit;
  end;

  if R.Ok then
  begin
    Inc(XmlOk);
    { Принятый документ обязан быть закрыт до конца. }
    if XmlDepth(P) <> 0 then XmlCase := False;
    { И не иметь причины отказа. }
    if XmlFault(P) <> xfNone then XmlCase := False;
  end
  else
  begin
    Inc(XmlBad);
    { Отвергнутый обязан назвать причину: отказ без причины нечитаем в
      логе и неотличим от дефекта самого разборщика. }
    if XmlFault(P) = xfNone then XmlCase := False;
  end;

  { Неиспорченный конверт обязан проходить. Контрольный случай нужен,
    чтобы фаззер, отвергающий вообще всё, не выглядел зелёным. }
  if (M = muNone) and (not R.Ok) then
    XmlCase := False;
end;

{ Один случай кадрирования: закодировать случайный кадр, разобрать
  обратно, сверить побайтно. }
function FrameCase: Boolean;
var
  F, G: TFrame;
  Buf: array[0..MaxFrameBytes - 1] of Byte;
  N, K: Word;
  R: TResult;
  Corrupt: Boolean;
  Where: LongInt;
begin
  FrameCase := True;

  FillChar(F, SizeOf(F), 0);
  F.Channel := Byte(Below(256));
  F.Flags := Byte(Below(8));
  F.Len := Word(Below(MaxPayload + 1));
  if F.Len > 0 then
    for K := 0 to F.Len - 1 do
      F.Payload[K] := Byte(NextRnd and $FF);

  R := FrameEncode(F, Buf, SizeOf(Buf), N);
  if not R.Ok then
  begin
    { Кадр в пределах контракта обязан кодироваться. }
    FrameCase := False;
    Exit;
  end;

  { В четверти случаев портим один байт. }
  Corrupt := Below(4) = 0;
  if Corrupt and (N > 0) then
  begin
    Where := Below(N);
    Buf[Where] := Buf[Where] xor Byte(1 shl Below(8));
  end;

  DecoderReset(Rx);
  G := Rx.Frame;
  FillChar(G, SizeOf(G), 0);

  for K := 0 to N - 1 do
    if DecoderFeed(Rx, Buf[K]) then
      G := Rx.Frame;

  if G.Len > MaxPayload then
  begin
    { Разобранная длина сверх предела означала бы запись мимо буфера. }
    FrameCase := False;
    Exit;
  end;

  if not Corrupt then
  begin
    { Неиспорченный кадр обязан вернуться побайтно тем же. }
    if (G.Channel <> F.Channel) or (G.Flags <> F.Flags) or
       (G.Len <> F.Len) then
    begin
      FrameCase := False;
      Exit;
    end;
    if F.Len > 0 then
      for K := 0 to F.Len - 1 do
        if G.Payload[K] <> F.Payload[K] then
        begin
          FrameCase := False;
          Exit;
        end;
    Inc(FrameOk);
  end
  else
    { Испорченный кадр может и пройти CRC — шестнадцать бит не дают
      гарантии, а дают вероятность. Считаем и не считаем это дефектом. }
    Inc(FrameBad);
end;

var
  M: TMutation;
  Okc: Boolean;
  Rep: Text;

{$IFDEF CPU16}
const ReportFile = 'SOAK.OUT';
{$ELSE}
const ReportFile = 'soak.out';
{$ENDIF}

{ Строка идёт и в файл, и в вывод: под DOSBox читается файл, нативно
  удобнее вывод. }
procedure Say(const T: string);
begin
  WriteLn(Rep, T);
  Flush(Rep);
  WriteLn(T);
end;

function Num(N: LongInt): string;
var
  T: string;
begin
  Str(N, T);
  Num := T;
end;

begin
  Seed := 1;
  Rounds := 100000;

  if ParamCount >= 1 then
  begin
    Val(ParamStr(1), Seed, Code);
    if Code <> 0 then Seed := 1;
  end;
  if ParamCount >= 2 then
  begin
    Val(ParamStr(2), Rounds, Code);
    if Code <> 0 then Rounds := 100000;
  end;
  SelfTest := (ParamCount >= 3) and (ParamStr(3) = 'selftest');

  Assign(Rep, ReportFile);
  Rewrite(Rep);

  BuildEnvelope;
  Rnd := Seed;
  XmlOk := 0; XmlBad := 0;
  FrameOk := 0; FrameBad := 0;
  Violations := 0;
  for M := muNone to muTokens do
    ByMutation[M] := 0;

  for I := 1 to Rounds do
  begin
    { Неиспорченный конверт подмешивается редко, но регулярно: он
      контрольный. }
    if Below(64) = 0 then
      M := muNone
    else
      M := TMutation(Below(Ord(muTokens)) + 1);

    Inc(ByMutation[M]);

    Okc := XmlCase(M);
    if not Okc then
    begin
      Inc(Violations);
      Say('VIOLATION xml seed=' + Num(Seed) + ' round=' + Num(I) +
          ' mutation=' + Num(Ord(M)) + ' depth=' + Num(XmlDepth(P)) +
          ' fault=' + XmlFaultName(XmlFault(P)));
    end;

    if not FrameCase then
    begin
      Inc(Violations);
      Say('VIOLATION frame seed=' + Num(Seed) + ' round=' + Num(I));
    end;
  end;

  if SelfTest then
  begin
    Inc(Violations);
    Say('VIOLATION selftest seed=' + Num(Seed) +
        ' (намеренное, проверка обвязки)');
  end;

  { Строка итога машиночитаема: скрипт долгого прогона складывает их. }
  S := 'soak seed=' + Num(Seed) + ' rounds=' + Num(Rounds) +
       ' xml_ok=' + Num(XmlOk) + ' xml_bad=' + Num(XmlBad) +
       ' frame_ok=' + Num(FrameOk) + ' frame_corrupt=' + Num(FrameBad) +
       ' violations=' + Num(Violations);
  Say(S);

  Close(Rep);

  if Violations > 0 then
    Halt(1)
  else
    Halt(0);
end.
