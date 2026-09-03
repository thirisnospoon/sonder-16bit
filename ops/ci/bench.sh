#!/usr/bin/env bash
# Нагрузочный профиль против ПОДНЯТОЙ системы.
#
# Гейт Ф10 требует отчёта с графиками насыщения и подтверждённым
# потолком записи. Потолок предсказан спайком S2 и ADR-0011 — порядка
# десяти команд в секунду, — и предсказание надо подтвердить на живой
# системе, а не сослаться на него.
#
# Меряются ОБА пути. Запись идёт через нульмодем к шестнадцатибитному
# ядру, чтение — из проекции и линию не трогает вовсе. Контраст между
# ними и есть главный результат: он показывает, что потолок принадлежит
# ЛИНИИ, а не приложению.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NET="${SONDER_E2E_NET:-sonder_default}"
URL="${SONDER_URL:-https://web:443}"
SECONDS_PER_LEVEL="${SONDER_BENCH_SECONDS:-15}"
LEVELS="${SONDER_BENCH_LEVELS:-1,2,4,8,16,32}"
USERS="${SONDER_BENCH_USERS:-64}"
OUT="$ROOT/docs/bench"

if ! docker network inspect "$NET" >/dev/null 2>&1; then
  echo "нет сети $NET — система не поднята. Сначала ./sonder up" >&2
  exit 1
fi

mkdir -p "$OUT"

run() {
  local mode="$1"
  echo
  echo "==> нагрузка: $mode"
  docker run --rm \
    --network "$NET" \
    -v "$ROOT/ops/bench:/bench:ro" \
    -v "$OUT:/out" \
    -w /bench \
    python:3.12-alpine \
    python load.py \
      --url "$URL" \
      --users "$USERS" \
      --seconds "$SECONDS_PER_LEVEL" \
      --levels "$LEVELS" \
      --mode "$mode" \
      --out "/out/$mode.json"
}

run write || exit 1
run read  || exit 1

echo
echo "==> отчёт"
docker run --rm \
  -v "$ROOT/ops/bench:/bench:ro" \
  -v "$OUT:/out" \
  -w /bench \
  python:3.12-alpine \
  python report.py /out/write.json /out/read.json /out
