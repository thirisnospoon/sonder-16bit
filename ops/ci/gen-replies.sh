#!/usr/bin/env bash
# Эталонные ОТВЕТЫ: то, что ядро на самом деле кладёт в линию.
#
# Эталонные конверты были только на запросы: Java порождает, Pascal
# разбирает. Обратное направление не проверялось ничем — и это стоило
# четырёх дефектов подряд, найденных сквозным прогоном, а не тестом.
#
# Здесь ответы порождает НАСТОЯЩИЙ писатель ядра, а разбирает их
# настоящий связыватель гейтвея. Файл порождается заново вместе с ядром:
# устаревший эталон опаснее отсутствующего — проверка остаётся зелёной,
# сверяясь с прошлым.
#
# Печатает строку того же вида, что и остальные генераторы, чтобы
# проверка дрейфа подхватила файл без отдельного упоминания:
#
#     <путь>  изменён | без изменений
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/contracts/generated/replies"
OUT="$OUT_DIR/replies.bin"

mkdir -p "$OUT_DIR"

BIN="$(bash "$ROOT/dosnode/build/build.sh" --target native \
       --main "$ROOT/dosnode/tools/mkreplies.pas" --out mkreplies --outdir tools)" \
  || { echo "не собрался mkreplies" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Программа пишет в рабочий каталог, поэтому запускается из него.
( cd "$WORK" && "$BIN" ) > "$WORK/log.txt" 2>&1 || {
  echo "mkreplies не отработал:" >&2
  cat "$WORK/log.txt" >&2
  exit 1
}

MARK="изменён"
if [ -f "$OUT" ] && cmp -s "$WORK/replies.bin" "$OUT"; then
  MARK="без изменений"
else
  cp "$WORK/replies.bin" "$OUT"
fi

echo "  contracts/generated/replies/replies.bin  $MARK"
sed -n 's/^ответов записано: /ответов: /p' "$WORK/log.txt"
