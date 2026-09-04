#!/usr/bin/env bash
# Выгрузка постов в плоский файл фиксированной ширины для свода.
#
# Разметка объявлена в contracts/reports/digest-v1.yaml и порождена в
# report/copybook/DIGEST.cpy — здесь она ПОВТОРЯЕТСЯ только в виде
# ширин, потому что выгрузка идёт SQL-запросом, а не через Java. Это
# осознанный долг: как только выгрузка переедет в оболочку, ширины
# возьмутся из порождённого DigestRecord, и повтор исчезнет.
#
# Пока же ширины сверяются проверкой: сумма обязана дать record_bytes.
#
# СОРТИРОВКА ЗДЕСЬ. Контрольный переход в COBOL требует упорядоченного
# входа, а индекс есть у базы, а не у пакета.
#
# ДЛИНЫ В БАЙТАХ И В СИМВОЛАХ СЧИТАЕТ БАЗА: OCTET_LENGTH и
# CHAR_LENGTH. Считать их на стороне скрипта значило бы завести третье
# мнение о том, что такое длина.
#
# ДОБИВКА СЧИТАЕТСЯ В БАЙТАХ, и это главная тонкость файла. `RPAD`
# добивает до СИМВОЛОВ, а COBOL читает БАЙТЫ: имя «Андрей» в поле на
# 240 символов даёт 246 байт, и всё последующее уезжает на шесть.
# Первая редакция так и делала — отчёт получался, выглядел правдоподобно
# и врал: 90 300 байт на пост при доменном пределе в тысячу знаков.
#
# Поэтому цель добивки — не 240 символов, а `240 минус перерасход`, где
# перерасход это `OCTET_LENGTH - CHAR_LENGTH`. Тогда поле занимает ровно
# 240 байт при любом содержимом.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${1:?нужен путь к файлу выгрузки}"
DB_PASSWORD="${SONDER_DB_PASSWORD:-masterkey}"

cd "$ROOT" || exit 1

SQL=$(cat <<'ЗАПРОС'
SET HEADING OFF;
SET LIST OFF;
SELECT
  RPAD(p.id, 40 - (OCTET_LENGTH(p.id) - CHAR_LENGTH(p.id)), ' ')
  || RPAD(u.nick, 80 - (OCTET_LENGTH(u.nick) - CHAR_LENGTH(u.nick)), ' ')
  || RPAD(u.display_name,
          240 - (OCTET_LENGTH(u.display_name) - CHAR_LENGTH(u.display_name)),
          ' ')
  || SUBSTRING(CAST(p.created_at AS VARCHAR(30)) FROM 1 FOR 4)
  || SUBSTRING(CAST(p.created_at AS VARCHAR(30)) FROM 6 FOR 2)
  || SUBSTRING(CAST(p.created_at AS VARCHAR(30)) FROM 9 FOR 2)
  || LPAD(OCTET_LENGTH(p.body), 6, '0')
  || LPAD(CHAR_LENGTH(p.body), 6, '0')
FROM posts p
JOIN users u ON u.id = p.author_id
WHERE p.status = 'VISIBLE'
ORDER BY u.nick, p.created_at;
ЗАПРОС
)

printf '%s\n' "$SQL" \
  | docker compose exec -T db isql -b -q \
      -user sysdba -password "$DB_PASSWORD" \
      -ch UTF8 \
      /var/lib/firebird/data/sonder.fdb 2>/tmp/digest-export.err \
  | sed 's/[[:space:]]*$//' \
  | grep -v '^$' > "$TARGET"

if [ ! -s "$TARGET" ]; then
  echo "выгрузка пуста. Ошибки isql:" >&2
  head -5 /tmp/digest-export.err >&2
  exit 1
fi

# ДЛИНА КАЖДОЙ ЗАПИСИ ПРОВЕРЯЕТСЯ, а не предполагается. У файла
# фиксированной ширины нет ни разделителей, ни заголовков: запись не той
# длины сдвигает всё последующее, программа читает мусор и НЕ ЗАМЕЧАЕТ
# этого — отчёт получается, только числа в нём неправда. Ровно так и
# вышло, пока добивка считалась в символах.
# LC_ALL=C ОБЯЗАТЕЛЕН: `length()` в awk считает СИМВОЛЫ, если локаль
# многобайтовая, и запись в 380 байт с шестью кириллическими буквами
# даёт 374. Первая редакция проверки так и мерила и объявила негодными
# 6971 запись из 6972 — при, возможно, исправной выгрузке. Проверка,
# перепутавшая единицу измерения, обвиняет невиновного ровно так же
# уверенно, как ловит виноватого.
EXPECTED=$(awk '/^record_bytes:/ { print $2 }' contracts/reports/digest-v1.yaml)
BAD=$(LC_ALL=C awk -v want="$EXPECTED" '
  { n = length($0) }
  n != want { print NR ": " n; bad++ }
  END { exit 0 }
' "$TARGET" | head -3)
WRONG=$(LC_ALL=C awk -v want="$EXPECTED" 'length($0) != want { c++ } END { print c + 0 }' "$TARGET")
if [ "$WRONG" -gt 0 ]; then
  echo "записей не той длины: $WRONG из $(wc -l < "$TARGET")" >&2
  echo "ожидалось $EXPECTED байт. Первые расхождения (строка: длина):" >&2
  printf '%s
' "$BAD" >&2
  exit 1
fi
