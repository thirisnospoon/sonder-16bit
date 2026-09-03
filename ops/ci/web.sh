#!/usr/bin/env bash
# Сборка и тесты фронта.
#
# В контейнере, как и всё остальное: на хосте не должно заводиться ни
# node, ни npm. Кэш модулей живёт в томе — иначе каждый прогон качал бы
# TypeScript заново.
#
# Раннер тестов встроен в Node (`node --test`). Ещё одна зависимость
# ради describe/it была бы платой ни за что: правило проекта «убери — и
# ничего не сломается» применяется и к средствам сборки, а не только к
# коду (ADR-0012).
#
# Режимы:
#   test  — проверить (по умолчанию): строгая установка по замку, сборка,
#           тесты
#   build — только собрать
#   lock  — пересчитать package-lock.json после правки зависимостей
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="node:20-alpine"
CACHE_VOLUME="sonder-npm"

MODE="${1:-test}"

run_node() {
  docker run --rm \
    -v "$ROOT/web:/web" \
    -v "$CACHE_VOLUME:/root/.npm" \
    -w /web \
    "$IMAGE" \
    sh -eu -c "$1"
}

if [ "$MODE" = "lock" ]; then
  # Отдельным режимом, а не запасным путём в обычном прогоне. Молчаливое
  # «не сошлось с замком — ну и ладно, поставлю что есть» превращает
  # замок в украшение: он затем и нужен, чтобы расхождение было ЗАМЕТНО.
  echo "==> пересчёт package-lock.json"
  run_node 'npm install --no-audit --no-fund'
  exit $?
fi

if [ ! -f "$ROOT/web/package-lock.json" ]; then
  echo "нет web/package-lock.json — сначала ./sonder web lock" >&2
  exit 1
fi

# Установка БЕЗ конвейера: с `npm ci | tail` статус берётся у tail,
# который успешен всегда, и рассинхронизованный замок проходил бы молча,
# а падало бы потом на «tsc: not found» — в месте, к причине отношения не
# имеющем.
run_node "npm ci --no-audit --no-fund && npm run $MODE"
exit $?
