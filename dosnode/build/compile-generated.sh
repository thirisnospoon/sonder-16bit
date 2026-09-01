#!/usr/bin/env bash
# Проверка сгенерированного из контрактов Pascal: собирается и работает.
#
# Генератор, выдающий несобирающийся код, бесполезен, а заметить это глазами
# невозможно. Но и одной компиляции мало: раскладка записей может собраться и
# при этом не работать. Поэтому пробник ещё и исполняется под DOSBox и пишет
# TAP — вердикт выносится по нему.
#
# Входит в ./sonder verify.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MODEL="${MODEL:-large}"
OUT="$HERE/out/generated"

rm -rf "$OUT"; mkdir -p "$OUT"

cat > "$OUT/probe.pas" <<'PAS'
{ Пробник сгенерированного кода.

  Трогает по одному представителю каждого вида типов: перечисление, запись
  с вложенными записями, список, строковую запись, константы кодов отказа.
  Без обращения к ним smart-linking выбросил бы всё неиспользуемое, и
  проверка стала бы бессмысленной. }
program Probe;

{$MODE TP}
{$R-}

uses DcdTypes, Strings;   { Strings нужен для StrComp: имена значений
                            перечислений хранятся как PChar, а не string }

{$I errcodes.inc}

var
  Report: Text;
  TestNo: Integer;
  Fails:  Integer;
  Tmp:    string;

procedure Emit(const S: string);
begin
  WriteLn(Report, S);
  Flush(Report);
  WriteLn(S);
end;

procedure Check(const Name: string; Cond: Boolean);
begin
  Inc(TestNo);
  Str(TestNo, Tmp);
  if Cond then
    Emit('ok ' + Tmp + ' - ' + Name)
  else
  begin
    Emit('not ok ' + Tmp + ' - ' + Name);
    Inc(Fails);
  end;
end;

procedure DiagNum(const S: string; N: LongInt);
var
  T: string;
begin
  Str(N, T);
  Emit('# ' + S + ' ' + T);
end;

var
  Req:   TCreatePostRequest;
  Del:   TDeletePostRequest;
  Dec:   TDecision;
  Ev:    TDomainEvent;
  Node:  TDomainEventNode;
  R:     TRole;
  Body:  array[0..63] of Char;

begin
  Assign(Report, 'PROBE.TAP');
  Rewrite(Report);
  TestNo := 0;
  Fails  := 0;

  Emit('1..8');
  Emit('# пробник сгенерированного из контракта кода');

  FillChar(Req, SizeOf(Req), 0);
  FillChar(Del, SizeOf(Del), 0);
  FillChar(Dec, SizeOf(Dec), 0);
  FillChar(Ev,  SizeOf(Ev),  0);
  FillChar(Node, SizeOf(Node), 0);

  DiagNum('SizeOf(TCreatePostRequest)', SizeOf(Req));
  DiagNum('SizeOf(TDeletePostRequest)', SizeOf(Del));
  DiagNum('SizeOf(TDecision)', SizeOf(Dec));
  DiagNum('SizeOf(TActorContext)', SizeOf(Req.actor));
  DiagNum('SizeOf(TStr)', SizeOf(Req.command.body));

  { Записи не пустые: если бы генератор потерял поля, размер схлопнулся бы. }
  Check('запись запроса непустая', SizeOf(Req) > 20);
  Check('состояние вложено в запрос', SizeOf(Req.actor) >= 10);

  { Перечисления и их имена согласованы по порядку. }
  Req.actor.role := Role_ADMIN;
  R := Req.actor.role;
  Check('перечисление роли присваивается и читается', R = Role_ADMIN);
  Check('имя роли соответствует значению',
        StrComp(RoleNames[Role_MODERATOR], 'MODERATOR') = 0);
  Check('имя статуса соответствует значению',
        StrComp(UserStatusNames[UserStatus_BANNED], 'BANNED') = 0);

  { Строковая запись: указатель в арену и длина. }
  Body := 'проверка';
  Req.command.body.Ptr := @Body[0];
  Req.command.body.Len := 8;
  Check('строковая запись хранит указатель и длину',
        (Req.command.body.Ptr <> nil) and (Req.command.body.Len = 8));

  { Список событий: узел ссылается сам на себя через указатель. }
  Dec.accepted := True;
  Node.Value := Ev;
  Node.Next := nil;
  Dec.event := @Node;
  Check('список событий связывается', (Dec.event <> nil) and (Dec.event^.Next = nil));

  { Константы кодов отказа доступны и непустые. }
  Check('коды отказа сгенерированы',
        (ERR_ACTOR_BANNED = 'ACTOR_BANNED') and (ERR_CODE_COUNT > 20));

  DiagNum('число кодов отказа', ERR_CODE_COUNT);
  Emit('# операция: ' + Op_CreatePost);

  if Fails = 0 then
    Emit('# ИТОГ: сгенерированный код собирается и работает')
  else
    DiagNum('ИТОГ: провалов', Fails);

  Close(Report);
  Halt(Fails);
end.
PAS

cp "$ROOT/dosnode/src/generated/dcdtypes.pas" "$OUT/"
cp "$ROOT/dosnode/src/generated/errcodes.inc" "$OUT/"

# Путь к TurboCore, а не копия его модулей: сгенерированный код берёт TStr
# из TcStr (вокабуляр принадлежит фреймворку), и собирать пробник против
# копии значило бы не заметить расхождения — ровно того, ради чего этот
# шаг существует.
CORE="$ROOT/dosnode/src/turbocore"

echo "--- компиляция, модель $MODEL ---"
if ! fpc-dos "$MODEL" -FE"$OUT" -FU"$OUT" -Fi"$OUT" -Fu"$OUT" -Fu"$CORE" \
       -oPROBE.EXE "$OUT/probe.pas" > "$OUT/compile.log" 2>&1; then
  echo "  ПРОВАЛ: сгенерированный код не компилируется"
  grep -E 'Error|Fatal' "$OUT/compile.log" | head -20 | sed 's/^/    /'
  exit 1
fi
echo "  собралось: $(stat -c%s "$OUT/PROBE.EXE") байт"

echo "--- прогон под DOSBox ---"
bash "$ROOT/ops/ci/run-dos-tap.sh" \
     --exe "$OUT/PROBE.EXE" --tap PROBE.TAP --timeout 60 --name generated \
  | sed 's/^/  /'
exit "${PIPESTATUS[0]}"
