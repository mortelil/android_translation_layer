#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Build the MediaExtractor unit probe APK. Run inside Alpine (Toolbox atl_dev):
#   sh tests/media-extractor/build.sh <ATL_BUILD_DIR> <OUTPUT_DIR>
set -eu
test "$#" -eq 2 || { echo "Usage: $0 ATL_BUILD_DIR OUTPUT_DIR" >&2; exit 2; }
atl_build=$(cd "$1" && pwd)
mkdir -p "$2/classes"
out=$(cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-javac}" -source 8 -target 8 -Xlint:-options \
	-bootclasspath "$art_java/core-all_classes.jar" \
	-cp "$atl_build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" "$src"/ExtractorProbe.java
dx --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" \
	-I "$atl_build/res/framework-res/framework-res.apk" \
	-F "$out/media-extractor-probe.apk"
(cd "$out" && aapt add media-extractor-probe.apk classes.dex)
printf '%s\n' "$out/media-extractor-probe.apk"
