{ Проверка образа тулчейна: собирается ли вообще что-нибудь под i8086-msdos
  и что за модель памяти получилась. Запускается на сборке образа (компиляция)
  и под DOSBox (исполнение). }
program Smoke;

{$MODE TP}

begin
  WriteLn('sonder dos toolchain smoke');
  WriteLn('DSEG=', Dseg);
  WriteLn('SSEG=', Sseg);
  if Sseg = Dseg then
    WriteLn('SS=DS yes')
  else
    WriteLn('SS=DS no');
  WriteLn('MemAvail=', MemAvail);
end.
