#!/usr/bin/env bash
# Сборка кросс-компилятора FPC i8086-msdos и RTL под все модели памяти.
#
# Собирается в два явных этапа, и это не стилистика:
#
#   1. compiler_cycle — только компилятор. Цель crossall делала бы и RTL, но
#      она вызывает его подсборку с RELEASE=1 и принудительно пустым OPT
#      (docs/PHASE0-FINDINGS.md, F-07), из-за чего наш ключ модели памяти туда
#      не доходит, а релизная оптимизация переполняет сегмент кода.
#
#   2. rtl/msdos отдельно под каждую модель, с явным -Wm и без RELEASE.
#      RTL для каждой модели ставится в свой префикс /opt/fpc/<model>.
#
# Модель определяет предел размера кода и данных и то, совпадают ли SS и DS,
# а от этого зависит вся конструкция файберов (docs/TURBOCORE.md §5). Какая
# модель победит — решает спайк S1, поэтому образ обязан давать сравнить.
set -euo pipefail

FPC_VERSION="${FPC_VERSION:-3.2.2}"
SRC=/usr/src/fpcbuild/fpcsrc
BOOTSTRAP=/usr/bin/ppcx64
MODELS="${MODELS:-tiny small medium compact large huge}"

COMMON_ARGS=(
  CPU_TARGET=i8086
  OS_TARGET=msdos
  BINUTILSPREFIX=msdos-
  NOWPO=1
)

echo "=============================================================="
echo " 1. Кросс-компилятор"
echo "=============================================================="
cd "$SRC"
make compiler_cycle "${COMMON_ARGS[@]}" FPC="$BOOTSTRAP"

CROSSC="$(find "$SRC/compiler" -name ppcross8086 -type f | head -1)"
if [ -z "$CROSSC" ]; then
  echo "ОШИБКА: ppcross8086 не собрался"
  exit 1
fi
install -D -m 0755 "$CROSSC" /usr/local/bin/ppcross8086
ln -sf /usr/local/bin/ppcross8086 /usr/local/bin/ppc8086
CROSSC=/usr/local/bin/ppcross8086
echo "кросс-компилятор: $("$CROSSC" -iV) для $("$CROSSC" -iTP)"

echo
echo "=============================================================="
echo " 2. RTL под каждую модель памяти"
echo "=============================================================="
BUILT=""
FAILED=""
for model in $MODELS; do
  printf '  %-9s ' "$model"
  make -C "$SRC/rtl" clean "${COMMON_ARGS[@]}" FPC="$CROSSC" >/dev/null 2>&1 || true

  if make -C "$SRC/rtl/msdos" all "${COMMON_ARGS[@]}" \
          FPC="$CROSSC" OPT="-Wm${model}" \
          > "/tmp/rtl-${model}.log" 2>&1 \
     && make -C "$SRC/rtl/msdos" install "${COMMON_ARGS[@]}" \
          FPC="$CROSSC" OPT="-Wm${model}" \
          INSTALL_PREFIX="/opt/fpc/${model}" \
          > "/tmp/rtl-${model}-install.log" 2>&1
  then
    sysppu="$(find "/opt/fpc/${model}" -name 'system.ppu' 2>/dev/null | head -1)"
    if [ -n "$sysppu" ]; then
      UNITDIR="$(dirname "$sysppu")"

      # Цель install кладёт только .ppu. Компилятору нужны ещё и объектные
      # файлы, поэтому докладываем их из каталога сборки (F-08).
      for d in "$SRC/rtl/units/msdos" "$SRC/rtl/units/i8086-msdos"; do
        [ -d "$d" ] && cp -a "$d"/. "$UNITDIR"/ 2>/dev/null || true
      done

      # Каталог модулей записывается в манифест, а не угадывается обёрткой:
      # прямой вызов make -C rtl/msdos install кладёт их в units/msdos,
      # а полная установка — в units/i8086-msdos.
      echo "$UNITDIR" > "/opt/fpc/${model}/UNITDIR"

      nppu=$(find "$UNITDIR" -name '*.ppu' | wc -l)
      nobj=$(find "$UNITDIR" \( -name '*.o' -o -name '*.a' \) | wc -l)
      echo "OK, ${nppu} модулей, ${nobj} объектных → ${UNITDIR}"
      if [ "$nobj" -eq 0 ]; then
        echo "             ВНИМАНИЕ: объектных файлов нет, линковка не пройдёт"
      fi
      BUILT="${BUILT} ${model}"
    else
      echo "ПРОВАЛ: установка не положила ни одного .ppu"
      FAILED="${FAILED} ${model}"
    fi
  else
    echo "ПРОВАЛ"
    grep -m2 -E 'Fatal|Error:' "/tmp/rtl-${model}.log" 2>/dev/null | sed 's/^/             /'
    FAILED="${FAILED} ${model}"
  fi
done

echo
echo "=============================================================="
echo " 3. Итог"
echo "=============================================================="
echo "  собрано:${BUILT:- —}"
echo "  провалено:${FAILED:- —}"

if [ -z "${BUILT// /}" ]; then
  echo "ОШИБКА: ни одна модель не собралась, образ бесполезен"
  exit 1
fi

# Манифест для скриптов сборки: какие модели реально доступны в образе.
mkdir -p /opt/fpc
printf '%s\n' ${BUILT} > /opt/fpc/AVAILABLE_MODELS

rm -rf /usr/src/fpcbuild
