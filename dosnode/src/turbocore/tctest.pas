{ ===================================================================
  TurboCore · тестовый раннер с выводом в формате TAP.

  Почему TAP, а не собственный формат: его понимают готовые парсеры, он
  читается глазами, и он текстовый — а значит переживает и DOSBox, и
  перенаправление в файл.

  Почему отчёт пишется в файл, а не только в консоль: DOSBox не обязан
  честно пробрасывать код возврата DOS-программы наружу (docs/RISKS.md, R3).
  Вердикт выносит обвязка ops/ci/run-dos-tap.sh по содержимому файла.

  Строка плана 1..N печатается ПЕРВОЙ и обязательна. Обвязка сверяет число
  выполненных проверок с планом: если программа упала на середине, в отчёте
  останутся одни ok, и без этой сверки прогон выглядел бы успешным.
  =================================================================== }
unit TcTest;

{$MODE TP}

interface

uses
  TcResult;

{ Начать прогон. FileName — куда писать TAP; пустая строка означает
  только консоль (удобно на нативном таргете). }
procedure TestBegin(const FileName: string; Planned: Integer);

{ Одна проверка. }
procedure TestOk(const Name: string; Cond: Boolean);

{ Проверки-помощники: сообщение об отказе получается информативнее,
  чем у голого TestOk с уже вычисленным условием. }
procedure TestEqInt(const Name: string; Got, Want: LongInt);
procedure TestEqStr(const Name: string; const Got, Want: string);
procedure TestTrue(const Name: string; Cond: Boolean);
procedure TestFalse(const Name: string; Cond: Boolean);

{ Результат операции: при отказе в сообщение попадает код. }
procedure TestResultOk(const Name: string; const R: TResult);
procedure TestResultErr(const Name: string; const R: TResult;
                        const WantCode: TErrCode);

{ Диагностика: строка с # в начале, не считается проверкой. }
procedure TestDiag(const S: string);
procedure TestDiagInt(const S: string; N: LongInt);

{ Завершить прогон. Возвращает число провалов; программа обязана
  завершиться с этим кодом. }
function TestEnd: Integer;

implementation

var
  Report:   Text;
  ToFile:   Boolean;
  Count:    Integer;
  Failures: Integer;
  Planned_: Integer;

procedure Emit(const S: string);
begin
  WriteLn(S);
  if ToFile then
  begin
    WriteLn(Report, S);
    Flush(Report);   { при падении на середине уже написанное должно уцелеть }
  end;
end;

function IntStr(N: LongInt): string;
var
  S: string;
begin
  Str(N, S);
  IntStr := S;
end;

procedure TestBegin(const FileName: string; Planned: Integer);
begin
  Count := 0;
  Failures := 0;
  Planned_ := Planned;
  ToFile := FileName <> '';
  if ToFile then
  begin
    Assign(Report, FileName);
    Rewrite(Report);
  end;
  Emit('1..' + IntStr(Planned));
end;

procedure TestOk(const Name: string; Cond: Boolean);
begin
  Inc(Count);
  if Cond then
    Emit('ok ' + IntStr(Count) + ' - ' + Name)
  else
  begin
    Emit('not ok ' + IntStr(Count) + ' - ' + Name);
    Inc(Failures);
  end;
end;

procedure TestDiag(const S: string);
begin
  Emit('# ' + S);
end;

procedure TestDiagInt(const S: string; N: LongInt);
begin
  Emit('# ' + S + ' ' + IntStr(N));
end;

procedure TestEqInt(const Name: string; Got, Want: LongInt);
begin
  TestOk(Name, Got = Want);
  if Got <> Want then
  begin
    TestDiag('  получено: ' + IntStr(Got));
    TestDiag('  ожидалось: ' + IntStr(Want));
  end;
end;

procedure TestEqStr(const Name: string; const Got, Want: string);
begin
  TestOk(Name, Got = Want);
  if Got <> Want then
  begin
    TestDiag('  получено: "' + Got + '"');
    TestDiag('  ожидалось: "' + Want + '"');
  end;
end;

procedure TestTrue(const Name: string; Cond: Boolean);
begin
  TestOk(Name, Cond);
end;

procedure TestFalse(const Name: string; Cond: Boolean);
begin
  TestOk(Name, not Cond);
end;

procedure TestResultOk(const Name: string; const R: TResult);
begin
  TestOk(Name, R.Ok);
  if not R.Ok then
    TestDiag('  отказ с кодом: ' + R.Code);
end;

procedure TestResultErr(const Name: string; const R: TResult;
                        const WantCode: TErrCode);
begin
  TestOk(Name, (not R.Ok) and (R.Code = WantCode));
  if R.Ok then
    TestDiag('  ожидался отказ ' + WantCode + ', получен успех')
  else if R.Code <> WantCode then
  begin
    TestDiag('  получен код: ' + R.Code);
    TestDiag('  ожидался код: ' + WantCode);
  end;
end;

function TestEnd: Integer;
begin
  { Расхождение с планом опаснее провала: оно означает, что часть проверок
    не выполнилась, а мы этого не заметили. }
  if Count <> Planned_ then
  begin
    Emit('# ВНИМАНИЕ: выполнено ' + IntStr(Count) +
         ' проверок из ' + IntStr(Planned_));
    Inc(Failures);
  end;

  if Failures = 0 then
    Emit('# ИТОГ: пройдено ' + IntStr(Count))
  else
    Emit('# ИТОГ: провалов ' + IntStr(Failures) + ' из ' + IntStr(Count));

  if ToFile then
    Close(Report);
  TestEnd := Failures;
end;

end.
