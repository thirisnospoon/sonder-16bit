#!/usr/bin/env bash
# Прогон тестов NODE-7 на обоих таргетах.
#
#   test.sh [native|msdos|both]     по умолчанию both
#
# Смысл в том, чтобы одни и те же проверки шли и на нативном таргете, где
# они занимают доли секунды, и на 16-битном, который поедет в бой.
# Расхождение результатов означает дефект, а не особенность платформы:
# именно ради этого сравнения существует дуальная сборка (ADR-0005).
set -uo pipefail

WHAT="${1:-both}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TESTS="$ROOT/dosnode/tests"

rc=0

run_native() {
  echo "=============================================="
  echo " Нативный таргет"
  echo "=============================================="
  local failed=0
  for src in "$TESTS"/*.pas; do
    local name; name="$(basename "${src%.pas}")"
    local bin
    if ! bin="$(bash "$HERE/build.sh" --target native --main "$src" --out "$name")"; then
      failed=1; continue
    fi
    echo "--- $name ---"
    # Отчёт пишется в рабочий каталог, поэтому запускаем из него.
    ( cd "$(dirname "$bin")" && "./$name" ) | sed 's/^/  /'
    local st=${PIPESTATUS[0]}
    if [ "$st" -ne 0 ]; then
      echo "  ПРОВАЛ: код возврата $st"
      failed=1
    fi
  done
  return $failed
}

run_msdos() {
  echo
  echo "=============================================="
  echo " Таргет i8086-msdos"
  echo "=============================================="
  local failed=0
  for src in "$TESTS"/*.pas; do
    local name; name="$(basename "${src%.pas}")"
    local upper; upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
    local bin
    if ! bin="$(bash "$HERE/build.sh" --target msdos --main "$src" --out "$name")"; then
      failed=1; continue
    fi
    echo "--- $name ($(stat -c%s "$bin") байт) ---"
    # Вердикт выносится по TAP-файлу, а не по коду возврата эмулятора (R3).
    bash "$ROOT/ops/ci/run-dos-tap.sh" \
         --exe "$bin" --tap "${upper}.TAP" --timeout 300 --name "$name" \
      | sed 's/^/  /'
    [ "${PIPESTATUS[0]}" -ne 0 ] && failed=1
  done
  return $failed
}

case "$WHAT" in
  native) run_native || rc=1 ;;
  msdos)  run_msdos  || rc=1 ;;
  both)
    run_native || rc=1
    run_msdos  || rc=1
    ;;
  *) echo "неизвестный таргет: $WHAT" >&2; exit 64 ;;
esac

echo
if [ "$rc" -eq 0 ]; then
  echo "тесты пройдены на всех таргетах"
else
  echo "ЕСТЬ ПРОВАЛЫ"
fi
exit "$rc"
