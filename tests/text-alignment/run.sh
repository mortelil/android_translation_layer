#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:-$repo/build/text-alignment}
mkdir -p "$out"
cc -Wall -Wextra -Wno-unused-parameter -o "$out/test" "$repo/tests/text-alignment/test.c" \
 "$repo/src/api-impl-jni/text/AndroidLineGeometry.c" "$repo/src/api-impl-jni/widgets/AndroidLineBox.c" \
 $(pkg-config --cflags --libs gtk4 pangocairo) -lm
GSK_RENDERER=cairo xvfb-run -a "$out/test"
