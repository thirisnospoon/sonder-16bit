#!/usr/bin/env bash
# Прогон спайка S1 для одной модели памяти.
#
#   ./run.sh <model>
#
# Вердикт выносится по содержимому S1.TAP, а не по коду возврата DOSBox:
# эмулятор не обязан честно его пробрасывать (docs/RISKS.md, R3).
set -uo pipefail

MODEL="${1:-medium}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out/$MODEL"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "=============================================="
echo " S1 · модель памяти: $MODEL"
echo "=============================================="

echo "--- компиляция ---"
if ! fpc-dos "$MODEL" -FE"$OUT" -oS1.EXE "$HERE/src/s1.pas" \
       > "$OUT/compile.log" 2>&1; then
  echo "  КОМПИЛЯЦИЯ ПРОВАЛЕНА"
  grep -E 'Error|Fatal|Warning' "$OUT/compile.log" | head -15 | sed 's/^/    /'
  exit 2
fi
echo "  S1.EXE: $(stat -c%s "$OUT/S1.EXE" 2>/dev/null || echo '?') байт"

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
S1.EXE
exit
EOF

echo "--- запуск под DOSBox ---"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  timeout 300 dosbox -conf "$OUT/dosbox.conf" -exit \
  > "$OUT/dosbox.log" 2>&1
echo "  DOSBox завершился с кодом $? (по решению не используется)"

TAP="$OUT/S1.TAP"
if [ ! -f "$TAP" ]; then
  echo "  S1.TAP НЕ СОЗДАН — программа не отработала"
  echo "  --- хвост лога DOSBox ---"
  tail -20 "$OUT/dosbox.log" | sed 's/^/    /'
  exit 1
fi

echo "--- S1.TAP ---"
sed 's/^/  /' "$TAP"

if grep -q '^not ok' "$TAP"; then
  echo
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ ($MODEL)"
  exit 1
fi
echo
echo "  РЕЗУЛЬТАТ: пройдено ($MODEL)"
