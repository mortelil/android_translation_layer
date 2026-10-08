#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
mkdir -p "$repo/build/egl-lifecycle"
cc "$repo/tests/egl-lifecycle/test.c" "$repo/src/libandroid/egl_lifecycle.c" $(pkg-config --cflags --libs egl glib-2.0) -o "$repo/build/egl-lifecycle/test"
"$repo/build/egl-lifecycle/test"
