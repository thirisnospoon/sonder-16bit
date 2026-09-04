#!/usr/bin/env bash
# Ловит ли проверка свода настоящие ошибки пакетной обработки.
#
# Две мутации, и обе — классика приёма, а не выдумка:
#
#   1. ЗАБЫТАЯ ПОСЛЕДНЯЯ ГРУППА. Контрольный переход срабатывает на
#      СМЕНЕ автора, а после последней записи менять нечего. Программа
#      без закрытия последней группы работает, печатает отчёт и просто
#      не показывает одного автора — выглядит это как «его и не было»,
#      а не как ошибка. Итог при этом сходится, потому что общий счёт
#      идёт отдельно: поймать можно только по строке автора.
#
#   2. НЕОБНУЛЁННЫЙ НАКОПИТЕЛЬ. Счётчики автора обнуляются при переходе;
#      без обнуления каждая следующая группа наследует предыдущую, и
#      числа растут монотонно — тоже правдоподобно.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/report/src/DIGEST.cbl"
KEEP="$(mktemp)"
cp "$SRC" "$KEEP"

restore() {
  cp "$KEEP" "$SRC"
  rm -f "$KEEP"
  cd "$ROOT" && docker build -f ops/compose/Dockerfile.report \
    -t sonder/report:1 . > /dev/null 2>&1
}
trap restore EXIT

cd "$ROOT" || exit 1

rebuild() {
  docker build -f ops/compose/Dockerfile.report -t sonder/report:1 . \
    > /tmp/f-report-build.log 2>&1 \
    || { echo "  сборка отказала"; tail -5 /tmp/f-report-build.log; return 1; }
}

rebuild || exit 1
bash ops/ci/report-test.sh > /tmp/f-report-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; tail -3 /tmp/f-report-0.log; exit 1; }

# --- мутация 1: последняя группа не закрывается ------------------------
python3 - "$SRC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_bytes().decode("utf-8")
old = """           IF NOT IS-FIRST
               PERFORM WRITE-AUTHOR-TOTAL
           END-IF"""
assert old in t, "не нашёл закрытие последней группы"
p.write_bytes(t.replace(old, "           CONTINUE", 1).encode("utf-8"))
PY
rebuild || exit 1
if bash ops/ci/report-test.sh > /tmp/f-report-1.log 2>&1; then
  echo "  ЗЕЛЕНО БЕЗ ПОСЛЕДНЕЙ ГРУППЫ — проверка её не стережёт"
  exit 1
fi
grep -aq "последняя группа не закрыта" /tmp/f-report-1.log \
  || { echo "  упало, но не на последней группе:"; tail -3 /tmp/f-report-1.log; exit 1; }
cp "$KEEP" "$SRC"

# --- мутация 2: накопитель автора не обнуляется ------------------------
python3 - "$SRC" <<'PY'
import sys, pathlib
p = pathlib.Path(sys.argv[1])
t = p.read_bytes().decode("utf-8")
old = """                   MOVE 0 TO A-POSTS
                   MOVE 0 TO A-BYTES
                   MOVE 0 TO A-CHARS"""
assert old in t, "не нашёл обнуление накопителя"
p.write_bytes(t.replace(old, "                   CONTINUE", 1).encode("utf-8"))
PY
rebuild || exit 1
if bash ops/ci/report-test.sh > /tmp/f-report-2.log 2>&1; then
  echo "  ЗЕЛЕНО С НЕОБНУЛЁННЫМ НАКОПИТЕЛЕМ"
  exit 1
fi

echo "  потеря последней группы и необнулённый накопитель ловятся"
