#!/usr/bin/env bash
# Сквозной прогон: настоящая NODE-7 под DOSBox против настоящего гейтвея.
#
# До сих пор каждое звено проверялось по отдельности, а стыки — по
# эталонам. Здесь не подменено ничего: 16-битная программа исполняется
# эмулятором, кадры идут через нульмодем, конверт разбирает tcsoap,
# решение принимает dmdecide.
#
# ПОРЯДОК ПОДЪЁМА ОБЯЗАТЕЛЕН. Нульмодем DOSBox — КЛИЕНТ, гейтвей —
# сервер. Запусти эмулятор первым, и он придёт на закрытый порт: не
# дождётся и не повторит. Поэтому сперва поднимается сторона Java, и
# только когда порт слушает — эмулятор.
#
# ОБЩАЯ СЕТЬ ХОСТА, а не своя. Гейтвей и нода должны видеть один
# localhost, а связывать два контейнера пользовательской сетью значит
# заводить имена, которые придётся объяснять и DOSBox, и Java. Здесь
# участников двое и живут они минуту.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOS_IMAGE="sonder/dos-toolchain:3.2.2"
MVN_IMAGE="maven:3.9-eclipse-temurin-8"
M2_VOLUME="sonder-m2"

PORT="${SONDER_E2E_PORT:-0}"
if [ "$PORT" = 0 ]; then
  # Свободный порт выбирается заранее: и Java, и DOSBox должны знать
  # один и тот же номер, а «любой свободный» знает только Java.
  PORT="$(python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
)"
fi

NODE_NAME="sonder-e2e-node-$$"
WORK="$(mktemp -d)"

cleanup() {
  docker rm -f "$NODE_NAME" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "=============================================="
echo " Сквозной прогон: гейтвей ↔ NODE-7"
echo "=============================================="
echo "  порт линии: $PORT"
echo

echo "==> сборка NODE-7"
docker run --rm -v "$ROOT:/work" -w /work "$DOS_IMAGE" \
  bash dosnode/build/build.sh --target msdos \
       --main dosnode/src/node7.pas --out NODE7 --outdir node \
  || { echo "нода не собралась" >&2; exit 1; }

cp "$ROOT/dosnode/build/out/msdos-node/NODE7.EXE" "$WORK/"
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
[serial]
serial1=nullmodem server:127.0.0.1 port:$PORT transparent:1
serial2=disabled
serial3=disabled
serial4=disabled
[autoexec]
mount c $WORK
c:
NODE7.EXE > CON.TXT
exit
EOF

# Сторона Java поднимается ПЕРВОЙ и ждёт ноду сама: порт должен слушать
# до того, как эмулятор пойдёт на него нульмодемом.
echo "==> гейтвей и проверка"
docker run --rm --network host \
  -v "$ROOT:/work" -v "$M2_VOLUME:/root/.m2" -w /work \
  "$MVN_IMAGE" \
  mvn -B --no-transfer-progress -Pit -f core/pom.xml verify \
      -Dgroups=e2e -Dit.test=NodeE2EIT -DfailIfNoTests=false \
      -Dsonder.e2e.port="$PORT" \
  > "$WORK/java.log" 2>&1 &
JAVA_PID=$!

# Ждём, пока порт начнёт слушать. Без этого эмулятор придёт на закрытый
# и молча не подключится.
echo "    ждём, пока гейтвей займёт порт"
LISTENING=0
for _ in $(seq 1 120); do
  if python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(0.3)
sys.exit(0 if s.connect_ex(('127.0.0.1', $PORT)) == 0 else 1)
" 2>/dev/null; then
    LISTENING=1
    break
  fi
  if ! kill -0 "$JAVA_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [ "$LISTENING" -ne 1 ]; then
  echo "гейтвей не занял порт $PORT" >&2
  tail -40 "$WORK/java.log" >&2
  wait "$JAVA_PID"
  exit 1
fi

echo "    порт слушает, поднимаем ноду"
docker run -d --name "$NODE_NAME" --network host \
  -v "$WORK:$WORK" -w "$WORK" \
  -e SDL_VIDEODRIVER=dummy -e SDL_AUDIODRIVER=dummy \
  "$DOS_IMAGE" \
  dosbox -conf "$WORK/dosbox.conf" -exit >/dev/null \
  || { echo "не поднялся эмулятор" >&2; exit 1; }

wait "$JAVA_PID"
RC=$?

echo
echo "==> вывод ноды"
# Целиком, а не отфильтрованный: когда нода молчит, важно КАЖДОЕ слово,
# которое она успела сказать, включая жалобы самого эмулятора.
docker logs "$NODE_NAME" 2>&1 | tail -20
if [ -f "$WORK/NODE7.LOG" ]; then
  echo "    из NODE7.LOG:"
  tr -d '' < "$WORK/NODE7.LOG" | sed 's/^/      /'
else
  echo "    NODE7.LOG не написан: программа не дошла даже до первой строки"
fi

echo
if [ "$RC" -eq 0 ]; then
  grep -aE "Tests run:" "$WORK/java.log" | tail -2
  echo "сквозной прогон пройден"
else
  echo "СКВОЗНОЙ ПРОГОН ПРОВАЛЕН"
  grep -aE "Tests run:|ERROR|FAIL" "$WORK/java.log" | tail -25
fi
exit "$RC"
