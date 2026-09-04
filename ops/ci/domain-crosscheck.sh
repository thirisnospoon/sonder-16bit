#!/usr/bin/env bash
# Второе мнение о доменных правилах: согласны ли Паскаль и Пролог.
#
# Корпус порождён НАСТОЯЩИМ DecideCreatePost и лежит в
# contracts/generated/domain/createpost.tsv вместе с тем, что ядро
# решило. Пролог решает те же случаи заново и обязан сойтись на каждом.
#
# ПРЕДЕЛЫ БЕРУТСЯ ИЗ КОНТРАКТА, а не из корпуса. Возьми мы их оттуда —
# расхождение в самих пределах прошло бы незамеченным: обе стороны
# считали бы по одному и тому же неверному числу. Пролог сверяет
# полученное с тем, что записано в корпусе, и падает при несовпадении.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

cd "$ROOT" || exit 1

fail() { echo "ПРОВАЛ: $*" >&2; exit 1; }

# Разбор YAML двумя awk-выражениями, а не библиотекой: нужны два числа,
# и тащить ради них разборщик в этот скрипт значило бы завести ещё одну
# зависимость там, где хватает поиска строки.
MAXLEN="$(awk '/^  post_body_max_len:/ { found = 1 }
               found && /value:/ { print $2; exit }' contracts/domain/limits.yaml)"
RATE="$(awk '/^  posts_per_hour:/ { found = 1 }
             found && /value:/ { print $2; exit }' contracts/domain/limits.yaml)"

[ -n "$MAXLEN" ] && [ -n "$RATE" ] \
  || fail "в contracts/domain/limits.yaml не нашлись пределы поста"

IMAGE=sonder/prolog:1
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  docker build -q -f ops/compose/Dockerfile.prolog -t "$IMAGE" . > /dev/null \
    || fail "не собрать образ второго мнения"
fi

NICK_MIN="$(awk '/^  nick_min_len:/ { f = 1 } f && /value:/ { print $2; exit }' \
            contracts/domain/limits.yaml)"
NICK_MAX="$(awk '/^  nick_max_len:/ { f = 1 } f && /value:/ { print $2; exit }' \
            contracts/domain/limits.yaml)"
NAME_MAX="$(awk '/^  display_name_max_len:/ { f = 1 } f && /value:/ { print $2; exit }' \
            contracts/domain/limits.yaml)"

[ -n "$NICK_MIN" ] && [ -n "$NICK_MAX" ] && [ -n "$NAME_MAX" ] \
  || fail "в contracts/domain/limits.yaml не нашлись пределы ника и имени"

# Операций будет семь; чтобы добавление восьмой не означало правку в
# трёх местах, прогон вынесен в функцию.
run_op() {
  local title="$1" rules="$2" corpus="$3"
  shift 3
  [ -f "$corpus" ] || fail "нет корпуса $corpus — сначала ./sonder codegen"
  echo
  echo "==> $title: случаев $(grep -vc '^#' "$corpus")"
  docker run --rm -v "$ROOT:/work:ro" -w /work "$IMAGE" \
    "$rules" "$corpus" "$@" \
    || fail "$title: Паскаль и Пролог решают по-разному"
}

echo "==> пределы из контракта"
echo "  пост: $MAXLEN знаков, не больше $RATE в час"
echo "  ник: от $NICK_MIN до $NICK_MAX знаков; имя: до $NAME_MAX"

run_op "создание поста" dosnode/prolog/createpost.pl \
       contracts/generated/domain/createpost.tsv "$MAXLEN" "$RATE"

run_op "регистрация" dosnode/prolog/registeruser.pl \
       contracts/generated/domain/registeruser.tsv \
       "$NICK_MIN" "$NICK_MAX" "$NAME_MAX"
