#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1; mkdir -p "$out"
cc -ffunction-sections -fdata-sections -Wl,--gc-sections \
 -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include" -I"${JAVA_HOME:-/usr/lib/jvm/default-jvm}/include/linux" \
 "$repo/tests/egl-headless/test.c" "$repo/src/libandroid/egl.c" "$repo/src/libandroid/egl_lifecycle.c" \
 $(pkg-config --cflags --libs gtk4 egl glesv2 wayland-egl wayland-client) -o "$out/test"
G_DEBUG=fatal-criticals EGL_PLATFORM=surfaceless timeout 15 "$out/test"
G_DEBUG=fatal-criticals EGL_PLATFORM=x11 xvfb-run -a timeout 15 "$out/test"
G_DEBUG=fatal-criticals GDK_BACKEND=x11 EGL_PLATFORM=x11 xvfb-run -a timeout 15 "$out/test" gdk
cc -fsanitize=address "$repo/tests/egl-headless/attributes.c" $(pkg-config --cflags --libs egl glib-2.0) -o "$out/attributes"
"$out/attributes"
