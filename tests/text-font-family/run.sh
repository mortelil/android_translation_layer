#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:-$repo/build/text-font-family}
mkdir -p "$out"
cc -Wall -Wextra -Wno-unused-parameter -o "$out/test" "$repo/tests/text-font-family/test.c" \
 $(pkg-config --cflags --libs pangocairo pangoft2 fontconfig) -lm
"$out/test"
