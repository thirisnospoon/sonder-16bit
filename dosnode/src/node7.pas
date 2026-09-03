{ ===================================================================
  NODE-7: доменное ядро как программа.

  Модули были все, а связывающего их цикла не было ни в одной фазе
  плана: фундамент, решения, рантайм, транспорт — всё построено, а
  запустить нечего. Пропуск обнаружился при попытке прогнать цепочку
  целиком и оказался не в коде, а в плане.

  ЧТО ЗДЕСЬ ПРОИСХОДИТ. Байт приходит из порта, попадает в кадр, кадр —
  в конверт, конверт — в запрос, запрос — в решение, решение уезжает
  ответом. Каждое звено уже написано и проверено по отдельности; здесь
  они соединяются, и ничего сверх соединения тут быть не должно. Всякое
  доменное правило, попавшее в этот файл, было бы вторым экземпляром
  правила из dmdecide.

  СОСТОЯНИЕ КАНАЛОВ ЖИВЁТ В ДАЛЬНЕЙ КУЧЕ, и это измерено, а не
  прикинуто: разборщик конверта занимает 1676 байт, самый толстый запрос
  — 72, итого 1748 на канал и 27 968 на шестнадцать. В сегменте данных,
  которого всего 64 КБ, это больше сорока процентов ради состояния,
  живущего только пока команда в работе. Тот же вывод, что и у спайка
  S1b про стеки файберов.

  ПОЧЕМУ БЕЗ ФАЙБЕРОВ. Решение есть чистая функция и считается
  микросекунды: переключать на нём контекст незачем. Файберы нужны там,
  где обработчик ждёт, а здесь ждать нечего — ввода-вывода в ядре нет по
  построению (ADR-0011). Разбор же конверта идёт по кадрам, и его
  состояние держит не стек файбера, а запись канала: команда,
  приехавшая вперемешку с пятнадцатью другими, продолжается ровно с того
  места, где прервалась.
  =================================================================== }
program Node7;

{$MODE TP}
{$R-}

uses
  TcResult, TcStr, TcArena, TcFrame, TcMux, TcXml, TcSoap, TcPort,
  DcdTypes, DcdSrv, DmDecide;

{$I errcodes.inc}

const
  { Арена на канал: строки разобранного запроса смотрят внутрь неё и
    обязаны пережить решение. Двух килобайт хватает на предельный
    конверт с запасом — самый длинный, create-comment, занимает 721
    байт вместе с разметкой. }
  ArenaPerChan = 2048;

type
  TOpKind = (opNone, opRegister, opPost, opComment, opDelete,
             opFollow, opUnfollow, opBan, opPing);

  PChanCtx = ^TChanCtx;
  TChanCtx = record
    Arena:     TArena;
    Reader:    TSoapReader;
    Kind:      TOpKind;
    Fields:    Integer;
    Unknowns:  Integer;
    BadValues: Integer;
    Broken:    Boolean;   { конверт отвергнут разборщиком }

    { Разобранный запрос. Вариантная запись, а не восемь полей: они
      никогда не нужны одновременно, а место в дальней куче считается. }
    case Integer of
      1: (RqReg:  TRegisterUserRequest);
      2: (RqPost: TCreatePostRequest);
      3: (RqComm: TCreateCommentRequest);
      4: (RqDel:  TDeletePostRequest);
      5: (RqFol:  TFollowUserRequest);
      6: (RqUnf:  TUnfollowUserRequest);
      7: (RqBan:  TBanUserRequest);
      8: (RqPing: TPingRequest);
  end;

var
  Ctx: array[FirstDataChan..LastDataChan] of PChanCtx;

  { Канал, чей конверт разбирается прямо сейчас. Обработчики полей
    контекста не принимают — такова подпись в tcsoap, — а разбор идёт
    синхронно внутри MuxFeedByte, поэтому одной переменной довольно. }
  Cur: Byte;

  { Метрики ноды. Отвечаем ими на пинг, а не выдумываем нули.

    ArenaPeak — НАСТОЯЩИЙ пик занятости арены, снятый с той арены,
    которая только что отработала. Раньше на месте пика уезжало число
    обслуженных команд: величина правдоподобная, растущая, похожая на
    пик — и потому неотличимая от него снаружи. Проверка здоровья
    прочитала бы «1738 из 2048» и решила бы, что нода на грани.

    Метрика, которая врёт, хуже отсутствующей: отсутствующую видно. }
  Served:    LongInt;
  Refused:   LongInt;
  Malformed: LongInt;
  ArenaPeak: Word;

{ ------------------------------------------------------------------
  Разбор конверта
  ------------------------------------------------------------------ }

function KindOf(const Op: string): TOpKind;
begin
  if Op = 'RegisterUserRequest' then KindOf := opRegister
  else if Op = 'CreatePostRequest' then KindOf := opPost
  else if Op = 'CreateCommentRequest' then KindOf := opComment
  else if Op = 'DeletePostRequest' then KindOf := opDelete
  else if Op = 'FollowUserRequest' then KindOf := opFollow
  else if Op = 'UnfollowUserRequest' then KindOf := opUnfollow
  else if Op = 'BanUserRequest' then KindOf := opBan
  else if Op = 'PingRequest' then KindOf := opPing
  else KindOf := opNone;
end;

procedure OnOp(const Op: TStr); far;
begin
  Ctx[Cur]^.Kind := KindOf(StrHead(Op));
end;

procedure OnField(const Group, Field, Value: TStr); far;
var
  C: PChanCtx;
  O: TFillOutcome;
begin
  C := Ctx[Cur];
  Inc(C^.Fields);
  case C^.Kind of
    opRegister: O := DcdSrv.FillRegisterUser(C^.RqReg, Group, Field, Value);
    opPost:     O := DcdSrv.FillCreatePost(C^.RqPost, Group, Field, Value);
    opComment:  O := DcdSrv.FillCreateComment(C^.RqComm, Group, Field, Value);
    opDelete:   O := DcdSrv.FillDeletePost(C^.RqDel, Group, Field, Value);
    opFollow:   O := DcdSrv.FillFollowUser(C^.RqFol, Group, Field, Value);
    opUnfollow: O := DcdSrv.FillUnfollowUser(C^.RqUnf, Group, Field, Value);
    opBan:      O := DcdSrv.FillBanUser(C^.RqBan, Group, Field, Value);
    opPing:     O := DcdSrv.FillPing(C^.RqPing, Group, Field, Value);
  else
    O := foUnknown;
  end;
  case O of
    foUnknown:  Inc(C^.Unknowns);
    foBadValue: Inc(C^.BadValues);
  end;
end;

{ ------------------------------------------------------------------
  Ответ
  ------------------------------------------------------------------ }

function Sink(const F: TFrame; More: Boolean): Boolean; far;
var
  R: TResult;
begin
  R := MuxReply(F.Channel, F, More);
  Sink := R.Ok;
end;

{ Ответ с одним лишь кодом отказа: так отвечаем, когда до решения дело
  не дошло — конверт не разобрался или операция неизвестна. }
procedure ReplyError(Chan: Byte; const Code: string);
var
  W: TSoapWriter;
  D: TDecision;
begin
  FillChar(D, SizeOf(D), 0);
  D.accepted := False;
  D.errorCode := StrView(Code);

  SoapWriterInit(W, Chan, Sink);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
end;

procedure ReplyDecision(Chan: Byte; const D: TDecision);
var
  W: TSoapWriter;
begin
  SoapWriterInit(W, Chan, Sink);
  SoapBeginEnvelope(W);
  WriteDecision(W, 'DecisionResponse', D);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
end;

procedure ReplyPing(Chan: Byte; const Req: TPingRequest);
var
  W: TSoapWriter;
  P: TPingResponse;
  PS: TPortStats;
  MS: TMuxStats;
begin
  PS := PortGetStats;
  MS := MuxGetStats;

  FillChar(P, SizeOf(P), 0);
  { Тот же нонс: так спрашивающий отличает свой ответ от чужого, а
    заодно видит, что круг замкнулся именно на нём. }
  P.nonce := Req.nonce;
  P.fibersInUse := MuxActive;
  { Пик и ёмкость вместе. Пик без ёмкости не говорит ничего: «1900» —
    это спокойствие при ёмкости 8192 и тревога при 2048. }
  P.arenaHighMark := ArenaPeak;
  P.arenaCapacity := ArenaPerChan;
  P.commandsServed := Served;
  P.commandsRefused := Refused + MS.Refused;
  P.commandsMalformed := Malformed;
  { Ошибки линии и байты — то, чем на самом деле ставится диагноз.
    Именно они показали разорванные кадры: tx=74 против err=150. Пока
    их было видно только в NODE7.LOG, снаружи это выглядело как
    «нода молчит». }
  P.lineErrors := PS.LineErrs + PS.Overruns;
  P.rxBytes := PS.RxBytes;
  P.txBytes := PS.TxBytes;

  SoapWriterInit(W, Chan, Sink);
  SoapBeginEnvelope(W);
  { Пишет генератор, а не эти строки. Рукописный писатель ответа — это
    второй экземпляр контракта: поле, добавленное в WSDL, в него не
    попадёт вовсе, и узнать об этом будет неоткуда. Так уже вышло с
    пространством имён — его тут забыли, и метрики приезжали нулями. }
  WritePingResponse(W, P);
  SoapEndEnvelope(W);
  SoapWriterFlush(W);
end;

{ ------------------------------------------------------------------
  Команда
  ------------------------------------------------------------------ }

procedure Decide(Chan: Byte);
var
  C: PChanCtx;
  D: TDecision;
  R: TResult;
begin
  C := Ctx[Chan];

  if C^.Broken or (C^.Unknowns > 0) or (C^.BadValues > 0) then
  begin
    { Конверт не разобрался или в нём поля, которых контракт не
      объявляет. Решать по нему нельзя: ядро обязано отказать, а не
      додумывать (INSUFFICIENT_CONTEXT из errors.yaml). }
    Inc(Malformed);
    ReplyError(Chan, ERR_MALFORMED_ENVELOPE);
    Exit;
  end;

  FillChar(D, SizeOf(D), 0);
  case C^.Kind of
    opRegister: R := DecideRegisterUser(C^.Arena, C^.RqReg, D);
    opPost:     R := DecideCreatePost(C^.Arena, C^.RqPost, D);
    opComment:  R := DecideCreateComment(C^.Arena, C^.RqComm, D);
    opDelete:   R := DecideDeletePost(C^.Arena, C^.RqDel, D);
    opFollow:   R := DecideFollowUser(C^.Arena, C^.RqFol, D);
    opUnfollow: R := DecideUnfollowUser(C^.Arena, C^.RqUnf, D);
    opBan:      R := DecideBanUser(C^.Arena, C^.RqBan, D);
    opPing:
      begin
        ReplyPing(Chan, C^.RqPing);
        Inc(Served);
        Exit;
      end;
  else
    { Имя операции не из контракта. Это не пользовательская ошибка, а
      расхождение сторон, и молчать о нём нельзя. }
    Inc(Refused);
    ReplyError(Chan, ERR_MALFORMED_ENVELOPE);
    Exit;
  end;

  if not R.Ok then
  begin
    { Решение не получилось составить: не хватило арены на события.
      Отказ от имени ядра честнее молчания. }
    Inc(Refused);
    ReplyError(Chan, ERR_DECIDER_PANIC);
    Exit;
  end;

  ReplyDecision(Chan, D);
  Inc(Served);
  { Пик снимается ПОСЛЕ решения: арена к этому моменту держит и
    разобранный запрос, и события ответа — то есть всё, что команда
    вообще занимала. Снятый раньше, он показывал бы половину. }
  if C^.Arena.HighMark > ArenaPeak then
    ArenaPeak := C^.Arena.HighMark;
end;

procedure OnCommand(Chan: Byte; const F: TFrame; First, Last: Boolean); far;
var
  C: PChanCtx;
begin
  if (Chan < FirstDataChan) or (Chan > LastDataChan) then
    Exit;
  C := Ctx[Chan];
  if C = nil then
    Exit;

  Cur := Chan;

  if First then
  begin
    { Новая команда: всё прежнее с этого канала больше не нужно, и
      арена сбрасывается целиком. Освобождать по кусочку в прикладном
      коде запрещено — на то она и арена. }
    ArenaReset(C^.Arena);
    FillChar(C^.RqComm, SizeOf(C^.RqComm), 0);
    C^.Kind := opNone;
    C^.Fields := 0;
    C^.Unknowns := 0;
    C^.BadValues := 0;
    C^.Broken := False;
    SoapReaderInit(C^.Reader, C^.Arena, OnOp, OnField);
  end;

  if (F.Len > 0) and (not C^.Broken) then
    if not SoapFeed(C^.Reader, F.Payload, F.Len) then
      C^.Broken := True;

  if not Last then
    Exit;

  if not C^.Broken then
    if not SoapFinish(C^.Reader).Ok then
      C^.Broken := True;

  Decide(Chan);

  { Канал свободен: ответ отправлен, состояние больше не нужно. }
  MuxRelease(Chan);
end;

procedure OnControl(const F: TFrame); far;
begin
  { Управляющий канал пока несёт только приветствие, и отвечать на него
    нечем: рукопожатие ведёт сам драйвер порта. Обработчик существует,
    чтобы эти кадры не считались неприкаянными — иначе полезный
    счётчик превратился бы в шум. }
end;

{ ------------------------------------------------------------------
  Часы и цикл
  ------------------------------------------------------------------ }

{ Тик BIOS: 0040:006C, примерно 18.2 в секунду.

  Именно эти часы, а не счётчик оборотов цикла: IdleTicks и
  HelloEveryTicks в tcport заданы в тиках реального времени, и цикл,
  крутящийся миллион раз в секунду, объявил бы паузу на первом же
  обороте. }
function NowTicks: LongInt;
begin
{$IFDEF CPU16}
  NowTicks := MemL[$0040:$006C];
{$ELSE}
  { На нативном таргете часов BIOS нет; программа собирается здесь
    только затем, чтобы её проверял тот же компилятор с диапазонами. }
  NowTicks := 0;
{$ENDIF}
end;

{ Куда нода сообщает, чем кончился её запуск.

  Под DOSBox вывод программы уходит на эмулированный экран, а не наружу:
  снаружи ноду не слышно вовсе, и «не поздоровалась» неотличимо от «не
  запустилась». Файл закрывается сразу после записи — программа дальше
  не возвращается, и недописанный буфер остался бы недописанным
  навсегда. }
procedure Note(const S: string);
var
  F: Text;
begin
{$I-}
  Assign(F, 'NODE7.LOG');
  Append(F);
  if IOResult <> 0 then
  begin
    Rewrite(F);
    if IOResult <> 0 then
      Exit;
  end;
  WriteLn(F, S);
  Close(F);
{$I+}
  if IOResult <> 0 then
    ;
end;

{ Число строкой: WriteLn в файл умеет, но собирать из кусков удобнее
  одной функцией, чем городить Write подряд. }
function IntStr(V: LongInt): string;
var
  S: string;
begin
  Str(V, S);
  IntStr := S;
end;

var
  I: Integer;
  R: TResult;
  Tick, NextNote: LongInt;
  Notes: Integer;
  PS: TPortStats;
  MS: TMuxStats;

begin
  WriteLn('NODE-7');
  Note('NODE-7 стартовала');

  for I := FirstDataChan to LastDataChan do
  begin
    New(Ctx[I]);
    if Ctx[I] = nil then
    begin
      WriteLn('не хватило дальней кучи на канал ', I);
      Note('не хватило дальней кучи');
      Halt(1);
    end;
    FillChar(Ctx[I]^, SizeOf(TChanCtx), 0);
    R := ArenaCreate(Ctx[I]^.Arena, ArenaPerChan, 'chan');
    if not R.Ok then
    begin
      WriteLn('не создалась арена канала ', I);
      Note('не создалась арена канала');
      Halt(1);
    end;
  end;

  MuxReset;
  MuxSetCommandHandler(OnCommand);
  MuxSetControlHandler(OnControl);

  R := PortOpen(Com1Base, Divisor115200);
  if not R.Ok then
  begin
    WriteLn('не открылся порт');
    Note('НЕ ОТКРЫЛСЯ ПОРТ: ' + R.Code);
    Halt(1);
  end;

  WriteLn('порт открыт, ждём гейтвей');
  Note('порт открыт, каналов ' + Chr(Ord('0') + LastDataChan div 10)
       + Chr(Ord('0') + LastDataChan mod 10));

  { Цикл до конца света. Останавливать ноду нечем и незачем: она живёт
    столько же, сколько эмулятор, а её остановка — это остановка
    эмулятора.

    Счётчики выкладываются несколько раз в начале жизни и потом
    перестают: под эмулятором ноду не слышно, и «байты не идут»
    неотличимо от «идут, но не туда». Писать их постоянно нельзя —
    запись в файл под DOS стоит дороже самого цикла. }
  NextNote := 18;
  Notes := 0;
  while True do
  begin
    Tick := NowTicks;
    PortPump(Tick);
    if (Notes < 4) and (Tick >= NextNote) then
    begin
      PS := PortGetStats;
      MS := MuxGetStats;
      Note('тик ' + IntStr(Tick)
           + ' tx=' + IntStr(PS.TxBytes)
           + ' rx=' + IntStr(PS.RxBytes)
           + ' err=' + IntStr(PS.LineErrs)
           + ' idle=' + IntStr(PS.Idles)
           + ' послано=' + IntStr(MS.Sent)
           + ' принято=' + IntStr(MS.Received)
           + ' команд=' + IntStr(MS.Commands));
      Inc(Notes);
      NextNote := Tick + 36;
    end;
  end;
end.
