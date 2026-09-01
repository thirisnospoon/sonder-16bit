#!/usr/bin/env bash
# Прогон спайка S6. Вердикт — по TAP от клиента.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

echo "=============================================="
echo " S6 · Firebird как замена PostgreSQL"
echo "=============================================="

docker compose -f compose.yml down -v --remove-orphans >/dev/null 2>&1

echo "--- сборка клиента ---"
if ! docker compose -f compose.yml build > build.log 2>&1; then
  echo "СБОРКА ПРОВАЛЕНА"
  grep -E "ERROR|error:|Could not resolve" build.log | head -20
  exit 2
fi

echo "--- запуск ---"
docker compose -f compose.yml up \
  --abort-on-container-exit --exit-code-from probe \
  > run.log 2>&1
RC=$?

echo
echo "--- Firebird ---"
grep -a "db-1" run.log | sed 's/^[^|]*| /  /' | tail -6

echo
echo "--- клиент (TAP) ---"
grep -a "probe-1" run.log | sed 's/^[^|]*| /  /'

docker compose -f compose.yml down -v --remove-orphans >/dev/null 2>&1

echo
if [ "$RC" -eq 0 ]; then
  echo "  РЕЗУЛЬТАТ: пройдено"
else
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ (код $RC)"
fi
exit $RC
