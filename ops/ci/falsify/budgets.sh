#!/usr/bin/env bash
# Умеет ли падать сверка бюджетов TURBOCORE §12.
#
# Два случая, и оба обязаны краснеть:
#   1. код вырос за бюджет — превышение;
#   2. строку бюджета переименовали в документе — проверка ослепла бы
#      молча, и это опаснее превышения.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PAS="$ROOT/dosnode/src/node7.pas"
DOC="$ROOT/docs/TURBOCORE.md"
K1="$(mktemp)"; K2="$(mktemp)"
cp "$PAS" "$K1"; cp "$DOC" "$K2"
trap 'cp "$K1" "$PAS"; cp "$K2" "$DOC"; rm -f "$K1" "$K2"' EXIT

cd "$ROOT" || exit 1

bash ./sonder check-budgets > /tmp/f-budgets-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; exit 1; }

python3 - "$PAS" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_bytes().decode("utf-8")
old = "  ArenaPerChan = 2048;"
assert old in t
p.write_bytes(t.replace(old, "  ArenaPerChan = 4096;", 1).encode("utf-8"))
PY
bash ./sonder check-budgets > /tmp/f-budgets-1.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ ПРЕВЫШЕНИИ БЮДЖЕТА"; exit 1; }
cp "$K1" "$PAS"

python3 - "$DOC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_bytes().decode("utf-8")
old = "| Размер кадра |"
assert old in t
p.write_bytes(t.replace(old, "| Кадр, размер |", 1).encode("utf-8"))
PY
bash ./sonder check-budgets > /tmp/f-budgets-2.log 2>&1 \
  && { echo "  ЗЕЛЕНО БЕЗ СТРОКИ БЮДЖЕТА — проверка ослепла молча"; exit 1; }

echo "  превышение и потерянная строка бюджета ловятся"
