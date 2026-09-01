#!/usr/bin/env bash
# Диагностический прогон S2: жив ли UART и ходят ли байты через nullmodem.
# Перебирает варианты настройки последовательного порта DOSBox.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODEL="${MODEL:-large}"
PORT="${S2_PORT:-5310}"
OUT="$HERE/out/probe"

rm -rf "$OUT"; mkdir -p "$OUT"

echo "--- компиляция ---"
if ! fpc-dos "$MODEL" -FE"$OUT" -oPROBE.EXE "$HERE/src/s2probe.pas" \
       > "$OUT/compile.log" 2>&1; then
  echo "КОМПИЛЯЦИЯ ПРОВАЛЕНА"
  grep -E 'Error|Fatal' "$OUT/compile.log" | head -10 | sed 's/^/  /'
  exit 2
fi
echo "PROBE.EXE: $(stat -c%s "$OUT/PROBE.EXE") байт"

# Хост: принимает подключение, сыплет байты, печатает всё, что вернулось.
cat > "$OUT/peer.py" <<'PYEOF'
import os, socket, sys, time
port = int(os.environ["S2_PORT"])
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port)); srv.listen(1); srv.settimeout(45)
try:
    s, a = srv.accept()
except socket.timeout:
    print("хост: подключения не было"); sys.exit(1)
print("хост: подключился", a)
s.settimeout(0.3)
got = bytearray()
t0 = time.time()
sent = 0
while time.time() - t0 < 14:
    if time.time() - t0 > 2 and sent < 16:
        try:
            s.sendall(bytes([0x30 + (sent % 10)])); sent += 1
        except OSError as e:
            print("хост: ошибка отправки", e); break
        time.sleep(0.3)
    try:
        b = s.recv(4096)
        if not b: print("хост: соединение закрыто той стороной"); break
        got.extend(b)
    except socket.timeout:
        pass
print(f"хост: отправлено {sent}, принято {len(got)}")
print("хост: принятые байты:", got[:40].hex(" ") or "(ничего)")
s.close(); srv.close()
PYEOF

run_variant() {
  local name="$1" serialcfg="$2"
  echo
  echo "=============================================="
  echo " вариант: $name"
  echo "   $serialcfg"
  echo "=============================================="
  local vout="$OUT/$name"
  mkdir -p "$vout"
  cp "$OUT/PROBE.EXE" "$vout/"

  cat > "$vout/dosbox.conf" <<EOF
[sdl]
autolock=false
[dosbox]
memsize=16
[cpu]
core=auto
cycles=max
[serial]
serial1=$serialcfg
[autoexec]
mount c $vout
c:
PROBE.EXE
exit
EOF

  S2_PORT="$PORT" python3 "$OUT/peer.py" > "$vout/peer.log" 2>&1 &
  local pid=$!
  sleep 1

  SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
    timeout 90 dosbox -conf "$vout/dosbox.conf" -exit > "$vout/dosbox.log" 2>&1
  wait "$pid" 2>/dev/null

  echo "--- DOS ---"
  [ -f "$vout/PROBE.TAP" ] && sed 's/^/  /' "$vout/PROBE.TAP" || echo "  PROBE.TAP не создан"
  echo "--- хост ---"
  sed 's/^/  /' "$vout/peer.log"
  echo "--- DOSBox о порте ---"
  grep -i serial "$vout/dosbox.log" | sed 's/^/  /' || echo "  (тишина)"
}

run_variant "transparent" "nullmodem server:127.0.0.1 port:$PORT transparent:1"
run_variant "plain"       "nullmodem server:127.0.0.1 port:$PORT"
run_variant "dummy"       "dummy"
