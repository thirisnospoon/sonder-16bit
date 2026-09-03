#!/usr/bin/env bash
# Замер Lighthouse против ПОДНЯТОЙ системы.
#
# Как и сценарии в браузере, идёт против всего состава: nginx, оболочка,
# Firebird, шестнадцатибитная NODE-7. Замерять статику отдельно смысла
# нет — гейт говорит о странице, которую видит человек, а её первый экран
# зависит от ответа `/api/me` ровно так же, как от размера бандла.
#
# ТРИ ПРОГОНА И МЕДИАНА, а не один замер. Оценка производительности
# складывается из времён, а времена на машине общего назначения шумят:
# соседний контейнер, сборщик мусора, кеш файловой системы. Одиночный
# замер поэтому не измеряет почти ничего — он с равным успехом даёт 94 и
# 71 на одном и том же коде. Медиана трёх — то, что рекомендует и сама
# Lighthouse: она гасит один выброс, но настоящую просадку спрятать не
# сможет.
#
# У проверки есть порог, и она умеет провалиться: раздел ниже порога
# возвращает ненулевой код. Замер без порога — это отчёт, а не проверка.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="mcr.microsoft.com/playwright:v1.45.0-jammy"
CACHE_VOLUME="sonder-npm"
NET="${SONDER_E2E_NET:-sonder_default}"
URL="${SONDER_URL:-https://web:443/}"
RUNS="${SONDER_LH_RUNS:-3}"

# Порог гейта Ф9. Один на все четыре раздела: занижать порог тому
# разделу, который не дотянул, — значит мерить не систему, а своё
# терпение.
THRESHOLD="${SONDER_LH_MIN:-90}"

OUT="$ROOT/web/lighthouse"

if ! docker network inspect "$NET" >/dev/null 2>&1; then
  echo "нет сети $NET — система не поднята. Сначала ./sonder up" >&2
  exit 1
fi

echo "==> Lighthouse против $URL, прогонов $RUNS, порог $THRESHOLD"

mkdir -p "$OUT"
rm -f "$OUT"/run-*.json

docker run --rm \
  --network "$NET" \
  --ipc=host \
  -v "$OUT:/out" \
  -v "$ROOT/ops/ci:/ci:ro" \
  -v "$CACHE_VOLUME:/root/.npm" \
  -w /out \
  -e "SONDER_URL=$URL" \
  -e "RUNS=$RUNS" \
  -e "THRESHOLD=$THRESHOLD" \
  "$IMAGE" \
  bash -eu -c '
    LH="lighthouse@11.7.1"

    # Chrome в образе Playwright лежит по версионному пути, и сама
    # Lighthouse его не находит: она ищет в системных местах, а тут
    # браузер поставлен рядом с драйвером. Без этой строки замер
    # отказывает с жалобой на CHROME_PATH.
    CHROME_PATH="$(ls -d /ms-playwright/chromium-*/chrome-linux/chrome | head -1)"
    export CHROME_PATH
    echo "  браузер: $CHROME_PATH"

    for i in $(seq 1 "$RUNS"); do
      echo "  прогон $i из $RUNS"
      npm exec --yes -- "$LH" "$SONDER_URL" \
        --quiet \
        --output=json \
        --output-path="/out/run-$i.json" \
        --only-categories=performance,accessibility,best-practices,seo \
        --chrome-flags="--headless=new --no-sandbox --disable-dev-shm-usage --disable-gpu --ignore-certificate-errors"
    done

    node /ci/lighthouse-median.js "$THRESHOLD" /out/run-*.json
  '
exit $?
