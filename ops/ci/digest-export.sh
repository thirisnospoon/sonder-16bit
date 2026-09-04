#!/usr/bin/env bash
# Выгрузка постов в плоский файл фиксированной ширины для свода.
#
# ВЫГРУЖАЕТ JAVA, а не SQL-скрипт, и это не вкусовщина. Разметка — ширина
# в БАЙТАХ, а `RPAD` в Firebird добивает до СИМВОЛОВ: имя «Андрей» в поле
# на 240 символов давало 246 байт, всё последующее уезжало на шесть, и
# свод считал по мусору — 90 300 байт на пост при доменном пределе в
# тысячу знаков. Выглядело правдоподобно.
#
# `DigestRecord` порождается из того же контракта, что и копибук COBOL,
# добивает по байтам и ОТКАЗЫВАЕТСЯ обрезать. Пока выгрузка шла SQL-ом,
# порождённый класс не использовался ничем: контракт правил одну сторону
# из двух, а «обе стороны порождаются» было утверждением без силы.
#
# ЗАПУСК БЕЗ SPRING. Пакету не нужны ни веб-сервер, ни линия к ядру, ни
# планировщик; поднимать их значило бы уронить выгрузку там, где она ни
# при чём.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGET="${1:?нужен путь к файлу выгрузки}"

cd "$ROOT" || exit 1

TARGET_DIR="$(cd "$(dirname "$TARGET")" && pwd)"
TARGET_NAME="$(basename "$TARGET")"

# Адрес базы — тот же, что у оболочки: она и владеет схемой. Имя хоста
# `db` разрешается внутри составной сети, поэтому контейнер выгрузки
# подключается к ней же.
NET="${SONDER_E2E_NET:-sonder_default}"
DB_URL="${SONDER_DB_URL:-jdbc:firebirdsql://db:3050//var/lib/firebird/data/sonder.fdb?encoding=UTF8}"
DB_PASSWORD="${SONDER_DB_PASSWORD:-masterkey}"

if ! docker network inspect "$NET" > /dev/null 2>&1; then
  echo "нет сети $NET — система не поднята. Сначала ./sonder up" >&2
  exit 1
fi

# Классы берутся из ОБРАЗА ОБОЛОЧКИ: там уже лежит собранный jar со
# всеми зависимостями, включая драйвер Jaybird. Собирать их второй раз
# значило бы завести вторую сборку, которая однажды разойдётся с первой.
docker run --rm   --network "$NET"   -v "$TARGET_DIR:/out"   -e SONDER_DB_URL="$DB_URL"   -e SONDER_DB_USER="${SONDER_DB_USER:-sysdba}"   -e SONDER_DB_PASSWORD="$DB_PASSWORD"   --entrypoint java   sonder-app   -cp /app/sonder.jar   -Dloader.main=sonder.report.DigestExport   org.springframework.boot.loader.PropertiesLauncher   "/out/$TARGET_NAME" || exit 1

if [ ! -s "$TARGET" ]; then
  echo "выгрузка пуста" >&2
  exit 1
fi

# ДЛИНА КАЖДОЙ ЗАПИСИ ПРОВЕРЯЕТСЯ, а не предполагается. У файла
# фиксированной ширины нет ни разделителей, ни заголовков: запись не той
# длины сдвигает всё последующее, программа читает мусор и НЕ ЗАМЕЧАЕТ
# этого — отчёт получается, только числа в нём неправда.
#
# LC_ALL=C обязателен: `length()` в awk считает СИМВОЛЫ при многобайтовой
# локали, и запись в 380 байт с шестью кириллическими буквами даёт 374.
# Проверка, перепутавшая единицу измерения, обвиняет невиновного ровно
# так же уверенно, как ловит виноватого.
EXPECTED=$(awk '/^record_bytes:/ { print $2 }' contracts/reports/digest-v1.yaml)
WRONG=$(LC_ALL=C awk -v want="$EXPECTED" 'length($0) != want { c++ } END { print c + 0 }' "$TARGET")
if [ "$WRONG" -gt 0 ]; then
  echo "записей не той длины: $WRONG из $(wc -l < "$TARGET")" >&2
  echo "ожидалось $EXPECTED байт. Первые расхождения (строка: длина):" >&2
  LC_ALL=C awk -v want="$EXPECTED" 'length($0) != want { print NR ": " length($0) }'     "$TARGET" | head -3 >&2
  exit 1
fi
