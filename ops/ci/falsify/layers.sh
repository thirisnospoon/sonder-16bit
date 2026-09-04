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

echo "  ввод-вывод в домене, ребро вверх и потерянный модуль ловятся"
