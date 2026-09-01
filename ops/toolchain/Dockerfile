# syntax=docker/dockerfile:1
#
# Образ тулчейна для DOS-ноды.
#
# Собирает кросс-компилятор FPC i8086-msdos, потому что ни один дистрибутив
# его не поставляет (docs/PHASE0-FINDINGS.md, F-02), и приносит компоновщик
# Open Watcom, потому что FPC линкует этот таргет именно им (F-03).
#
# RTL собирается сразу под несколько моделей памяти: от модели зависит и предел
# размера кода, и совпадение SS с DS, а значит вся конструкция файберов.
# Выбор модели — предмет спайка S1, поэтому образ обязан давать сравнить.
#
#   docker build -f ops/toolchain/Dockerfile -t sonder/dos-toolchain:3.2.2 .
#
FROM debian:12-slim

ARG FPC_VERSION=3.2.2
ARG FPCBUILD_URL=https://downloads.freepascal.org/fpc/dist/3.2.2/source/fpcbuild-3.2.2.tar.gz
ARG OW_URL=https://github.com/open-watcom/open-watcom-v2/releases/download/Current-build/ow-snapshot.tar.xz

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    FPC_VERSION=${FPC_VERSION} \
    WATCOM=/opt/ow \
    PATH=/opt/ow/binl64:/usr/local/bin:/usr/bin:/bin

# --- 1. Базовые инструменты -------------------------------------------------
# fpc    — нативный ppcx64, бутстрап-компилятор той же версии
# nasm   — ассемблер таргета i8086-msdos
# dosbox — прогон DOS-бинарников: у DOSBox-X нет Linux-сборок (F-01)
RUN apt-get update && apt-get install -y --no-install-recommends \
        fpc \
        nasm \
        make \
        binutils \
        curl \
        ca-certificates \
        xz-utils \
        dosbox \
        python3 \
    && rm -rf /var/lib/apt/lists/*

# --- 2. Компоновщик Open Watcom --------------------------------------------
# Из 143 МБ архива нужны ровно три статических бинарника, суммарно ~4.3 МБ.
RUN --mount=type=cache,target=/dlcache,sharing=locked \
    set -eux; \
    if [ ! -f /dlcache/ow-snapshot.tar.xz ]; then \
        curl -fSL --retry 3 -o /dlcache/ow.part "${OW_URL}"; \
        mv /dlcache/ow.part /dlcache/ow-snapshot.tar.xz; \
    fi; \
    mkdir -p /opt/ow; \
    tar -xJf /dlcache/ow-snapshot.tar.xz -C /opt/ow --strip-components=1 \
        ./binl64/wlink ./binl64/wlib ./binl64/wasm; \
    chmod +x /opt/ow/binl64/*; \
    /opt/ow/binl64/wlink 2>&1 | head -1

# --- 3. Префиксованные имена инструментов ----------------------------------
# При кросс-сборке FPC зовёт инструменты как <os>-<tool> (F-04).
RUN set -eux; \
    ln -sf /usr/bin/nasm        /usr/local/bin/msdos-nasm; \
    ln -sf /opt/ow/binl64/wlink /usr/local/bin/msdos-wlink; \
    ln -sf /opt/ow/binl64/wlib  /usr/local/bin/msdos-wlib; \
    ln -sf /opt/ow/binl64/wasm  /usr/local/bin/msdos-wasm

# --- 4. Кросс-компилятор и RTL ---------------------------------------------
COPY ops/toolchain/build-fpc-cross.sh /usr/local/bin/build-fpc-cross
RUN --mount=type=cache,target=/dlcache,sharing=locked \
    set -eux; \
    chmod +x /usr/local/bin/build-fpc-cross; \
    if [ ! -f /dlcache/fpcbuild-${FPC_VERSION}.tar.gz ]; then \
        curl -fSL --retry 3 -o /dlcache/fpcbuild.part "${FPCBUILD_URL}"; \
        mv /dlcache/fpcbuild.part /dlcache/fpcbuild-${FPC_VERSION}.tar.gz; \
    fi; \
    mkdir -p /usr/src/fpcbuild; \
    tar -xzf /dlcache/fpcbuild-${FPC_VERSION}.tar.gz -C /usr/src/fpcbuild --strip-components=1; \
    build-fpc-cross

COPY ops/toolchain/fpc-dos /usr/local/bin/fpc-dos
RUN chmod +x /usr/local/bin/fpc-dos

# --- 5. Проверка образа -----------------------------------------------------
# Образ, не умеющий собрать hello world, бесполезен: ловим это на сборке,
# а не на первом использовании.
COPY ops/toolchain/smoke.pas /tmp/smoke/smoke.pas
ARG SMOKE_STRICT=1
RUN set -eu; \
    cd /tmp/smoke; \
    built=0; \
    for model in $(cat /opt/fpc/AVAILABLE_MODELS); do \
        if fpc-dos "$model" -osmoke_$model.exe smoke.pas > $model.log 2>&1; then \
            echo "  $model: OK, $(stat -c%s smoke_$model.exe) байт"; \
            built=$((built+1)); \
        else \
            echo "  $model: ПРОВАЛ"; \
            sed 's/^/      /' $model.log; \
        fi; \
    done; \
    echo "успешно собралось моделей: $built"; \
    if [ "$SMOKE_STRICT" = "1" ]; then test "$built" -gt 0; fi

WORKDIR /work
CMD ["bash"]
