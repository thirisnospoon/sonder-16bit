#!/usr/bin/env bash
# Ловит ли сверка идентификаторов то, что ломается на самом деле.
#
# Три случая. Первые два обязаны краснеть — в bash они не работают.
# Третий обязан НЕ краснеть: имя функции кириллицей законно, и проверка,
# которая ругается на исправный код, учит себя игнорировать.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PROBE="$ROOT/ops/ci/shell-probe.sh"
trap 'rm -f "$PROBE"' EXIT

cd "$ROOT" || exit 1

bash ./sonder check-shell > /tmp/f-shell-0.log 2>&1 \
  || { echo "  БАЗА КРАСНАЯ"; exit 1; }

printf '#!/usr/bin/env bash\nНИК=proba\necho "$НИК"\n' > "$PROBE"
bash ./sonder check-shell > /tmp/f-shell-1.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ ПРИСВАИВАНИИ КИРИЛЛИЦЕЙ"; exit 1; }

printf '#!/usr/bin/env bash\nf() { local начало=5; echo "$начало"; }\n' > "$PROBE"
bash ./sonder check-shell > /tmp/f-shell-2.log 2>&1 \
  && { echo "  ЗЕЛЕНО ПРИ local КИРИЛЛИЦЕЙ"; exit 1; }

# Имя функции — законно: измерено на bash 5.3, вызывается и возвращает
# значения как обычно. Ложное срабатывание тут хуже пропуска.
printf '#!/usr/bin/env bash\nпопытка() { echo 42; }\nV="$(попытка)"\necho "$V"\n' > "$PROBE"
bash ./sonder check-shell > /tmp/f-shell-3.log 2>&1 \
  || { echo "  КРАСНО НА ЗАКОННОМ ИМЕНИ ФУНКЦИИ — ложное срабатывание"; exit 1; }

echo "  присваивание и local ловятся, имя функции — нет"
