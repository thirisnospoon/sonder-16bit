#!/usr/bin/env bash
# Интеграционные тесты оболочки против настоящего Firebird.
#
#   ops/ci/run-it.sh [дополнительные цели maven]
#
# ПОЧЕМУ НЕ TESTCONTAINERS. Он умеет ровно одно, чего здесь не хватает, —
# управлять жизненным циклом контейнера, — и требует за это доступа к сокету
# Docker изнутри сборки. Изнутри контейнера его библиотека договаривается о
# версии API 1.32, демон требует не ниже 1.40, и сообщение об этом приходит
# от демона в адрес клиента: «client version is too old». По нему легко
# решить, что сломан Docker, а не библиотека внутри сборки. Час раскопок
# ничего не дал, а спайк S6 уже показал, что compose с этим образом
# работает. Здесь то же самое, без посредника.
#
# База поднимается на отдельной сети, и сборка подключается к ней же. Так не
# нужны ни проброс портов, ни --network host, ни сокет Docker внутри сборки:
# у контейнеров одной сети имя контейнера и есть имя хоста.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="maven:3.9-eclipse-temurin-8"
VOLUME="sonder-m2"
NET="sonder-it"
DB="sonder-it-db"
DB_IMAGE="firebirdsql/firebird:5"
DB_PATH="/var/lib/firebird/data/sonder.fdb"
PASSWORD="masterkey"

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
}
trap cleanup EXIT

cleanup
docker network create "$NET" >/dev/null || exit 1
docker volume inspect "$VOLUME" >/dev/null 2>&1 || docker volume create "$VOLUME" >/dev/null

echo "==> Firebird"
docker run -d --name "$DB" --network "$NET" \
  -e FIREBIRD_ROOT_PASSWORD="$PASSWORD" \
  -e FIREBIRD_DATABASE=sonder.fdb \
  -e FIREBIRD_USE_LEGACY_AUTH=true \
  "$DB_IMAGE" >/dev/null || exit 1

# Готовность — по файлу базы, а не по открытому порту: порт слушается
# раньше, чем база создана, и первая же попытка тогда падает «файл не
# найден». Это и есть та ошибка, которую спайк S6 закрыл healthcheck-ом.
echo -n "    ждём базу"
ready=0
for _ in $(seq 1 60); do
  if docker exec "$DB" test -f "$DB_PATH" 2>/dev/null; then
    ready=1
    break
  fi
  echo -n "."
  sleep 1
done
echo
if [ "$ready" -ne 1 ]; then
  echo "    база не поднялась за минуту"
  docker logs "$DB" 2>&1 | tail -30
  exit 1
fi
echo "    поднялась"

JDBC="jdbc:firebirdsql://$DB:3050/$DB_PATH?encoding=UTF8"

echo
echo "==> интеграционные тесты"
docker run --rm \
  --network "$NET" \
  -v "$ROOT:/work" \
  -v "$VOLUME:/root/.m2" \
  -w /work \
  "$IMAGE" \
  mvn -B --no-transfer-progress -Pit \
      -Dsonder.it.jdbcUrl="$JDBC" \
      -Dsonder.it.user=sysdba \
      -Dsonder.it.password="$PASSWORD" \
      verify "$@"
rc=$?

if [ "$rc" -ne 0 ]; then
  echo
  echo "--- журнал базы ---"
  docker logs "$DB" 2>&1 | tail -30
fi
exit "$rc"
