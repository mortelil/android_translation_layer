#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside Alpine, on an X11 compositor providing presentation feedback.
# A bare Xvfb has no feedback: it MUST time out without claiming completion.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build=$(realpath "${1:?usage: run.sh BUILD_DIR RUNTIME_PREFIX OUTPUT_DIR}")
prefix=$(realpath "${2:?runtime prefix}")
mkdir -p "${3:?output directory}"
out=$(realpath "$3")
export LD_LIBRARY_PATH="$build:$prefix/lib:/usr/lib/art:/usr/lib/java/dex/art/natives${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
cc -g -Isrc/libandroid -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" \
  $(pkg-config --cflags gtk4) "$repo/tests/surface-control-cpu/presentation.c" \
  -I"$repo/src/libandroid" -L"$build" -L"$prefix/lib" -ltranslation_layer_main -landroid -ldl_bio \
  $(pkg-config --libs gtk4) -lX11 -o "$out/presentation"
GDK_BACKEND=x11 GSK_RENDERER=${GSK_RENDERER:-gl} timeout 15 "$out/presentation"
