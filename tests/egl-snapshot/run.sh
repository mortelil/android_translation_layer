#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=${1:?Supply output directory};mkdir -p "$out"
cc -Wall -Wextra -Werror -I"$repo/src/libandroid" "$repo/tests/egl-snapshot/test.c" "$repo/src/libandroid/egl_snapshot.c" "$repo/src/libandroid/egl_readback.c" $(pkg-config --cflags --libs gtk4 egl glesv2) -Wl,--wrap=gdk_dmabuf_texture_builder_build -o "$out/snapshot-test"
timeout 45 "$out/snapshot-test"
