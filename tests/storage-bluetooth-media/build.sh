#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test "$#" -eq 2 || { echo "Usage: $0 ATL_BUILD_DIR OUTPUT_DIR" >&2; exit 2; }
atl_build=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
mkdir -p "$out/classes"
"${JAVAC:-javac}" -source 8 -target 8 -bootclasspath "$art_java/core-all_classes.jar" -cp "${ANDROID_JAR:?Set the Android SDK android.jar path}" \
	-d "$out/classes" "$src"/*.java
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$atl_build/res/framework-res/framework-res.apk" \
	-F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex)
printf '%s\n' "$out/probe.apk"
