#!/usr/bin/env bash
# Прогон S1 по всем моделям памяти, доступным в образе, и сводная таблица.
#
# Смысл спайка не в том, чтобы что-то заработало, а в том, чтобы выбрать модель
# осознанно: сравнить, где SS = DS, где влезает код и какова цена по стеку.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODELS_FILE=/opt/fpc/AVAILABLE_MODELS

if [ ! -f "$MODELS_FILE" ]; then
  echo "нет $MODELS_FILE — запускать нужно внутри образа sonder/dos-toolchain"
  exit 64
fi

MODELS="$(tr '\n' ' ' < "$MODELS_FILE")"
echo "модели в образе: $MODELS"
echo

declare -A VERDICT
for model in $MODELS; do
  if bash "$HERE/run.sh" "$model"; then
    VERDICT[$model]="пройдено"
  else
    case $? in
      2) VERDICT[$model]="не компилируется" ;;
      *) VERDICT[$model]="провал" ;;
    esac
  fi
  echo
done

echo "=============================================="
echo " Сводка S1"
echo "=============================================="
printf '%-10s %-18s %-8s %-8s %s\n' МОДЕЛЬ ВЕРДИКТ SS=DS PTR "ГЛУБИНА СТЕКА, БАЙТ"
for model in $MODELS; do
  tap="$HERE/out/$model/S1.TAP"
  ssds="—"; ptr="—"; depth="—"
  if [ -f "$tap" ]; then
    grep -q 'ok .* SS = DS' "$tap" && ssds="да" || ssds="нет"
    ptr="$(grep -oP '(?<=# SizeOf\(Pointer\) )\d+' "$tap" | head -1)"
    depth="$(grep -oP '(?<=# глубина стека файбера, байт: )\d+' "$tap" | tr '\n' '/' | sed 's:/$::')"
  fi
  printf '%-10s %-18s %-8s %-8s %s\n' "$model" "${VERDICT[$model]}" "$ssds" "${ptr:-—}" "${depth:-—}"
done

echo
echo "Числа отсюда переносятся в docs/PHASE0-FINDINGS.md и заменяют пометки"
echo "[спайк] в docs/TURBOCORE.md."
