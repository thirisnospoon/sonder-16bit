#!/usr/bin/env bash
# Спайк S3 — устойчивость headless-прогона DOS в CI.
#
# Риск R3: эмулятор без дисплея может подвисать, не отдавать код возврата или
# флакать. Одиночный удачный запуск ничего не доказывает, поэтому здесь
# гоняется один и тот же бинарник N раз и проверяется, что результат
# детерминирован.
#
#   ./run.sh [N]     по умолчанию 20
set -uo pipefail

RUNS="${1:-20}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
HARNESS="$ROOT/ops/ci/run-dos-tap.sh"
OUT="$HERE/out"

rm -rf "$OUT"; mkdir -p "$OUT"

echo "=============================================="
echo " S3 · устойчивость headless-прогона, $RUNS раз"
echo "=============================================="

# Берём s1b: он рассчитан на модель large, где SS и DS различаются.
# s1.pas требует их совпадения и в large законно провалится — на этом
# первый прогон S3 и споткнулся.
echo "--- собираем подопытный бинарник (S1b, модель large) ---"
if ! fpc-dos large -FE"$OUT" -oS1B.EXE "$ROOT/spikes/s1-fibers/src/s1b.pas" \
       > "$OUT/compile.log" 2>&1; then
  echo "  КОМПИЛЯЦИЯ ПРОВАЛЕНА"; tail -10 "$OUT/compile.log"; exit 2
fi
echo "  S1B.EXE: $(stat -c%s "$OUT/S1B.EXE") байт"

echo "--- прогоны ---"
PASS=0; FAIL=0
declare -A SIGS
START=$(date +%s)

for i in $(seq 1 "$RUNS"); do
  t0=$(date +%s%N)
  if bash "$HARNESS" --exe "$OUT/S1B.EXE" --tap S1B.TAP --timeout 180 \
       --name "run-$i" > "$OUT/run-$i.out" 2>&1; then
    PASS=$((PASS+1)); verdict="ok"
  else
    FAIL=$((FAIL+1)); verdict="ПРОВАЛ"
  fi
  t1=$(date +%s%N)
  ms=$(( (t1 - t0) / 1000000 ))

  # Подпись прогона: только нумерованные строки TAP от самой программы.
  # Итоговая строка обвязки содержит имя прогона и в подпись попадать не должна,
  # иначе каждый прогон окажется уникальным просто из-за своего номера.
  sig=$(grep -E '^(ok|not ok) [0-9]+ ' "$OUT/run-$i.out" | md5sum | cut -c1-12)
  SIGS[$sig]=$(( ${SIGS[$sig]:-0} + 1 ))

  printf '  %2d/%d  %-7s %5d мс  подпись %s\n' "$i" "$RUNS" "$verdict" "$ms" "$sig"
done

END=$(date +%s)

echo
echo "=============================================="
echo " Итог"
echo "=============================================="
echo "  прогонов:        $RUNS"
echo "  успешных:        $PASS"
echo "  провалов:        $FAIL"
echo "  общее время:     $((END - START)) с"
echo "  различных подписей результата: ${#SIGS[@]}"
for s in "${!SIGS[@]}"; do
  echo "    $s встретилась ${SIGS[$s]} раз"
done

echo
if [ "$FAIL" -ne 0 ]; then
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ — $FAIL прогонов из $RUNS не прошли"
  exit 1
fi
if [ "${#SIGS[@]}" -ne 1 ]; then
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ — результат недетерминирован"
  exit 1
fi
echo "  РЕЗУЛЬТАТ: пройдено — $RUNS из $RUNS, результат детерминирован"
