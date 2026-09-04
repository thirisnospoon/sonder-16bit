#!/usr/bin/env bash
# Ночной свод: выгрузка, пакет на COBOL, отчёт.
#
# Пакет не трогает оперативный путь: он читает плоский файл, который
# выгрузила оболочка, и пишет текстовый отчёт (ADR-0018). Ни линии, ни
# проекции, ни ядра.
#
# ВХОД СОРТИРУЕТСЯ ЗДЕСЬ, а не в программе. Контрольный переход по
# автору работает только на упорядоченном входе, и несортированный файл
# дал бы столько «итогов по автору», сколько раз автор встретился —
# правдоподобно и неверно. Сортирует выгрузка, у которой есть индекс.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${SONDER_REPORT_DIR:-$ROOT/report/out}"
IMAGE=sonder/report:1

cd "$ROOT" || exit 1

mkdir -p "$OUT"

# Период: по умолчанию вчерашние сутки — свод ночной. Аргументы
# прокидываются в выгрузку как есть.
echo "==> выгрузка постов"
bash ops/ci/digest-export.sh "$OUT/posts.dat" "$@" || exit 1
RECORDS=$(wc -l < "$OUT/posts.dat")
BYTES=$(wc -c < "$OUT/posts.dat")
echo "  записей: $RECORDS, байт: $BYTES"

echo
echo "==> пакет на COBOL"
docker run --rm \
  -v "$OUT:/data" \
  -e SONDER_DIGEST_INPUT=/data/posts.dat \
  -e SONDER_DIGEST_OUTPUT=/data/digest.txt \
  "$IMAGE" || { echo "пакет отказал" >&2; exit 1; }

echo
echo "==> отчёт"
cat "$OUT/digest.txt"
