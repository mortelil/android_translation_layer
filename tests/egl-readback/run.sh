#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:?Supply an output directory};mkdir -p "$out"
cc -I"$repo/src/libandroid" "$repo/tests/egl-readback/test.c" "$repo/src/libandroid/egl_readback.c" $(pkg-config --cflags --libs glib-2.0 egl glesv2) -o "$out/readback-test"
EGL_PLATFORM=surfaceless timeout 20 "$out/readback-test"
cc -rdynamic "$repo/tests/egl-readback/dispatch.c" "$repo/src/libandroid/egl_dispatch.c" $(pkg-config --cflags --libs egl) -ldl -o "$out/dispatch-test"
"$out/dispatch-test"
