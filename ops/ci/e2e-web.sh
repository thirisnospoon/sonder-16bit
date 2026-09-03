#!/usr/bin/env bash
# Сценарии в настоящем браузере против ПОДНЯТОЙ системы.
#
# Заглушки нет: за адресом стоит весь состав — nginx, оболочка на Java,
# Firebird и шестнадцатибитная NODE-7 под DOSBox. Пост, появившийся в
# ленте, побывал решением, принятым программой под эмулятором.
#
# Отсюда и требование: система должна быть поднята. Поднимать её отсюда
# значило бы прятать в проверке минуты сборки и делать её непригодной
# для быстрого повтора.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="mcr.microsoft.com/playwright:v1.45.0-jammy"
CACHE_VOLUME="sonder-npm"
NET="${SONDER_E2E_NET:-sonder_default}"
URL="${SONDER_URL:-http://web:80}"

# Проверяем, что система и правда поднята. Иначе Playwright упрётся в
# отказ соединения и сообщит об этом так, что искать причину будут в
# сценариях.
if ! docker network inspect "$NET" >/dev/null 2>&1; then
  echo "нет сети $NET — система не поднята. Сначала ./sonder up" >&2
  exit 1
fi

echo "==> сценарии в браузере против $URL"

docker run --rm \
  --network "$NET" \
  --ipc=host \
  -v "$ROOT/web/e2e:/e2e" \
  -v "$CACHE_VOLUME:/root/.npm" \
  -w /e2e \
  -e "SONDER_URL=$URL" \
  -e CI=1 \
  "$IMAGE" \
  bash -eu -c '
    if [ -f package-lock.json ]; then
      npm ci --no-audit --no-fund
    else
      npm install --no-audit --no-fund
    fi
    npx playwright test
  '
exit $?
