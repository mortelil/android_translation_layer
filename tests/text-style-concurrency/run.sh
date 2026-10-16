#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:-$repo/build/text-style-concurrency}
mkdir -p "$out"
cc -Wall -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections -Wl,--gc-sections \
 -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
 "$repo/tests/text-style-concurrency/test.c" "$repo/src/api-impl-jni/graphics/android_graphics_Paint.c" \
 $(pkg-config --cflags --libs gtk4 pangocairo pangoft2 fontconfig freetype2) -lm -o "$out/test"
"$out/test"
