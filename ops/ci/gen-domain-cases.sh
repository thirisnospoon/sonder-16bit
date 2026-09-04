#!/usr/bin/env bash
# Корпус решений о создании поста — из настоящего доменного ядра.
#
# Второе мнение (dosnode/prolog/createpost.pl) сверяется НЕ с текстом
# правил, а с тем, что ядро на самом деле решило. Значит корпус обязан
# порождаться вместе с ядром: устаревший опаснее отсутствующего —
# проверка остаётся зелёной, сверяясь с прошлым.
#
# Тот же приём и тот же вид вывода, что у эталонных кадров, чтобы
# проверка дрейфа подхватила файл без отдельного упоминания:
#
#     <путь>  изменён | без изменений
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$ROOT/contracts/generated/domain"
OUT="$OUT_DIR/createpost.tsv"

mkdir -p "$OUT_DIR"

BIN="$(bash "$ROOT/dosnode/build/build.sh" --target native \
       --main "$ROOT/dosnode/tools/mkcases.pas" --out mkcases --outdir tools)" \
  || { echo "не собрался mkcases" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

( cd "$WORK" && "$BIN" ) > "$WORK/log.txt" 2>&1 || {
  echo "mkcases не отработал:" >&2
  cat "$WORK/log.txt" >&2
  exit 1
}

MARK="изменён"
if [ -f "$OUT" ] && cmp -s "$WORK/createpost.tsv" "$OUT"; then
  MARK="без изменений"
else
  cp "$WORK/createpost.tsv" "$OUT"
fi

echo "  contracts/generated/domain/createpost.tsv  $MARK"
sed -n 's/^случаев записано: /случаев: /p' "$WORK/log.txt"
