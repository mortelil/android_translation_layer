#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out="$repo/build/text-widget-style"
mkdir -p "$out"
cd "$repo"
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
  -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
  "$repo/tests/text-widget-style/test.c" "$repo/src/api-impl-jni/widgets/android_widget_TextView.c" \
  $(pkg-config --cflags --libs gtk4) -o "$out/test"
GSK_RENDERER=cairo "$out/test"
