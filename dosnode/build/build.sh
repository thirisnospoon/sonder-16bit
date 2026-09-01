#!/usr/bin/env bash
# Сборка кода NODE-7 под один из двух таргетов.
#
#   build.sh --target msdos|native --main <файл.pas> [--out ИМЯ.EXE]
#
# Зачем два таргета из одних исходников (ADR-0005):
#
#   msdos  — то, что поедет в бой: 16 бит, модель large, DOSBox.
#   native — то, на чём идёт разработка. Тесты и фаззинг проходят за секунды
#            вместо прогонов через эмулятор, доступны отладчик и санитайзеры.
#
# Различия между таргетами изолированы в модулях с суффиксом _dos и _nat.
# Всё остальное обязано собираться обоими компиляторами: расхождение —
# это дефект, а не особенность.
set -uo pipefail

TARGET=""
MAIN=""
OUTNAME=""
MODEL="${MODEL:-large}"
EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    --target) TARGET="$2"; shift 2 ;;
    --main)   MAIN="$2";   shift 2 ;;
    --out)    OUTNAME="$2"; shift 2 ;;
    --model)  MODEL="$2";  shift 2 ;;
    *)        EXTRA+=("$1"); shift ;;
  esac
done

[ -n "$TARGET" ] || { echo "нужен --target msdos|native" >&2; exit 64; }
[ -n "$MAIN" ]   || { echo "нужен --main <файл.pas>" >&2; exit 64; }
[ -f "$MAIN" ]   || { echo "не найден $MAIN" >&2; exit 64; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/dosnode/src"
OUT="$HERE/out/$TARGET"

mkdir -p "$OUT"

[ -n "$OUTNAME" ] || OUTNAME="$(basename "${MAIN%.pas}")"

# Пути к исходникам одинаковы для обоих таргетов: в этом весь смысл.
UNIT_PATHS=(
  -Fu"$SRC/turbocore"
  -Fu"$SRC/domain"
  -Fu"$SRC/generated"
  -Fi"$SRC/generated"
  -Fi"$SRC/turbocore"
)

case "$TARGET" in
  msdos)
    OUTNAME="$(echo "$OUTNAME" | tr '[:lower:]' '[:upper:]')"
    [[ "$OUTNAME" == *.EXE ]] || OUTNAME="${OUTNAME}.EXE"
    fpc-dos "$MODEL" \
      -FE"$OUT" -FU"$OUT" \
      "${UNIT_PATHS[@]}" \
      "${EXTRA[@]+"${EXTRA[@]}"}" \
      -o"$OUTNAME" "$MAIN" \
      > "$OUT/build.log" 2>&1
    rc=$?
    ;;
  native)
    # -Mtp — тот же диалект, что и на 16-битном таргете. Без него нативная
    # сборка приняла бы то, чего боевой компилятор не поймёт, и дуальность
    # превратилась бы в фикцию.
    #
    # -gl -Ci -Cr -Co: строки в трассировке, проверки диапазонов, границ и
    # переполнения. На msdos они стоили бы памяти и скорости, здесь бесплатны
    # и ловят то, что на 16 битах молча испортило бы соседнюю переменную.
    fpc -Mtp -gl -Ci -Cr -Co -O1 \
      -FE"$OUT" -FU"$OUT" \
      "${UNIT_PATHS[@]}" \
      "${EXTRA[@]+"${EXTRA[@]}"}" \
      -o"$OUTNAME" "$MAIN" \
      > "$OUT/build.log" 2>&1
    rc=$?
    ;;
  *)
    echo "неизвестный таргет: $TARGET" >&2; exit 64 ;;
esac

if [ $rc -ne 0 ]; then
  echo "СБОРКА ПРОВАЛЕНА ($TARGET)"
  grep -E 'Error|Fatal|Warning' "$OUT/build.log" | head -25 | sed 's/^/  /'
  exit 1
fi

echo "$OUT/$OUTNAME"
