#!/usr/bin/env bash
# Прогон спайка S5. Вердикт — по TAP от клиента.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

echo "=============================================="
echo " S5 · CORBA/IIOP между контейнерами"
echo "=============================================="

docker compose -f compose.yml down -v --remove-orphans >/dev/null 2>&1

echo "--- сборка образа ---"
if ! docker compose -f compose.yml build > build.log 2>&1; then
  echo "СБОРКА ПРОВАЛЕНА"; tail -25 build.log; exit 2
fi
grep -o "сгенерировано классов: [0-9]*" build.log | tail -1 | sed 's/^/  /'

echo "--- запуск ---"
docker compose -f compose.yml up \
  --abort-on-container-exit --exit-code-from events \
  > run.log 2>&1
RC=$?

echo
echo "--- сервер ---"
grep -a "core-1" run.log | sed 's/^[^|]*| /  /' | head -10

echo
echo "--- клиент (TAP) ---"
grep -a "events-1" run.log | sed 's/^[^|]*| /  /'

docker compose -f compose.yml down -v --remove-orphans >/dev/null 2>&1

echo
if [ "$RC" -eq 0 ]; then
  echo "  РЕЗУЛЬТАТ: пройдено"
else
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ (код $RC)"
fi
exit $RC
