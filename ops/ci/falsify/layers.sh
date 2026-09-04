#!/usr/bin/env bash
# Ловит ли проверка слоёв настоящие нарушения.
#
# Три случая, и каждый — то, что собралось бы молча:
#   1. домен трогает порт — ввод-вывод в чистой функции;
#   2. фреймворк узнаёт о контракте — ребро вверх;
#   3. неизвестный модуль в uses — потерянный файл или устаревший RTL.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
DM="$ROOT/dosnode/src/domain/dmrules.pas"
TC="$ROOT/dosnode/src/turbocore/tcarena.pas"
K1="$(mktemp)"; K2="$(mktemp)"
cp "$DM" "$K1"; cp "$TC" "$K2"
trap 'cp "$K1" "$DM"; cp "$K2" "$TC"; rm -f "$K1" "$K2"' EXIT

cd "$ROOT" || exit 1

bash ./sonder check-layers > /tmp/f-layers-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; exit 1; }

replace_in() {
  python3 - "$1" "$2" "$3" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_bytes().decode("utf-8")
assert sys.argv[2] in t, sys.argv[2]
p.write_bytes(t.replace(sys.argv[2], sys.argv[3], 1).encode("utf-8"))
PY
}

replace_in "$DM" "  TcStr, DcdTypes;" "  TcStr, TcPort, DcdTypes;"
bash ./sonder check-layers > /tmp/f-layers-1.log 2>&1 \
  && { echo "  ЗЕЛЕНО, КОГДА ДОМЕН ТРОГАЕТ ПОРТ"; exit 1; }
cp "$K1" "$DM"

replace_in "$TC" "  TcResult, TcStr;" "  TcResult, TcStr, DcdTypes;"
bash ./sonder check-layers > /tmp/f-layers-2.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ РЕБРЕ ВВЕРХ"; exit 1; }
cp "$K2" "$TC"

replace_in "$TC" "  TcResult, TcStr;" "  TcResult, TcStr, TcNoSuchThing;"
bash ./sonder check-layers > /tmp/f-layers-3.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ НЕИЗВЕСТНОМ МОДУЛЕ"; exit 1; }

cp "$K2" "$TC"

# --- достижимость от программы ----------------------------------------
#
# Две стороны, и обе обязаны краснеть. Модуль, ВЫПАВШИЙ из программы, —
# это либо потерянное `uses`, либо новый мёртвый груз. Модуль,
# ВЕРНУВШИЙСЯ в неё, делает объяснение в списке ложным, а список,
# объясняющий уже не то, хуже отсутствующего.
NODE="$ROOT/dosnode/src/node7.pas"
K3="$(mktemp)"
cp "$NODE" "$K3"
restore_node() { cp "$K3" "$NODE"; rm -f "$K3"; }

replace_in "$NODE" "  DcdTypes, DcdSrv, DmDecide;" "  DcdTypes, DcdSrv;"
if bash ./sonder check-layers > /tmp/f-layers-4.log 2>&1; then
  echo "  ЗЕЛЕНО, КОГДА МОДУЛЬ ВЫПАЛ ИЗ ПРОГРАММЫ"
  restore_node
  exit 1
fi
grep -aq "не входит в программу и не объявлен осознанно" /tmp/f-layers-4.log || {
  echo "  упало не на достижимости"
  restore_node
  exit 1
}
cp "$K3" "$NODE"

replace_in "$NODE" "  DcdTypes, DcdSrv, DmDecide;" \
                   "  DcdTypes, DcdSrv, DmDecide, TcLog;"
if bash ./sonder check-layers > /tmp/f-layers-5.log 2>&1; then
  echo "  ЗЕЛЕНО, КОГДА ОБЪЯВЛЕННЫЙ НЕДОСТИЖИМЫМ ВЕРНУЛСЯ"
  restore_node
  exit 1
fi
grep -aq "объявлен недостижимым, но программа его использует" /tmp/f-layers-5.log || {
  echo "  упало не на возвращении"
  restore_node
  exit 1
}
restore_node

echo "  ввод-вывод в домене, ребро вверх, потерянный модуль и обе"
echo "  стороны достижимости ловятся"
