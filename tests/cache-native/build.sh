#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside atl_dev. Native probe targets the container's architecture.
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes" "$2/assets"
out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 \
  -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" -d "$out/classes" "$src/TestActivity.java"
dx --dex --output="$out/classes.dex" "$out/classes"
cc -shared -fPIC -nostdlib -o "$out/assets/libcacheprobe.so" "$src/probe.c"
aapt package -f -M "$src/AndroidManifest.xml" -A "$out/assets" -I "$build/res/framework-res/framework-res.apk" -F "$out/cache-native.apk"
(cd "$out" && aapt add cache-native.apk classes.dex)
