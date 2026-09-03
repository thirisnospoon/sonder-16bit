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
# Без --seed прогон продолжает журнал soak-log.tsv, а не начинается с
# единицы: иначе каждый следующий запуск повторял бы уже проверенные
# случаи, копя часы вместо покрытия. Сколько накоплено — ./sonder
# soak-total.
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
# Раундов за прогон. Умолчание ЗАВИСИТ ОТ ТАРГЕТА и подставляется
# после разбора аргументов — см. ниже.
ROUNDS=""
ROUNDS_NATIVE=200000
# Под DOSBox фаззер идёт на порядки медленнее: 200 000 раундов не
# укладываются в PER_RUN_TIMEOUT, и прогон объявляется ЗАВИСАНИЕМ —
# то есть нарушением, записанным в журнал, на совершенно исправном коде.
# Гейт фазы 5 при этом не берётся из-за настройки, а не из-за дефекта.
#
# Число подобрано ночной сборкой и проверено ею же: 2000 раундов
# укладываются с запасом.
ROUNDS_MSDOS=2000
SEED=1
SEED_MODE=auto
SELFTEST=0

# Журнал прогонов. Гейт Ф5 требует суток накопленного фаззинга, а «сутки
# накоплено» — утверждение, которое нужно чем-то подкрепить: сколько
# прогонов, с какими семенами, с каким исходом. Без записи повторный
# запуск к тому же молча гонял бы ровно те же случаи и время копил, а
# покрытие — нет.
LEDGER="$ROOT/dosnode/fuzz/soak-log.tsv"

# Следующее непройденное семя: на единицу больше самого большого
# записанного. Пространство семян ОБЩЕЕ для таргетов, а не своё у
# каждого. Так оно и использовалось (нативно 1..250, затем под DOSBox
# 251..434), и так два таргета не гоняют молча один и тот же вход,
# называя это независимым накоплением.
ledger_next_seed() {
  local max=0 d t s r from to rest
  [ -f "$LEDGER" ] || { echo 1; return; }
  while IFS=$'\t' read -r d t s r from to rest; do
    case "$d" in '#'*) continue ;; esac
    case "$to" in ''|*[!0-9]*) continue ;; esac
    [ "$to" -gt "$max" ] && max="$to"
  done < "$LEDGER"
  echo $(( max + 1 ))
}

# Как часто откладывать накопленное в журнал. Прогон на несколько часов,
# записывающий итог только в конце, теряет ВСЁ, если его прервали на
# последней минуте, — а прерывают именно долгие. Отсюда контрольные
# точки: потерять можно не больше десяти минут.
# Через переменную окружения, чтобы проверять саму отсрочку не десятью
# минутами ожидания: механизм, который нельзя завести на глазах, не
# проверен, а понадеян.
CHECKPOINT="${SOAK_CHECKPOINT:-600}"

# Строка журнала: секунды, прогоны, семена от и до, четыре счётчика, исход.
ledger_append() {
  [ "$SELFTEST" -eq 0 ] || return 0
  # Пустой отрезок не пишется, но НАРУШЕНИЕ пишется всегда, даже если
  # упал первый же прогон и считать нечего. Журнал без провалов —
  # журнал, ради которого не стоило заводиться.
  [ "$2" -gt 0 ] || [ "$9" != "чисто" ] || return 0
  if [ ! -f "$LEDGER" ]; then
    printf '%s\n' \
      '# Журнал долгих прогонов фаззера. Пишет soak.sh, сводка —' \
      '# ./sonder soak-total. Пространство семян общее для таргетов.' \
      '# когда	таргет	секунд	прогонов	от	до	xml_ok	xml_bad	frame_ok	frame_corrupt	исход' \
      > "$LEDGER"
  fi
  printf '%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$TARGET" \
    "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" >> "$LEDGER"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --minutes) SECONDS_TOTAL=$(( $2 * 60 )); shift 2 ;;
    --hours)   SECONDS_TOTAL=$(( $2 * 3600 )); shift 2 ;;
    --seconds) SECONDS_TOTAL="$2"; shift 2 ;;
    --target)  TARGET="$2"; shift 2 ;;
    --rounds)  ROUNDS="$2"; shift 2 ;;
    --seed)    SEED="$2"; SEED_MODE=fixed; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    *) echo "неизвестный аргумент: $1" >&2; exit 64 ;;
  esac
done

case "$TARGET" in
  native|msdos) ;;
  *) echo "неизвестный таргет: $TARGET" >&2; exit 64 ;;
esac

# Умолчание по таргету — ПОСЛЕ разбора аргументов: до него неизвестно,
# какой таргет выбран, а явный --rounds обязан победить умолчание.
if [ -z "$ROUNDS" ]; then
  if [ "$TARGET" = msdos ]; then ROUNDS="$ROUNDS_MSDOS"; else ROUNDS="$ROUNDS_NATIVE"; fi
fi

# Прогон в одиночку. Два одновременных читают журнал в одну и ту же
# секунду, получают одно и то же следующее семя и гоняют один и тот же
# вход, записывая это как два независимых отрезка. Так уже вышло дважды,
# и нашлось это не глазами, а сверкой диапазонов в журнале.
#
# flock, а не файл с PID: замок снимается сам, когда процесс умирает, —
# в том числе когда его убили. Файл с PID пережил бы убийство и запретил
# бы запускать фаззер до ручной уборки.
LOCK="$ROOT/dosnode/build/out/.soak.lock"
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "фаззер уже работает: два прогона возьмут одно и то же семя" >&2
  echo "если это ошибка — дождитесь конца или уберите $LOCK" >&2
  exit 75
fi

# Семя по умолчанию продолжает журнал, а не начинается с единицы: иначе
# каждый следующий прогон повторял бы уже проверенные случаи и копил
# часы вместо покрытия. Явное --seed остаётся для воспроизведения.
#
# Читается ПОД ЗАМКОМ: иначе смысл замка теряется ровно на этой строке.
if [ "$SEED_MODE" = auto ]; then
  SEED="$(ledger_next_seed)"
fi

# Предел на один прогон. Он должен быть заметно больше ожидаемого времени
# прогона, но конечным: иначе зависание не отличить от долгой работы.
#
# Через переменную окружения по той же причине, что и CHECKPOINT: ветка
# «зависание» опаснее прочих (цикл, переставший возвращать управление,
# снаружи выглядит как «просто медленно»), и завести её на глазах должно
# быть можно, не дожидаясь настоящего зависания.
PER_RUN_TIMEOUT="${SOAK_RUN_TIMEOUT:-600}"

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
         --main "$HERE/soak.pas" --out soak --outdir soak)" || exit 1
else
  BIN="$(bash "$ROOT/dosnode/build/build.sh" --target msdos \
         --main "$HERE/soak.pas" --out SOAK --outdir soak)" || exit 1
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

# То же самое, но с последней контрольной точки: это и уходит в журнал.
ck_start=$started; ck_runs=0; ck_seed_from=$SEED
ck_xml_ok=0; ck_xml_bad=0; ck_frame_ok=0; ck_frame_corrupt=0

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
      xml_ok=*)        tot_xml_ok=$(( tot_xml_ok + ${kv#*=} ))
                       ck_xml_ok=$(( ck_xml_ok + ${kv#*=} )) ;;
      xml_bad=*)       tot_xml_bad=$(( tot_xml_bad + ${kv#*=} ))
                       ck_xml_bad=$(( ck_xml_bad + ${kv#*=} )) ;;
      frame_ok=*)      tot_frame_ok=$(( tot_frame_ok + ${kv#*=} ))
                       ck_frame_ok=$(( ck_frame_ok + ${kv#*=} )) ;;
      frame_corrupt=*) tot_frame_corrupt=$(( tot_frame_corrupt + ${kv#*=} ))
                       ck_frame_corrupt=$(( ck_frame_corrupt + ${kv#*=} )) ;;
    esac
  done

  runs=$(( runs + 1 ))
  ck_runs=$(( ck_runs + 1 ))
  seed=$(( seed + 1 ))

  # Контрольная точка: накопленное с прошлой уходит в журнал, счётчики
  # обнуляются. Прерванный прогон теряет только текущий отрезок.
  ck_elapsed=$(( $(date +%s) - ck_start ))
  if [ "$ck_elapsed" -ge "$CHECKPOINT" ]; then
    ledger_append "$ck_elapsed" "$ck_runs" "$ck_seed_from" "$(( seed - 1 ))" \
      "$ck_xml_ok" "$ck_xml_bad" "$ck_frame_ok" "$ck_frame_corrupt" "чисто"
    echo "  ... отложено в журнал: ${ck_elapsed} с, семена ${ck_seed_from}..$(( seed - 1 ))"
    ck_start=$(date +%s); ck_runs=0; ck_seed_from=$seed
    ck_xml_ok=0; ck_xml_bad=0; ck_frame_ok=0; ck_frame_corrupt=0
  fi

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

# Остаток с последней контрольной точки. Нарушение записывается тоже, и
# именно с настоящим исходом: строка «НАРУШЕНИЕ» в журнале — это то, ради
# чего он и заводился. Самопроверка не записывается: она проваливается
# намеренно, и её строка испортила бы и сумму часов, и перечень семян.
# В исходе называется упавшее семя: строка «НАРУШЕНИЕ:470» говорит, чем
# воспроизводить, а диапазон «от 470 до 469» остаётся честным — отрезок
# не довёл до конца ни одного семени, и следующий запуск начнётся именно
# с упавшего, а не за ним.
verdict=$([ "$rc" -eq 0 ] && echo "чисто" || echo "НАРУШЕНИЕ:$seed")
if ledger_append "$(( $(date +%s) - ck_start ))" "$ck_runs" \
     "$ck_seed_from" "$(( seed - 1 ))" \
     "$ck_xml_ok" "$ck_xml_bad" "$ck_frame_ok" "$ck_frame_corrupt" \
     "$verdict"; then
  [ "$SELFTEST" -eq 0 ] && [ "$ck_runs" -gt 0 ] \
    && echo "записано в dosnode/fuzz/soak-log.tsv"
fi

exit "$rc"
