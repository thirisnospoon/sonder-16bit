#!/usr/bin/env bash
# Maven в контейнере. На хосте не устанавливается ничего — ни JDK, ни Maven.
#
#   ops/ci/mvn.sh [цели и флаги maven]
#
# Java 8 — ограничение проекта, а не выбор здесь: это последний JDK, несущий
# CORBA в составе (ADR-0004). Отсюда и Spring Boot 2.7, и Hibernate 5, и
# Jaybird 4 — везде последняя ветка, работающая на восьмёрке.
#
# Репозиторий зависимостей лежит в именованном томе, а не в каталоге проекта.
# Иначе он попал бы в рабочее дерево, засорил git status и переехал бы в
# образ при следующей сборке. Том переживает контейнеры и не переживает
# `docker volume rm`, что и требуется.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="maven:3.9-eclipse-temurin-8"
VOLUME="sonder-m2"

docker volume inspect "$VOLUME" >/dev/null 2>&1 || docker volume create "$VOLUME" >/dev/null

# -B: без интерактива и без анимации прогресса, иначе лог в CI нечитаем.
# --no-transfer-progress: то же про скачивание.
exec docker run --rm \
  -v "$ROOT:/work" \
  -v "$VOLUME:/root/.m2" \
  -w /work \
  "$IMAGE" \
  mvn -B --no-transfer-progress "$@"
