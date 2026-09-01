#!/usr/bin/env bash
# Долгий прогон фаззеров XML и кадрирования.
#
#   soak.sh [--minutes N | --hours N] [--target native|msdos]
#           [--rounds N] [--seed N] [--selftest]
#
# --selftest заставляет фаззер сообщить о намеренном нарушении. Нужен
# затем, что фаззер, не умеющий провалиться, зелен всегда и не значит
# ничего — та же причина, по которой у валидатора контрактов есть свой
# selftest с намеренными дефектами.
#
# Программа-фаззер детерминирована и времени не знает: она получает семя и
# число раундов, отрабатывает их и выходит. Срок держит этот скрипт, меняя
# семя от прогона к прогону.
#
# Так падение на восемнадцатом часу воспроизводится одной командой с тем же
# семенем, а не «попробуй ещё сутки». Ровно та же причина, по которой в
# tstfuzz свой генератор, а не Random из RTL.
#
# Различаются три исхода, которые наивная проверка спутала бы:
#
#   * нарушение инварианта   — фаззер вышел с кодом 1 и назвал семя;
#   * падение                — код возврата от сигнала, семя в логе;
#   * зависание              — прогон не уложился в свой предел времени.
#
# Последнее опаснее прочих: цикл событий, который перестал возвращать
# управление, снаружи выглядит как «всё хорошо, просто медленно».
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SECONDS_TOTAL=60
TARGET=native
ROUNDS=200000
SEED=1
SELFTEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --minutes) SECONDS_TOTAL=$(( $2 * 60 )); shift 2 ;;
    --hours)   SECONDS_TOTAL=$(( $2 * 3600 )); shift 2 ;;
    --seconds) SECONDS_TOTAL="$2"; shift 2 ;;
    --target)  TARGET="$2"; shift 2 ;;
    --rounds)  ROUNDS="$2"; shift 2 ;;
    --seed)    SEED="$2"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

case "$TARGET" in
  native|msdos) ;;
  *) echo "неизвестный таргет: $TARGET" >&2; exit 64 ;;
esac

# Предел на один прогон. Он должен быть заметно больше ожидаемого времени
# прогона, но конечным: иначе зависание не отличить от долгой работы.
PER_RUN_TIMEOUT=600

echo "=============================================="
echo " Долгий прогон фаззеров"
echo "=============================================="
echo "  таргет:        $TARGET"
echo "  срок:          ${SECONDS_TOTAL} с"
echo "  раундов за раз: $ROUNDS"
echo "  первое семя:   $SEED"
echo

BIN=""
if [ "$TARGET" = native ]; then
  BIN="$(bash "$ROOT/dosnode/build/build.sh" --target native \
         --main "$HERE/soak.pas" --out soak)" || exit 1
else
  BIN="$(bash "$ROOT/dosnode/build/build.sh" --target msdos \
         --main "$HERE/soak.pas" --out SOAK)" || exit 1
  command -v dosbox >/dev/null 2>&1 || { echo "dosbox не установлен" >&2; exit 64; }
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

started=$(date +%s)
runs=0
seed=$SEED
rc=0

# Итоги складываются по всем прогонам: одна строка в конце важнее
# километра промежуточных.
tot_xml_ok=0; tot_xml_bad=0; tot_frame_ok=0; tot_frame_corrupt=0

run_native() {
  local extra=""
  [ "$SELFTEST" -eq 1 ] && extra=selftest
  # Из рабочего каталога: отчёт кладётся рядом, как и под DOSBox.
  ( cd "$WORK" && timeout "$PER_RUN_TIMEOUT" "$BIN" "$seed" "$ROUNDS" $extra ) \
      > "$WORK/out.txt" 2>&1
  return $?
}

run_msdos() {
  # Вердикт выносится по файлу отчёта, а не по коду возврата DOSBox и не
  # по его выводу: ни то ни другое под эмулятором не надёжно (RISKS, R3).
  # Программа пишет SOAK.OUT в примонтированный каталог — тем же способом,
  # каким тесты пишут TAP.
  local DOS_EXTRA=""
  [ "$SELFTEST" -eq 1 ] && DOS_EXTRA=selftest
  rm -rf "$WORK/dos"; mkdir -p "$WORK/dos"
  cp "$BIN" "$WORK/dos/SOAK.EXE"
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
mount c $WORK/dos
c:
SOAK.EXE $seed $ROUNDS $DOS_EXTRA
exit
EOF
  SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
    timeout --kill-after=10 "$PER_RUN_TIMEOUT" \
    dosbox -conf "$WORK/dosbox.conf" -exit \
      > "$WORK/dosbox.log" 2>&1
  local st=$?
  if [ "$st" -eq 124 ] || [ "$st" -eq 137 ]; then return 124; fi

  if [ ! -f "$WORK/dos/SOAK.OUT" ]; then
    # Отчёта нет — программа не отработала вовсе.
    tail -20 "$WORK/dosbox.log" > "$WORK/out.txt"
    return 1
  fi
  tr -d '\r' < "$WORK/dos/SOAK.OUT" > "$WORK/out.txt"

  grep -q '^soak ' "$WORK/out.txt" || return 1
  grep -q '^VIOLATION' "$WORK/out.txt" && return 1
  return 0
}

while :; do
  now=$(date +%s)
  elapsed=$(( now - started ))
  [ "$elapsed" -ge "$SECONDS_TOTAL" ] && break

  if [ "$TARGET" = native ]; then run_native; st=$?; else run_msdos; st=$?; fi

  if [ "$st" -eq 124 ]; then
    echo "ЗАВИСАНИЕ: семя $seed не уложилось в ${PER_RUN_TIMEOUT} с"
    sed 's/^/    /' "$WORK/out.txt"
    rc=1
    break
  fi
  if [ "$st" -ne 0 ]; then
    echo "ПРОВАЛ: семя $seed, код возврата $st"
    sed 's/^/    /' "$WORK/out.txt"
    rc=1
    break
  fi

  # Разбор строки итога.
  line="$(grep '^soak ' "$WORK/out.txt" | tail -1)"
  if [ -z "$line" ]; then
    echo "ПРОВАЛ: семя $seed не выдало строки итога"
    rc=1
    break
  fi
  for kv in $line; do
    case "$kv" in
      xml_ok=*)        tot_xml_ok=$(( tot_xml_ok + ${kv#*=} )) ;;
      xml_bad=*)       tot_xml_bad=$(( tot_xml_bad + ${kv#*=} )) ;;
      frame_ok=*)      tot_frame_ok=$(( tot_frame_ok + ${kv#*=} )) ;;
      frame_corrupt=*) tot_frame_corrupt=$(( tot_frame_corrupt + ${kv#*=} )) ;;
    esac
  done

  runs=$(( runs + 1 ))
  seed=$(( seed + 1 ))

  # Признак жизни примерно раз в минуту, а не на каждый прогон.
  if [ $(( runs % 50 )) -eq 0 ]; then
    echo "  ... ${elapsed} с, прогонов $runs, семя $seed"
  fi
done

elapsed=$(( $(date +%s) - started ))
echo
echo "прогонов:            $runs"
echo "секунд:              $elapsed"
echo "XML принято:         $tot_xml_ok"
echo "XML отвергнуто:      $tot_xml_bad"
echo "кадров сверено:      $tot_frame_ok"
echo "кадров испорчено:    $tot_frame_corrupt"
echo "следующее семя:      $seed"

if [ "$rc" -eq 0 ]; then
  echo "нарушений нет"
else
  echo "ЕСТЬ НАРУШЕНИЯ — воспроизводится: soak $seed $ROUNDS"
fi
exit "$rc"
