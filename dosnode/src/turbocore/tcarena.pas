{ ===================================================================
  TurboCore · арена памяти.

  Ручное освобождение в среде без исключений — самый надёжный способ
  получить утечку или двойное освобождение. Поэтому память здесь не
  освобождается по одному объекту вовсе.

  Модель владения:

      арена создаётся на обработку одной команды;
      всё, что нужно во время обработки, берётся из неё;
      по завершении арена сбрасывается целиком одним присваиванием.

  FreeMem в прикладном коде запрещён линтером. Это не стилистика: при
  отказе на середине обработки откатывать частично сделанные выделения
  без исключений практически невозможно, а сброс арены делает это
  бесплатно и всегда.

  Расплата — фрагментации нет, но и переиспользования внутри команды нет.
  Пик потребления известен заранее и меряется HighMark, а не набирается
  случайно.

  Отладочная сборка (DEBUG) заполняет освобождаемую память ядовитым
  узором: обращение к данным после сброса арены — частая ошибка, и
  ловить её надо сразу, а не по странному поведению через час.
  =================================================================== }
unit TcArena;

{$MODE TP}

interface

uses
  TcResult, TcStr;

const
  { Выравнивание. На 16 битах слово, на нативном таргете указатель.
    Невыровненный доступ на i8086 работает, но медленнее; на других
    архитектурах он же — ловушка. }
{$IFDEF CPU16}
  ArenaAlign = 2;
{$ELSE}
  ArenaAlign = 8;
{$ENDIF}

  PoisonByte  = $DD;   { чем травим память при сбросе в отладке }
  ArenaNameLen = 15;
  MaxWord     = 65535;

type
  TArena = record
    Base:     Pointer;              { начало блока, полученного у кучи }
    Capacity: Word;                 { сколько всего }
    Used:     Word;                 { сколько занято сейчас }
    HighMark: Word;                 { максимум за всю жизнь арены }
    Allocs:   LongInt;              { счётчик выделений, метрика }
    Resets:   LongInt;              { счётчик сбросов, метрика }
    Name:     string[ArenaNameLen]; { для логов и метрик }
    Live:     Boolean;
  end;

  { Указатель на арену. Нужен тем, кто хранит арену в своей записи, а не
    получает параметром: разборщик конверта живёт дольше одного вызова. }
  PArena = ^TArena;

{ Создать арену заданного размера. Память берётся у кучи один раз. }
function ArenaCreate(var A: TArena; Capacity: Word;
                     const Name: string): TResult;

{ Вернуть память куче. После этого арена непригодна до нового Create. }
procedure ArenaDestroy(var A: TArena);

{ Выделить Bytes байт. При нехватке возвращает отказ и НЕ трогает P:
  вызывающий обязан проверить результат, а не полученный указатель. }
function ArenaAlloc(var A: TArena; Bytes: Word; var P: Pointer): TResult;

{ Выделить и обнулить. Отдельная функция, потому что забыть FillChar
  после ArenaAlloc — типичная ошибка, а мусор в записи читается как
  правдоподобные данные. }
function ArenaAllocZero(var A: TArena; Bytes: Word; var P: Pointer): TResult;

{ Скопировать блок в арену. Возвращает указатель на копию. }
function ArenaDup(var A: TArena; Src: Pointer; Bytes: Word;
                  var P: Pointer): TResult;

{ Скопировать строку в арену. Копия принадлежит арене и потому переживает
  исходник — в отличие от результата StrView, который живёт ровно столько,
  сколько живёт то, на что он смотрит. }
function ArenaDupStr(var A: TArena; const Src: TStr; var Dst: TStr): TResult;

type
  { Наращиваемая строка в арене.

    Потоковый разбор выдаёт текст кусками, а поле по контракту бывает в
    четыре килобайта: фиксированного буфера под него нет ни в разборщике,
    ни в сегменте данных. Строка растёт прямо в арене, байт к байту, БЕЗ
    ВЫРАВНИВАНИЯ между кусками — иначе между ними легла бы набивка и
    строка перестала быть непрерывной.

    Отсюда единственное правило: пока строка растёт, из этой арены больше
    никто не выделяет. Чужое выделение вклинилось бы в середину, и
    получилась бы строка, содержащая чужие данные, — то есть
    правдоподобный неверный результат.

    Правило не на словах: BuildAppend сверяет, что арена стоит ровно там,
    где её оставили, и отказывает, если нет. Смещение, а не указатель,
    именно ради этой сверки. }
  TStrBuilder = record
    Start: Word;     { смещение начала строки в арене }
    Len:   Word;
    Live:  Boolean;
  end;

{ Начать строку в текущей позиции арены. }
function BuildBegin(var A: TArena; var B: TStrBuilder): TResult;

{ Дописать кусок. Отказ означает либо что арена кончилась, либо что из
  неё выделяли, пока строка росла. }
function BuildAppend(var A: TArena; var B: TStrBuilder;
                     Src: Pointer; Bytes: Word): TResult;

{ Закончить строку. Арена возвращается к выровненной позиции, чтобы
  следующее выделение снова было выровнено. }
function BuildFinish(var A: TArena; var B: TStrBuilder;
                     var S: TStr): TResult;

{ Бросить строку и вернуть арене её байты.

  Нужно потому, что потоковый разбор узнаёт о смысле элемента задним
  числом: пробелы между вложенными элементами приходят раньше, чем
  становится ясно, что элемент был не полем, а группой. Откат возможен
  ровно потому, что арена бампающая и после начала строки из неё никто
  не выделял — то же условие, что и у BuildAppend. }
procedure BuildCancel(var A: TArena; var B: TStrBuilder);

{ Сбросить арену целиком. Указатели, выданные ранее, становятся
  недействительными немедленно. }
procedure ArenaReset(var A: TArena);

{ Сколько свободно прямо сейчас. }
function ArenaAvail(const A: TArena): Word;

implementation

{ Округление вверх до кратного ArenaAlign.

  Возвращает False, если округление переполнило бы Word. Это не
  теоретическая осторожность: AlignUp(65535) при выравнивании 8 даёт
  65536, а Word заворачивается в ноль. Первая версия этой функции так и
  делала — на нативном таргете с проверкой диапазонов она падала, а на
  16-битном молча возвращала ноль, и ArenaAlloc отвечал успехом на запрос
  65535 байт, ничего не выделив. Вызывающий получал указатель и писал по
  нему, разрушая арену.

  Нашла это дуальная сборка: 16-битный таргет проглотил, нативный упал. }
function AlignUp(N: Word; var Res: Word): Boolean;
var
  R, Pad: Word;
begin
  R := N mod ArenaAlign;
  if R = 0 then
  begin
    Res := N;
    AlignUp := True;
    Exit;
  end;

  Pad := ArenaAlign - R;
  if N > MaxWord - Pad then
  begin
    AlignUp := False;
    Exit;
  end;

  Res := N + Pad;
  AlignUp := True;
end;

function ArenaCreate(var A: TArena; Capacity: Word;
                     const Name: string): TResult;
var
  P: Pointer;
begin
  FillChar(A, SizeOf(A), 0);

  if Capacity = 0 then
  begin
    ArenaCreate := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  GetMem(P, Capacity);
  if P = nil then
  begin
    ArenaCreate := Err('DECIDER_PANIC');
    Exit;
  end;

  A.Base := P;
  A.Capacity := Capacity;
  A.Used := 0;
  A.HighMark := 0;
  A.Allocs := 0;
  A.Resets := 0;
  A.Live := True;
  if Length(Name) > ArenaNameLen then
    A.Name := Copy(Name, 1, ArenaNameLen)
  else
    A.Name := Name;

  ArenaCreate := Ok;
end;

procedure ArenaDestroy(var A: TArena);
begin
  if A.Live and (A.Base <> nil) then
    FreeMem(A.Base, A.Capacity);
  FillChar(A, SizeOf(A), 0);
end;

function ArenaAlloc(var A: TArena; Bytes: Word; var P: Pointer): TResult;
var
  Need: Word;
  Base: PByte;
begin
  if not A.Live then
  begin
    ArenaAlloc := Err('DECIDER_PANIC');
    Exit;
  end;

  if Bytes = 0 then
  begin
    { Ноль байт — почти наверняка ошибка расчёта у вызывающего.
      Возвращать валидный указатель на пустоту опаснее, чем отказать. }
    ArenaAlloc := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  { Округление вверх само по себе может переполнить Word. }
  if not AlignUp(Bytes, Need) then
  begin
    ArenaAlloc := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  { Проверка до сложения: A.Used + Need могло бы переполнить Word и
    пройти проверку, испортив память. }
  if (Need > A.Capacity) or (A.Used > A.Capacity - Need) then
  begin
    ArenaAlloc := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  Base := PByte(A.Base);
  Inc(Base, A.Used);
  P := Pointer(Base);

  Inc(A.Used, Need);
  Inc(A.Allocs);
  if A.Used > A.HighMark then
    A.HighMark := A.Used;

  ArenaAlloc := Ok;
end;

function ArenaAllocZero(var A: TArena; Bytes: Word; var P: Pointer): TResult;
var
  R: TResult;
begin
  R := ArenaAlloc(A, Bytes, P);
  if R.Ok then
    FillChar(P^, Bytes, 0);
  ArenaAllocZero := R;
end;

function ArenaDup(var A: TArena; Src: Pointer; Bytes: Word;
                  var P: Pointer): TResult;
var
  R: TResult;
begin
  if Src = nil then
  begin
    ArenaDup := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;
  R := ArenaAlloc(A, Bytes, P);
  if R.Ok then
    Move(Src^, P^, Bytes);
  ArenaDup := R;
end;

function ArenaDupStr(var A: TArena; const Src: TStr; var Dst: TStr): TResult;
var
  R: TResult;
  P: Pointer;
begin
  { Пустая строка копируется в пустую: выделять ноль байт нельзя, а
    отказывать здесь незачем — пустое тело это законное значение. }
  if StrIsEmpty(Src) then
  begin
    Dst := StrNil;
    ArenaDupStr := Ok;
    Exit;
  end;

  R := ArenaAlloc(A, Src.Len, P);
  if not R.Ok then
  begin
    ArenaDupStr := R;
    Exit;
  end;

  Move(Src.Ptr^, P^, Src.Len);
  Dst.Ptr := PChar(P);
  Dst.Len := Src.Len;
  ArenaDupStr := Ok;
end;

procedure ArenaReset(var A: TArena);
begin
  if not A.Live then
    Exit;
{$IFDEF DEBUG}
  { Травим то, что было занято: обращение после сброса должно ломаться
    заметно, а не читать правдоподобный мусор. }
  if A.Used > 0 then
    FillChar(A.Base^, A.Used, PoisonByte);
{$ENDIF}
  A.Used := 0;
  Inc(A.Resets);
end;

function ArenaAvail(const A: TArena): Word;
begin
  if not A.Live then
    ArenaAvail := 0
  else
    ArenaAvail := A.Capacity - A.Used;
end;

{ ------------------------------------------------------------------
  Наращиваемая строка
  ------------------------------------------------------------------ }

procedure BuildCancel(var A: TArena; var B: TStrBuilder);
begin
  if not B.Live then
    Exit;
  { Откатываем только если из арены действительно никто не выделял:
    иначе мы вернули бы чужую память. }
  if A.Live and (A.Used - B.Start = B.Len) then
    A.Used := B.Start;
  B.Live := False;
  B.Len := 0;
end;

function BuildBegin(var A: TArena; var B: TStrBuilder): TResult;
begin
  B.Start := 0;
  B.Len := 0;
  B.Live := False;

  if not A.Live then
  begin
    BuildBegin := Err('DECIDER_PANIC');
    Exit;
  end;

  B.Start := A.Used;
  B.Live := True;
  BuildBegin := Ok;
end;

function BuildAppend(var A: TArena; var B: TStrBuilder;
                     Src: Pointer; Bytes: Word): TResult;
var
  Dst: PByte;
begin
  if (not A.Live) or (not B.Live) then
  begin
    BuildAppend := Err('DECIDER_PANIC');
    Exit;
  end;

  { Сверка написана как разность, а не как Start + Len: сумма могла бы
    переполнить Word на арене под предел, и проверка прошла бы там, где
    должна отказать. Used >= Start всегда, поэтому разность безопасна. }
  if A.Used - B.Start <> B.Len then
  begin
    { Из арены выделяли, пока строка росла. Продолжать нельзя: строка
      уже содержит чужие байты в середине. }
    B.Live := False;
    BuildAppend := Err('DECIDER_PANIC');
    Exit;
  end;

  if Bytes = 0 then
  begin
    { Пустой кусок законен: разборщик может выдать поле без текста. }
    BuildAppend := Ok;
    Exit;
  end;

  if Bytes > A.Capacity - A.Used then
  begin
    BuildAppend := Err('INSUFFICIENT_CONTEXT');
    Exit;
  end;

  Dst := PByte(A.Base);
  Inc(Dst, A.Used);
  Move(Src^, Dst^, Bytes);

  { Без выравнивания: байты обязаны лечь вплотную к предыдущему куску. }
  Inc(A.Used, Bytes);
  Inc(B.Len, Bytes);
  if A.Used > A.HighMark then
    A.HighMark := A.Used;

  BuildAppend := Ok;
end;

function BuildFinish(var A: TArena; var B: TStrBuilder;
                     var S: TStr): TResult;
var
  Base: PByte;
  Aligned: Word;
begin
  S := StrNil;

  if (not A.Live) or (not B.Live) then
  begin
    BuildFinish := Err('DECIDER_PANIC');
    Exit;
  end;
  if A.Used - B.Start <> B.Len then
  begin
    B.Live := False;
    BuildFinish := Err('DECIDER_PANIC');
    Exit;
  end;

  if B.Len > 0 then
  begin
    Base := PByte(A.Base);
    Inc(Base, B.Start);
    S.Ptr := PChar(Base);
    S.Len := B.Len;
  end;

  { Вернуть арену к выровненной позиции, иначе следующая запись легла бы
    со смещением и читалась бы медленнее, а на некоторых операциях —
    неверно. Если выравнивание не помещается, арена просто остаётся как
    есть: строка уже собрана, и терять её из-за набивки незачем. }
  if AlignUp(A.Used, Aligned) and (Aligned <= A.Capacity) then
  begin
    Inc(A.Allocs);
    A.Used := Aligned;
    if A.Used > A.HighMark then
      A.HighMark := A.Used;
  end;

  B.Live := False;
  BuildFinish := Ok;
end;

end.
