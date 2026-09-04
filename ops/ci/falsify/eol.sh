#!/usr/bin/env bash
# Видит ли сверка переводов строк то, что обязана видеть.
#
# Два случая, и оба были настоящими дырами:
#   1. НОВЫЙ файл. `git ls-files` без флагов перечисляет только
#      отслеживаемые, и файл, добавленный тем же изменением, проверку
#      проходил, а ронять её начинал уже после коммита — без всякой
#      связи с тем, кто его завёл;
#   2. файл с НЕ-ASCII именем. git экранирует такие пути в выводе, и
#      проверка существования по экранированному имени ложна: файл молча
#      выпадает, а проверка отвечает «все LF», не посмотрев на него.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ASCII_FILE="$ROOT/ops/compose/eol-probe.json"
CYR_FILE="$ROOT/ops/compose/проба-переводов.json"
trap 'rm -f "$ASCII_FILE" "$CYR_FILE"' EXIT

cd "$ROOT" || exit 1

bash ./sonder check-eol > /tmp/f-eol-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; exit 1; }

printf '{ "a": 1 }\r\n' > "$ASCII_FILE"
bash ./sonder check-eol > /tmp/f-eol-1.log 2>&1 \
  && { echo "  ЗЕЛЕНО НА НОВОМ ФАЙЛЕ С CRLF"; exit 1; }
rm -f "$ASCII_FILE"

printf '{ "a": 1 }\r\n' > "$CYR_FILE"
bash ./sonder check-eol > /tmp/f-eol-2.log 2>&1 \
  && { echo "  ЗЕЛЕНО НА ФАЙЛЕ С РУССКИМ ИМЕНЕМ"; exit 1; }

echo "  новые файлы и не-ASCII имена под проверкой"
