#!/usr/bin/env bash
# Прогон спайка S2: измерение последовательного транспорта.
#
#   ./run.sh [divisor]     1 = 115200 бод (по умолчанию), 12 = 9600
#
# Порядок важен: сначала поднимается измеряющий хост, потом DOSBox
# подключается к нему сокетом.
set -uo pipefail

DIVISOR="${1:-1}"
PORT="${S2_PORT:-5300}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/out/div-$DIVISOR"
MODEL="${MODEL:-large}"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "=============================================="
echo " S2 · делитель $DIVISOR ($((115200 / DIVISOR)) бод), модель $MODEL"
echo "=============================================="

echo "--- компиляция ---"
if ! fpc-dos "$MODEL" -FE"$OUT" -oS2.EXE "$HERE/src/s2.pas" \
       > "$OUT/compile.log" 2>&1; then
  echo "  КОМПИЛЯЦИЯ ПРОВАЛЕНА"
  grep -E 'Error|Fatal' "$OUT/compile.log" | head -15 | sed 's/^/    /'
  exit 2
fi
echo "  S2.EXE: $(stat -c%s "$OUT/S2.EXE") байт"

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
[serial]
serial1=nullmodem server:127.0.0.1 port:$PORT transparent:1
serial2=disabled
serial3=disabled
serial4=disabled
[autoexec]
mount c $OUT
c:
S2.EXE $DIVISOR
exit
EOF

echo "--- поднимаем измеряющий хост ---"
cd "$OUT"
rm -f "$OUT/listening"
S2_PORT="$PORT" S2_JSON="$OUT/result.json" S2_LISTENING_MARKER="$OUT/listening" \
  python3 "$HERE/host.py" "$DIVISOR" > "$OUT/host.tap" 2>&1 &
HOST_PID=$!

# Ждём файл-маркер, а не пробное подключение: проверка сокета соединением
# заняла бы единственный слот listen(1), и DOSBox остался бы в очереди.
for _ in $(seq 1 60); do
  [ -f "$OUT/listening" ] && break
  sleep 0.25
done
if [ ! -f "$OUT/listening" ]; then
  echo "  хост не поднялся"
  kill "$HOST_PID" 2>/dev/null
  exit 1
fi

echo "--- запускаем DOSBox ---"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  timeout 600 dosbox -conf "$OUT/dosbox.conf" -exit \
  > "$OUT/dosbox.log" 2>&1

wait "$HOST_PID"
HOST_RC=$?

echo
echo "--- отчёт хоста ---"
sed 's/^/  /' "$OUT/host.tap"

echo
echo "--- отчёт DOS-стороны ---"
if [ -f "$OUT/S2.TAP" ]; then
  sed 's/^/  /' "$OUT/S2.TAP"
else
  echo "  S2.TAP не создан — DOS-программа не отработала"
  tail -15 "$OUT/dosbox.log" | sed 's/^/    /'
fi

echo
if [ "$HOST_RC" -ne 0 ] || grep -q '^not ok' "$OUT/host.tap" 2>/dev/null \
   || { [ -f "$OUT/S2.TAP" ] && grep -q '^not ok' "$OUT/S2.TAP"; }; then
  echo "  РЕЗУЛЬТАТ: ПРОВАЛ (делитель $DIVISOR)"
  exit 1
fi
echo "  РЕЗУЛЬТАТ: пройдено (делитель $DIVISOR)"
