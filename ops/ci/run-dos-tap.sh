#!/usr/bin/env bash
# Запуск DOS-бинарника под headless DOSBox с вынесением вердикта по TAP.
#
#   run-dos-tap.sh --exe build/tests.exe --tap TESTS.TAP [--args "..."]
#                  [--timeout 300] [--name imя]
#
# Почему вердикт по файлу, а не по коду возврата: DOSBox не обязан честно
# пробрасывать код возврата DOS-программы наружу (docs/RISKS.md, R3).
# Программа пишет TAP в примонтированный каталог, решение принимается здесь.
#
# Обвязка ловит три разных исхода, которые наивная проверка спутала бы:
#   * программа не отработала вовсе      — TAP отсутствует;
#   * программа упала на середине        — тестов меньше, чем объявлено в плане;
#   * программа отработала и провалилась — есть строки not ok.
set -uo pipefail

EXE=""
TAP_NAME=""
PROG_ARGS=""
TIMEOUT=300
NAME=""
KEEP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --exe)     EXE="$2"; shift 2 ;;
    --tap)     TAP_NAME="$2"; shift 2 ;;
    --args)    PROG_ARGS="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --name)    NAME="$2"; shift 2 ;;
    --keep)    KEEP=1; shift ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

[ -n "$EXE" ] || { echo "нужен --exe" >&2; exit 64; }
[ -n "$TAP_NAME" ] || { echo "нужен --tap" >&2; exit 64; }
[ -f "$EXE" ] || { echo "не найден $EXE" >&2; exit 64; }
[ -n "$NAME" ] || NAME="$(basename "$EXE")"

command -v dosbox >/dev/null 2>&1 || { echo "dosbox не установлен" >&2; exit 64; }

WORK="$(mktemp -d)"
cleanup() { [ "$KEEP" -eq 1 ] || rm -rf "$WORK"; }
trap cleanup EXIT

# DOS требует имена 8.3, и монтируется именно каталог, а не файл.
DOS_EXE="$(basename "$EXE" | tr '[:lower:]' '[:upper:]')"
cp "$EXE" "$WORK/$DOS_EXE"

cat > "$WORK/dosbox.conf" <<EOF
[sdl]
autolock=false
[dosbox]
machine=svga_s3
memsize=16
[cpu]
core=auto
cputype=auto
cycles=max
[autoexec]
mount c $WORK
c:
$DOS_EXE $PROG_ARGS
exit
EOF

SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  timeout --kill-after=10 "$TIMEOUT" \
  dosbox -conf "$WORK/dosbox.conf" -exit \
  > "$WORK/dosbox.log" 2>&1
EMU_RC=$?

TAP="$WORK/$TAP_NAME"

if [ "$EMU_RC" -eq 124 ] || [ "$EMU_RC" -eq 137 ]; then
  echo "not ok - $NAME: DOSBox не завершился за ${TIMEOUT} с, убит сторожевым таймером"
  tail -20 "$WORK/dosbox.log" | sed 's/^/# /'
  exit 1
fi

if [ ! -f "$TAP" ]; then
  echo "not ok - $NAME: $TAP_NAME не создан, программа не отработала"
  echo "# код возврата DOSBox: $EMU_RC"
  tail -20 "$WORK/dosbox.log" | sed 's/^/# /'
  exit 1
fi

# Разбор TAP.
PLAN="$(grep -m1 -oE '^1\.\.[0-9]+' "$TAP" | cut -d. -f3)"
OK_N="$(grep -c '^ok ' "$TAP")"
BAD_N="$(grep -c '^not ok ' "$TAP")"
TOTAL=$((OK_N + BAD_N))

cat "$TAP"

if [ -z "$PLAN" ]; then
  echo "not ok - $NAME: в отчёте нет строки плана 1..N"
  exit 1
fi

# Обрыв на середине опаснее честного провала: он означает падение, а не отказ.
if [ "$TOTAL" -ne "$PLAN" ]; then
  echo "not ok - $NAME: выполнено $TOTAL проверок из $PLAN — программа прервалась"
  exit 1
fi

if [ "$BAD_N" -gt 0 ]; then
  echo "not ok - $NAME: провалено $BAD_N из $PLAN"
  exit 1
fi

echo "ok - $NAME: $OK_N из $PLAN"
exit 0
