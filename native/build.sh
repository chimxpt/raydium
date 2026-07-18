#!/usr/bin/env bash
# Сборка прослойки к NGX (DLSS Ray Reconstruction).
#
# Что делает:
#   1. качает NVIDIA DLSS SDK (заголовки + статическая libnvsdk_ngx.a + сама модель RR)
#   2. качает заголовки Vulkan (в системе их может не быть — пакет vulkan-headers не обязателен)
#   3. собирает libmcrt_ngx.so и кладёт её в ресурсы мода (поедет внутри jar)
#   4. кладёт модель RR (libnvidia-ngx-dlssd.so, 40 МБ) в папку игры — в jar её тащить незачем
#
# Запуск:  bash native/build.sh
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
DEPS="$HERE/deps"
MOD="$HERE/.."                     # скрипт лежит внутри репозитория мода
# Куда положить модель Ray Reconstruction (40 МБ). Не задано -> шаг пропускается.
# Пример: MC_DIR=~/.minecraft bash native/build.sh
GAMEDIR="${MC_DIR:-}"

mkdir -p "$DEPS"

# --- 1. DLSS SDK ---
if [ ! -d "$DEPS/DLSS" ]; then
    echo ">> качаю DLSS SDK..."
    git clone --depth 1 -q https://github.com/NVIDIA/DLSS.git "$DEPS/DLSS"
fi

# --- 2. заголовки Vulkan ---
if [ ! -d "$DEPS/Vulkan-Headers" ]; then
    echo ">> качаю заголовки Vulkan..."
    git clone --depth 1 -q https://github.com/KhronosGroup/Vulkan-Headers.git "$DEPS/Vulkan-Headers"
fi

# --- 3. JDK ---
JAVA_INC="${JAVA_HOME:-/usr/lib/jvm/default}/include"
[ -f "$JAVA_INC/jni.h" ] || { echo "!! не нашёл jni.h в $JAVA_INC (задай JAVA_HOME)"; exit 1; }

# --- 4. сборка ---
OUT="$MOD/src/main/resources/natives"
mkdir -p "$OUT"
echo ">> собираю libmcrt_ngx.so..."
g++ -O2 -fPIC -shared -std=c++17 \
    -I"$JAVA_INC" -I"$JAVA_INC/linux" \
    -I"$DEPS/Vulkan-Headers/include" \
    -I"$DEPS/DLSS/include" \
    "$HERE/mcrt_ngx.cpp" \
    "$DEPS/DLSS/lib/Linux_x86_64/libnvsdk_ngx.a" \
    -ldl -lpthread \
    -o "$OUT/libmcrt_ngx.so"
echo ">> готово: $OUT/libmcrt_ngx.so"

# --- 5. модель Ray Reconstruction в папку игры ---
if [ -n "$GAMEDIR" ] && [ -d "$GAMEDIR" ]; then
    mkdir -p "$GAMEDIR/dlss"
    # ⚠️ ТОЛЬКО файл С ВЕРСИЕЙ: NGX сам разбирает версию из имени и выбирает свежайшую.
    # Копия без версии тут ничего не чинит (проверено) и только засоряет список сниппетов.
    cp -u "$DEPS/DLSS/lib/Linux_x86_64/rel/libnvidia-ngx-dlssd.so."* "$GAMEDIR/dlss/"
    echo ">> модель RR: $GAMEDIR/dlss/"
    ls -la "$GAMEDIR/dlss/" | awk '{print $5, $9}'
fi
