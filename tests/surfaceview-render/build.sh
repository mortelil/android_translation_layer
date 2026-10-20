#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build in atl_dev: sh tests/surfaceview-render/build.sh build-mobile OUT_DIR
set -eu
if [ "$#" -ne 2 ]; then echo "Usage: $0 BUILD_DIR OUTPUT_DIR" >&2; exit 2; fi
build=$(cd "$1" && pwd)
out=$(mkdir -p "$2" && cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
mkdir -p "$out/classes"
javac -source 8 -target 8 -bootclasspath "$art_java/core-all_classes.jar" \
	-cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
[ "${ATL_PROBE_DEX_ONLY:-0}" = 1 ] && exit 0
aapt package -f -M "$src/AndroidManifest.xml" -S "$src/res" -I "${ANDROID_JAR:-$build/res/framework-res/framework-res.apk}" -F "$out/surfaceview-render.apk"
(cd "$out" && aapt add surfaceview-render.apk classes.dex)
printf 'Probe APK: %s\n' "$out/surfaceview-render.apk"
