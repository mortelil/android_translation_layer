#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
mkdir -p "$out"
cc -Wall -Wextra "$repo/tests/popup-placement/test.c" -o "$out/geometry"
"$out/geometry"
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
  "$repo/tests/popup-placement/native.c" $(pkg-config --cflags --libs gtk4) -o "$out/native"
GSK_RENDERER=cairo xvfb-run -a "$out/native"
