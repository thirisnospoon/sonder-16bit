{ ===================================================================
  Сколько места займёт состояние ноды.

  Программа NODE-7 обязана держать состояние на каждый из шестнадцати
  каналов: разборщик конверта, разобранный запрос, арену под строки.
  Сегмент данных в модели large — 64 КБ, и упереться в него на
  шестнадцатом канале хуже, чем узнать об этом заранее.

  Числа печатаются, а не прикидываются: раскладка записей зависит от
  таргета, и на i8086 она другая, чем на нативном.
  =================================================================== }
program Sizes;

{$MODE TP}
{$R-}

uses
  TcResult, TcArena, TcFrame, TcXml, TcSoap, DcdTypes;

procedure Say(const Name: string; Bytes: LongInt);
begin
  WriteLn(Name, ': ', Bytes);
end;

var
  Biggest: LongInt;

begin
{$IFDEF CPU16}
  WriteLn('таргет: i8086-msdos');
{$ELSE}
  WriteLn('таргет: нативный');
{$ENDIF}

  Say('TXmlParser', SizeOf(TXmlParser));
  Say('TSoapReader', SizeOf(TSoapReader));
  Say('TFrame', SizeOf(TFrame));
  Say('TArena', SizeOf(TArena));

  Say('TRegisterUserRequest', SizeOf(TRegisterUserRequest));
  Say('TCreatePostRequest', SizeOf(TCreatePostRequest));
  Say('TCreateCommentRequest', SizeOf(TCreateCommentRequest));
  Say('TDeletePostRequest', SizeOf(TDeletePostRequest));
  Say('TFollowUserRequest', SizeOf(TFollowUserRequest));
  Say('TUnfollowUserRequest', SizeOf(TUnfollowUserRequest));
  Say('TBanUserRequest', SizeOf(TBanUserRequest));
  Say('TPingRequest', SizeOf(TPingRequest));
  Say('TDecision', SizeOf(TDecision));

  { Самый толстый запрос: под него и придётся заводить место, если
    держать разобранное в общем поле. }
  Biggest := SizeOf(TCreateCommentRequest);
  if SizeOf(TFollowUserRequest) > Biggest then
    Biggest := SizeOf(TFollowUserRequest);
  if SizeOf(TBanUserRequest) > Biggest then
    Biggest := SizeOf(TBanUserRequest);
  Say('самый толстый запрос', Biggest);

  WriteLn;
  Say('на канал (разборщик + запрос)', SizeOf(TSoapReader) + Biggest);
  Say('на 16 каналов', LongInt(SizeOf(TSoapReader) + Biggest) * 16);
end.
