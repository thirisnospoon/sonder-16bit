#!/usr/bin/env bash
# Эталонные кадры линии из настоящего кодировщика ядра.
#
# Гейтвей на Java обязан кодировать кадры так же, как tcframe. Проверяет
# это FrameGoldenTest, сверяясь с этим файлом, — а значит файл обязан
# порождаться заново вместе с ядром. Устаревший эталон опаснее
# отсутствующего: проверка остаётся зелёной, сверяясь с прошлым.
#
# Печатает строку того же вида, что и остальные генераторы, чтобы
# проверка дрейфа подхватила файл без отдельного упоминания:
#
#     <путь>  изменён | без изменений
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/contracts/generated/frames"
OUT="$OUT_DIR/frames.bin"

mkdir -p "$OUT_DIR"

BIN="$(bash "$ROOT/dosnode/build/build.sh" --target native \
       --main "$ROOT/dosnode/tools/mkframes.pas" --out mkframes --outdir tools)" \
  || { echo "не собрался mkframes" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Программа пишет в рабочий каталог, поэтому запускается из него.
( cd "$WORK" && "$BIN" ) > "$WORK/log.txt" 2>&1 || {
  echo "mkframes не отработал:" >&2
  cat "$WORK/log.txt" >&2
  exit 1
}

MARK="изменён"
if [ -f "$OUT" ] && cmp -s "$WORK/frames.bin" "$OUT"; then
  MARK="без изменений"
else
  cp "$WORK/frames.bin" "$OUT"
fi

echo "  contracts/generated/frames/frames.bin  $MARK"
sed -n 's/^кадров записано: /кадров: /p' "$WORK/log.txt"
