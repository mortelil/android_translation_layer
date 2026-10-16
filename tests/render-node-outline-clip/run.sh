#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec toolbox run --container atl_dev sh -lc "
	set -eu
	cd '$repo'
	mkdir -p build/render-node-outline-clip
	cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
	  -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
	  tests/render-node-outline-clip/test.c src/api-impl-jni/graphics/android_view_RenderNode.c \
	  \$(pkg-config --cflags --libs gtk4) -lm -o build/render-node-outline-clip/test
	GSK_RENDERER=cairo build/render-node-outline-clip/test
"
