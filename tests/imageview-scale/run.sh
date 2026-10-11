#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
build=$(realpath "${1:?build}")
prefix=$(realpath "${2:?prefix}")
mkdir -p "${3:?output}"
out=$(realpath "$3")
export LD_LIBRARY_PATH="$build:$prefix/lib:/usr/lib/art:/usr/lib/java/dex/art/natives${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
cc -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" $(pkg-config --cflags gtk4) tests/imageview-scale/pixels.c -L"$build" -L"$prefix/lib" -ltranslation_layer_main -landroid -ldl_bio $(pkg-config --libs gtk4) -o "$out/pixels"
GSK_RENDERER=cairo timeout 10 "$out/pixels"
