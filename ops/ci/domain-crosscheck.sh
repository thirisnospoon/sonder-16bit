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
CORPUS="contracts/generated/domain/createpost.tsv"
RULES="dosnode/prolog/createpost.pl"

cd "$ROOT" || exit 1

fail() { echo "ПРОВАЛ: $*" >&2; exit 1; }

[ -f "$CORPUS" ] || fail "нет корпуса $CORPUS — сначала ./sonder codegen"

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

echo "==> пределы из контракта: длина $MAXLEN знаков, частота $RATE в час"
echo "==> случаев в корпусе: $(grep -vc '^#' "$CORPUS")"
echo

docker run --rm -v "$ROOT:/work:ro" -w /work "$IMAGE" \
  "$RULES" "$CORPUS" "$MAXLEN" "$RATE" \
  || fail "Паскаль и Пролог решают по-разному"
