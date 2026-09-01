{ ===================================================================
  Тесты фундамента TurboCore: TcResult и TcArena.

  Один и тот же файл собирается обоими таргетами:

      native — за секунды, с проверками диапазонов и границ;
      msdos  — под DOSBox, чтобы поведение на боевом таргете совпадало.

  Расхождение между таргетами — это дефект, а не особенность. Поэтому
  проверки одинаковы, а различается только объём нагрузочных циклов:
  миллион итераций на нативном и пятьдесят тысяч на 16-битном, где
  каждая обходится дороже.
  =================================================================== }
program TstCore;

{$MODE TP}
{$R-}

uses
  TcResult, TcArena, TcTest;

{$I errcodes.inc}

const
{$IFDEF CPU16}
  StressCycles = 50000;
  TapFile      = 'TSTCORE.TAP';
{$ELSE}
  StressCycles = 1000000;
  TapFile      = 'tstcore.tap';
{$ENDIF}

  PlannedTests = 49;

var
  A:      TArena;
  R:      TResult;
  P, Q:   Pointer;
  I:      LongInt;
  Src:    array[0..15] of Byte;
  Dst:    PByte;
  BaseBefore: Pointer;
  OkCount: Integer;

begin
  TestBegin(TapFile, PlannedTests);

  TestDiag('TurboCore: фундамент');
{$IFDEF CPU16}
  TestDiag('таргет: i8086-msdos');
{$ELSE}
  TestDiag('таргет: нативный');
{$ENDIF}
  TestDiagInt('SizeOf(Pointer)', SizeOf(Pointer));
  TestDiagInt('выравнивание арены', ArenaAlign);
  TestDiagInt('циклов нагрузки', StressCycles);

  { ================================================================
    TcResult
    ================================================================ }

  R := Ok;
  TestTrue('Ok даёт успех', R.Ok);
  TestEqStr('у успеха пустой код', R.Code, '');
  TestFalse('Failed на успехе ложно', Failed(R));

  R := Err(ERR_ACTOR_BANNED);
  TestFalse('Err даёт отказ', R.Ok);
  TestEqStr('код отказа сохранён', R.Code, 'ACTOR_BANNED');
  TestTrue('Failed на отказе истинно', Failed(R));

  { Отказ без кода — дефект вызывающего. Молчаливый пустой код сделал бы
    отказ неопознаваемым, поэтому подставляется заметный. }
  R := Err('');
  TestFalse('пустой код всё равно отказ', R.Ok);
  TestEqStr('пустой код заменён на заметный', R.Code, 'DECIDER_PANIC');

  TestResultOk('TestResultOk принимает успех', Ok);
  TestResultErr('TestResultErr сверяет код',
                Err(ERR_NICK_TAKEN), ERR_NICK_TAKEN);

  TestEqStr('FirstErr возвращает первый отказ',
            FirstErr(Err(ERR_SELF_FOLLOW), Err(ERR_NICK_TAKEN)).Code,
            'SELF_FOLLOW');
  TestEqStr('FirstErr пропускает успех',
            FirstErr(Ok, Err(ERR_NICK_TAKEN)).Code, 'NICK_TAKEN');
  TestTrue('FirstErr от двух успехов — успех', FirstErr(Ok, Ok).Ok);

  { Сгенерированные коды обязаны помещаться в TErrCode целиком:
    иначе присваивание молча обрежет строку, и код перестанет
    совпадать с тем, что ждёт оболочка. }
  TestDiagInt('самый длинный код в контракте', ERR_MAX_CODE_LEN);
  TestDiagInt('вместимость TErrCode', MaxErrCodeLen);
  TestTrue('все коды контракта помещаются в TErrCode',
           ERR_MAX_CODE_LEN <= MaxErrCodeLen);
  TestEqInt('число кодов в контракте', ERR_CODE_COUNT, 25);

  { ================================================================
    TcArena: жизненный цикл
    ================================================================ }

  R := ArenaCreate(A, 1024, 'test');
  TestResultOk('арена создаётся', R);
  TestEqInt('свежая арена пуста', A.Used, 0);
  TestEqInt('вся ёмкость свободна', ArenaAvail(A), 1024);
  TestEqStr('имя арены сохранено', A.Name, 'test');

  R := ArenaCreate(A, 0, 'zero');
  TestResultErr('арена нулевого размера отвергается',
                R, ERR_INSUFFICIENT_CONTEXT);

  { ================================================================
    TcArena: выделение
    ================================================================ }

  R := ArenaCreate(A, 1024, 'alloc');
  BaseBefore := A.Base;

  R := ArenaAlloc(A, 10, P);
  TestResultOk('выделение проходит', R);
  TestTrue('указатель не пуст', P <> nil);

  { Десять байт округляются вверх до кратного выравниванию: невыровненный
    доступ на i8086 медленнее, а на других архитектурах — ловушка. }
  TestEqInt('размер округлён до выравнивания',
            A.Used, ((10 + ArenaAlign - 1) div ArenaAlign) * ArenaAlign);

  TestEqInt('счётчик выделений вырос', A.Allocs, 1);
  TestEqInt('отметка максимума обновилась', A.HighMark, A.Used);

  R := ArenaAlloc(A, 0, P);
  TestResultErr('выделение нуля байт отвергается',
                R, ERR_INSUFFICIENT_CONTEXT);

  { Заведомо больше ёмкости. Важно, что арена при этом остаётся целой. }
  R := ArenaAlloc(A, 4000, P);
  TestResultErr('выделение больше ёмкости отвергается',
                R, ERR_INSUFFICIENT_CONTEXT);
  TestTrue('после отказа база не изменилась', A.Base = BaseBefore);

  { Проверка защиты от переполнения: A.Used + Need могло бы завернуться
    через ноль и пройти наивную проверку, испортив память. }
  R := ArenaCreate(A, 100, 'ovf');
  R := ArenaAlloc(A, 64, P);
  R := ArenaAlloc(A, 65500, Q);
  TestResultErr('переполнение при сложении не проходит',
                R, ERR_INSUFFICIENT_CONTEXT);
  TestEqInt('после отказа занято прежнее',
            A.Used, ((64 + ArenaAlign - 1) div ArenaAlign) * ArenaAlign);

  { Регрессия на найденный дуальной сборкой баг.

    AlignUp(65535) при выравнивании 8 давал 65536, а Word заворачивался
    в ноль. ArenaAlloc возвращал успех, ничего не выделив: вызывающий
    получал указатель и писал по нему 65535 байт в арену на 2 килобайта.
    На 16-битном таргете это проходило молча, нативный падал с проверкой
    диапазонов. }
  R := ArenaCreate(A, 2048, 'align');
  R := ArenaAlloc(A, 65535, P);
  TestResultErr('запрос 65535 байт отвергается, а не переполняет выравнивание',
                R, ERR_INSUFFICIENT_CONTEXT);
  TestEqInt('после отказа на переполнении арена пуста', A.Used, 0);
  TestEqInt('отказ на переполнении не считается выделением', A.Allocs, 0);

  { ================================================================
    TcArena: обнуление и копирование
    ================================================================ }

  R := ArenaCreate(A, 256, 'zerofill');
  R := ArenaAlloc(A, 8, P);
  FillChar(P^, 8, $FF);
  R := ArenaAllocZero(A, 8, Q);
  Dst := PByte(Q);
  OkCount := 0;
  for I := 0 to 7 do
  begin
    if Dst^ = 0 then Inc(OkCount);
    Inc(Dst);
  end;
  TestEqInt('AllocZero обнуляет всю выданную память', OkCount, 8);

  for I := 0 to 15 do
    Src[I] := Byte(I + 1);
  R := ArenaDup(A, @Src[0], 16, Q);
  TestResultOk('ArenaDup копирует', R);
  Dst := PByte(Q);
  OkCount := 0;
  for I := 0 to 15 do
  begin
    if Dst^ = Byte(I + 1) then Inc(OkCount);
    Inc(Dst);
  end;
  TestEqInt('копия совпадает с оригиналом побайтно', OkCount, 16);

  R := ArenaDup(A, nil, 16, Q);
  TestResultErr('копирование из nil отвергается',
                R, ERR_INSUFFICIENT_CONTEXT);

  { ================================================================
    TcArena: сброс
    ================================================================ }

  R := ArenaCreate(A, 512, 'reset');
  R := ArenaAlloc(A, 300, P);
  { Округление вверх, а не «до ближайшего»: при выравнивании 2 число 300
    уже кратно и не меняется, при 8 — вырастает до 304. Первая версия этой
    строки была написана под выравнивание 8 и на 16-битном таргете упала —
    ровно то расхождение, ради поимки которого существует дуальная сборка. }
  TestEqInt('перед сбросом занято',
            A.Used, ((300 + ArenaAlign - 1) div ArenaAlign) * ArenaAlign);
  ArenaReset(A);
  TestEqInt('после сброса занято ноль', A.Used, 0);
  TestTrue('отметка максимума переживает сброс', A.HighMark >= 300);
  TestEqInt('счётчик сбросов вырос', A.Resets, 1);
  TestTrue('арена жива после сброса', A.Live);

  { ================================================================
    Нагрузка: цикл выделения и сброса не должен ничего накапливать
    ================================================================ }

  R := ArenaCreate(A, 4096, 'stress');
  BaseBefore := A.Base;
  for I := 1 to StressCycles do
  begin
    R := ArenaAlloc(A, 64, P);
    if not R.Ok then Break;
    R := ArenaAlloc(A, 128, Q);
    if not R.Ok then Break;
    ArenaReset(A);
  end;

  TestResultOk('нагрузочный цикл дошёл до конца', R);
  TestEqInt('после нагрузки занято ноль', A.Used, 0);
  TestTrue('база не сдвинулась за всю нагрузку', A.Base = BaseBefore);
  TestEqInt('число сбросов совпало с числом циклов',
            A.Resets, StressCycles);
  TestTrue('отметка максимума не выросла сверх ожидаемого',
           A.HighMark <= 256);
  TestDiagInt('выделений за прогон', A.Allocs);
  TestDiagInt('отметка максимума, байт', A.HighMark);

  ArenaDestroy(A);
  TestEqInt('после уничтожения ёмкость обнулена', A.Capacity, 0);
  TestFalse('после уничтожения арена не жива', A.Live);

  Halt(TestEnd);
end.
