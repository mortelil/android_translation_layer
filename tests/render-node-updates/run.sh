#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
mkdir -p "$1"
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
 -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
 "$repo/tests/render-node-updates/test.c" $(pkg-config --cflags --libs gtk4) -lm -o "$1/native"
"$1/native"
