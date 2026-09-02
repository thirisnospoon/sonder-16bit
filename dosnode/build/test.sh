#!/usr/bin/env bash
# Прогон тестов NODE-7 на обоих таргетах.
#
#   test.sh [native|msdos|both]     по умолчанию both
#
# Смысл в том, чтобы одни и те же проверки шли и на нативном таргете, где
# они занимают доли секунды, и на 16-битном, который поедет в бой.
# Расхождение результатов означает дефект, а не особенность платформы:
# именно ради этого сравнения существует дуальная сборка (ADR-0005).
set -uo pipefail

WHAT="${1:-both}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TESTS="$ROOT/dosnode/tests"

# Эталонные конверты. Их записал маршалер JAXB на настоящих
# сгенерированных типах, и тест ядра разбирает ИМЕННО эти байты: «обе
# стороны порождены из одного WSDL» и «понимают друг друга» — разные
# утверждения.
ENVELOPES="$ROOT/contracts/generated/envelopes"

# Под DOS имя обязано быть 8.3, поэтому соответствие задано явно.
# Эталон, которого в таблице нет, до эмулятора не доедет и останется
# непроверенным на том самом таргете, ради которого всё затеяно. Поэтому
# он роняет прогон, а не пропускается молча.
dos_name() {
  case "$(basename "$1")" in
    register-user.xml)  echo REGUSER.XML ;;
    create-post.xml)    echo CRPOST.XML ;;
    create-comment.xml) echo CRCOMM.XML ;;
    delete-post.xml)    echo DELPOST.XML ;;
    follow-user.xml)    echo FOLLOW.XML ;;
    unfollow-user.xml)  echo UNFOLLOW.XML ;;
    ban-user.xml)       echo BANUSER.XML ;;
    ping.xml)           echo PING.XML ;;
    *) return 1 ;;
  esac
}

# Все эталоны имеют имя под DOS. Проверяется до сборки: узнать об этом
# из середины прогона хуже, чем сразу.
check_envelopes() {
  local bad=0 f
  for f in "$ENVELOPES"/*.xml; do
    [ -e "$f" ] || continue
    if ! dos_name "$f" >/dev/null 2>&1; then
      echo "эталон без имени 8.3: $(basename "$f") — до эмулятора не доедет" >&2
      bad=1
    fi
  done
  return $bad
}

# Рядом с нативным двоичным файлом: он читает эталоны из рабочего каталога.
place_gold() {
  local dir="$1" f
  mkdir -p "$dir"
  for f in "$ENVELOPES"/*.xml; do
    [ -e "$f" ] || continue
    cp "$f" "$dir/$(basename "$f")"
  done
}

# Аргументы --data для эмулятора: по одному на эталон.
gold_data_args() {
  local f
  for f in "$ENVELOPES"/*.xml; do
    [ -e "$f" ] || continue
    printf -- '--data\n%s:%s\n' "$f" "$(dos_name "$f")"
  done
}

rc=0

if ! check_envelopes; then
  echo "ЕСТЬ ПРОВАЛЫ: эталонные конверты не готовы к 16-битному таргету" >&2
  exit 1
fi

run_native() {
  echo "=============================================="
  echo " Нативный таргет"
  echo "=============================================="
  local failed=0
  for src in "$TESTS"/*.pas; do
    local name; name="$(basename "${src%.pas}")"
    local bin
    # build.sh печатает путь к двоичному файлу в stdout, и он тут же
    # уходит в подстановку. Вместе с ним туда уходила и диагностика
    # провала: тест, который не собрался, исчезал из прогона молча —
    # вердикт краснел, а причина не показывалась нигде.
    if ! bin="$(bash "$HERE/build.sh" --target native --main "$src" --out "$name")"; then
      echo "--- $name ---"
      echo "$bin" | sed 's/^/  /'
      failed=1; continue
    fi
    echo "--- $name ---"
    place_gold "$(dirname "$bin")"
    # Отчёт пишется в рабочий каталог, поэтому запускаем из него.
    ( cd "$(dirname "$bin")" && "./$name" ) | sed 's/^/  /'
    local st=${PIPESTATUS[0]}
    if [ "$st" -ne 0 ]; then
      echo "  ПРОВАЛ: код возврата $st"
      failed=1
    fi
  done
  return $failed
}

run_msdos() {
  echo
  echo "=============================================="
  echo " Таргет i8086-msdos"
  echo "=============================================="
  local failed=0
  for src in "$TESTS"/*.pas; do
    local name; name="$(basename "${src%.pas}")"
    local upper; upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
    local bin
    if ! bin="$(bash "$HERE/build.sh" --target msdos --main "$src" --out "$name")"; then
      echo "--- $name ---"
      echo "$bin" | sed 's/^/  /'
      failed=1; continue
    fi
    echo "--- $name ($(stat -c%s "$bin") байт) ---"
    # Вердикт выносится по TAP-файлу, а не по коду возврата эмулятора (R3).
    # mapfile отсутствует в bash 3, поэтому список читается построчно:
    # в путях и именах 8.3 пробелов нет, но разделять по строкам всё
    # равно правильнее, чем по $IFS.
    local data_args=()
    while IFS= read -r arg; do
      data_args+=("$arg")
    done < <(gold_data_args)

    bash "$ROOT/ops/ci/run-dos-tap.sh" \
         --exe "$bin" --tap "${upper}.TAP" --timeout 300 --name "$name" \
         "${data_args[@]}" \
      | sed 's/^/  /'
    [ "${PIPESTATUS[0]}" -ne 0 ] && failed=1
  done
  return $failed
}

case "$WHAT" in
  native) run_native || rc=1 ;;
  msdos)  run_msdos  || rc=1 ;;
  both)
    run_native || rc=1
    run_msdos  || rc=1
    ;;
  *) echo "неизвестный таргет: $WHAT" >&2; exit 64 ;;
esac

echo
if [ "$rc" -eq 0 ]; then
  echo "тесты пройдены на всех таргетах"
else
  echo "ЕСТЬ ПРОВАЛЫ"
fi
exit "$rc"
