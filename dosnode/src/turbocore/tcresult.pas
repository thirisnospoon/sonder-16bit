{ ===================================================================
  TurboCore · результат операции.

  Исключений в диалекте TP7 нет, и это не ограничение, которое надо
  обходить, а определяющее свойство: ошибка здесь — возвращаемое
  значение, и игнорировать её так же неудобно, как в языках без
  исключений вообще.

  Код отказа — строка из contracts/errors/errors.yaml, а не перечисление.
  Причина в том, что код уходит по линии в SOAP и приходит оттуда же:
  преобразование в перечисление и обратно добавило бы таблицу, которая
  обязана совпадать со сгенерированной, и однажды разошлась бы с ней.

  Соглашение о вызовах во всём фреймворке:

      function ЧтоТоДелает(... ; var Out: T): TResult;

  Результат возвращается, выход пишется через var. Проверять результат
  обязательно: линтер запрещает вызов функции, возвращающей TResult,
  как процедуры.
  =================================================================== }
unit TcResult;

{$MODE TP}

interface

const
  { Самый длинный код в errors.yaml — STATE_VERSION_CONFLICT, 22 символа.
    Запас взят до 31, чтобы добавление кода не требовало правки типа.
    Тест tests/test_result проверяет, что все сгенерированные коды влезают. }
  MaxErrCodeLen = 31;

type
  TErrCode = string[MaxErrCodeLen];

  TResult = record
    Ok:   Boolean;
    Code: TErrCode;
  end;

{ Успех. }
function Ok: TResult;

{ Отказ с кодом из errors.yaml. }
function Err(const Code: TErrCode): TResult;

{ Читается лучше, чем R.Ok, там где выражение длинное. }
function Failed(const R: TResult): Boolean;

{ Первый отказ из двух: удобно склеивать проверки по цепочке.
  Если оба успешны — успех. }
function FirstErr(const A, B: TResult): TResult;

implementation

function Ok: TResult;
var
  R: TResult;
begin
  R.Ok := True;
  R.Code := '';
  Ok := R;
end;

function Err(const Code: TErrCode): TResult;
var
  R: TResult;
begin
  R.Ok := False;
  { Пустой код — это дефект вызывающего: отказ обязан быть опознаваем.
    Молча подставляем INTERNAL, но так, чтобы это бросалось в глаза. }
  if Code = '' then
    R.Code := 'DECIDER_PANIC'
  else
    R.Code := Code;
  Err := R;
end;

function Failed(const R: TResult): Boolean;
begin
  Failed := not R.Ok;
end;

function FirstErr(const A, B: TResult): TResult;
begin
  if not A.Ok then
    FirstErr := A
  else
    FirstErr := B;
end;

end.
