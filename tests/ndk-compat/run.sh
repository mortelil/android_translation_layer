#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
out=$(realpath "$1")
repo=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
# Link the tested production translation units, discarding unrelated entrypoints
# which depend on ATL's running Java VM/window. No substitute implementations.
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
    $(pkg-config --cflags gtk4) \
    -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" \
    -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" \
    "$repo/tests/ndk-compat/contracts.c" \
    "$repo/src/libandroid/native_window.c" "$repo/src/libandroid/media.c" \
    "$repo/src/libandroid/egl.c" "$repo/src/libandroid/multinetwork.c" \
    "$repo/src/libandroid/malloc_tunables.c" -o "$out"
"$out"
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
    $(pkg-config --cflags gtk4) -I"$repo" \
    -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" \
    -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" \
    "$repo/tests/ndk-compat/permissions.c" "$repo/src/api-impl-jni/util.c" -o "$out-permissions"
"$out-permissions"
