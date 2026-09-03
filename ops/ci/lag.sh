#!/usr/bin/env bash
# Лаг read-model: сколько проходит от «принято» до «видно в ленте».
#
# ARCHITECTURE.md §12 объявляет это показателем SLO с целью «p99 < 1 с»,
# и до этой проверки величина не измерялась ничем. Замер дренажа
# отвечал на соседний вопрос — за сколько разбирается ЗАВАЛ, — а SLO
# спрашивает про одно событие в спокойной системе.
#
# ЧТО ИМЕННО МЕРЯЕТСЯ. От ответа 201 на создание поста до появления
# этого поста в ленте автора. Между ними: запись в outbox той же
# транзакцией, дренаж (ходит раз в секунду), обогащение через ORB,
# запись в проекцию. Меньше периода дренажа лаг быть не может — и это
# не дефект, а устройство.
#
# ИМЕНА ПЕРЕМЕННЫХ ЛАТИНИЦЕЙ: кириллица в идентификаторе bash даёт
# «command not found», а скрипт продолжает с пустой переменной.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
URL="${SONDER_URL:-https://localhost:8443}"
PASSWORD='достаточно-длинный-пароль'

# Сколько постов. Домен разрешает 20 в час на человека, и упереться в
# правило посреди замера значило бы мерить скорость отказов.
COUNT="${SONDER_LAG_COUNT:-15}"

# Цель SLO из ARCHITECTURE.md §12, миллисекунды.
SLO_P99_MS="${SONDER_LAG_SLO_MS:-1000}"

# Как часто спрашивать ленту. Пятьдесят миллисекунд — это и разрешение
# замера: реже мерили бы собственный период опроса.
POLL_MS=50

# Сколько ждать одного поста, прежде чем признать его потерянным.
GIVE_UP_MS=15000

cd "$ROOT" || exit 1

fail() { echo; echo "ПРОВАЛ: $*" >&2; exit 1; }

NICK="lag$(date +%s | tail -c 6)$RANDOM"
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

echo "==> лаг read-model через $URL, постов $COUNT"

CODE="$(curl -sk -o /dev/null -w '%{http_code}' -X POST "$URL/api/users" \
        -H 'Content-Type: application/json' \
        -d "{\"nick\":\"$NICK\",\"displayName\":\"Лаг\",\"password\":\"$PASSWORD\"}")"
[ "$CODE" = 201 ] || fail "регистрация ответила $CODE"

CODE="$(curl -sk -o /dev/null -w '%{http_code}' -c "$COOKIES" \
        -X POST "$URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"nick\":\"$NICK\",\"password\":\"$PASSWORD\"}")"
[ "$CODE" = 204 ] || fail "вход ответил $CODE"

# Миллисекунды ДЕЛЕНИЕМ, а не форматом.
#
# `date +%s%3N` выглядит как «секунды и три цифры наносекунд» и здесь
# даёт девятнадцать цифр: модификатор ширины поддерживают не все
# реализации, и лишняя тройка молча игнорируется. Первая редакция
# считала наносекунды миллисекундами, сдавалась через двадцать восемь
# миллисекунд и докладывала «не появился за 15000 мс» — то есть не могла
# пройти никогда и вдобавок врала о том, что сделала.
#
# Числа тут девятнадцатизначные; знаковое 64-битное целое bash их
# держит с запасом до 2262 года.
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

MEASURED=""
for i in $(seq 1 "$COUNT"); do
  BODY="$(curl -sk -b "$COOKIES" -X POST "$URL/api/posts" \
          -H 'Content-Type: application/json' \
          -d "{\"body\":\"замер лага $i $(date +%s%N)\"}")"
  POST_ID="$(printf '%s' "$BODY" | sed -n 's/.*"postId":"\([^"]*\)".*/\1/p')"
  [ -n "$POST_ID" ] || fail "создание поста $i не вернуло postId: $BODY"
  START="$(now_ms)"

  SEEN=0
  while :; do
    # БЕЗ КОНВЕЙЕРА, и это не вкусовщина.
    #
    # `curl ... | grep -q` выглядит естественно и НЕ РАБОТАЕТ под
    # `pipefail`: `grep -q` закрывает канал по первому совпадению,
    # `curl` получает SIGPIPE и завершается ненулевым кодом, а
    # `pipefail` берёт именно его. То есть УСПЕШНОЕ совпадение даёт
    # ложное условие — и проверка не может позеленеть никогда.
    #
    # Первая редакция была написана именно так и честно провалилась на
    # исправной системе; нашлось сравнением с ручным прогоном.
    FEED="$(curl -sk -b "$COOKIES" "$URL/api/feed?limit=20")"
    case "$FEED" in
      *"$POST_ID"*)
        SEEN=1
        break
        ;;
    esac
    ELAPSED=$(( $(now_ms) - START ))
    if [ -n "${SONDER_LAG_DEBUG:-}" ]; then
      echo "    ${ELAPSED} мс: $(printf '%s' "$FEED" | head -c 80)" >&2
    fi
    if [ "$ELAPSED" -ge "$GIVE_UP_MS" ]; then
      break
    fi
    sleep "0.0$POLL_MS"
  done
  if [ "$SEEN" -ne 1 ]; then
    echo "  последний ответ ленты: $(printf '%s' "$FEED" | head -c 200)" >&2
    # В сообщении — сколько ждали НА САМОМ ДЕЛЕ, а не сколько
    # собирались. Разница между этими двумя числами однажды уже стоила
    # получаса поисков дефекта, которого не было.
    fail "пост $POST_ID не появился в ленте за ${ELAPSED} мс (предел ${GIVE_UP_MS})"
  fi

  LAG=$(( $(now_ms) - START ))
  MEASURED="$MEASURED $LAG"
  printf '  %2d/%d  %5d мс\n' "$i" "$COUNT" "$LAG"
done

echo
echo "$MEASURED" | tr ' ' '\n' | grep -v '^$' | sort -n | awk -v slo="$SLO_P99_MS" '
  { v[NR] = $1; sum += $1 }
  END {
    n = NR
    p50 = v[int(n * 0.5) + (n % 2 ? 1 : 0)]
    p99 = v[n < 100 ? n : int(n * 0.99)]
    printf("  замеров %d\n", n)
    printf("  мин  %5d мс\n", v[1])
    printf("  p50  %5d мс\n", p50)
    printf("  p99  %5d мс   (SLO < %d)\n", p99, slo)
    printf("  макс %5d мс\n", v[n])
    printf("  сред %5.0f мс\n", sum / n)
    if (p99 >= slo) {
      printf("\nSLO НАРУШЕН: p99 %d мс при цели %d мс\n", p99, slo)
      exit 1
    }
    printf("\nSLO соблюдён\n")
  }
'
