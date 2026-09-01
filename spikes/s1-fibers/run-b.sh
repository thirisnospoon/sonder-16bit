#!/usr/bin/env bash
# Прогон спайка S1b: файберы со стеками в дальней куче и сменой SS.
#
#   ./run-b.sh [model]     по умолчанию large
set -uo pipefail

MODEL="${1:-large}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out/b-$MODEL"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "=============================================="
echo " S1b · модель памяти: $MODEL"
echo "=============================================="

echo "--- компиляция ---"
if ! fpc-dos "$MODEL" -FE"$OUT" -oS1B.EXE "$HERE/src/s1b.pas" \
       > "$OUT/compile.log" 2>&1; then
  echo "  КОМПИЛЯЦИЯ ПРОВАЛЕНА"
  grep -E 'Error|Fatal' "$OUT/compile.log" | head -15 | sed 's/^/    /'
  exit 2
fi
echo "  S1B.EXE: $(stat -c%s "$OUT/S1B.EXE" 2>/dev/null || echo '?') байт"

cat > "$OUT/dosbox.conf" <<EOF
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
mount c $OUT
c:
S1B.EXE
exit
EOF

echo "--- запуск под DOSBox ---"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  timeout 300 dosbox -conf "$OUT/dosbox.conf" -exit \
  > "$OUT/dosbox.log" 2>&1

TAP="$OUT/S1B.TAP"
if [ ! -f "$TAP" ]; then
  echo "  S1B.TAP НЕ СОЗДАН — программа не отработала вовсе"
  tail -20 "$OUT/dosbox.log" | sed 's/^/    /'
  exit 1
fi

echo "--- S1B.TAP ---"
sed 's/^/  /' "$TAP"

# Отчёт сбрасывается после каждой строки, поэтому обрыв на середине означает
# падение — это отдельный, худший исход, чем честный not ok.
if ! grep -q 'ИТОГ' "$TAP"; then
  echo
  echo "  РЕЗУЛЬТАТ: ПАДЕНИЕ — отчёт оборвался, конструкция неверна"
  exit 1
fi
if grep -q '^not ok' "$TAP"; then
  echo
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ ($MODEL)"
  exit 1
fi
echo
echo "  РЕЗУЛЬТАТ: пройдено ($MODEL)"
