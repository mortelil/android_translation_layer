#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
if [ "$#" -ne 2 ]; then
	printf 'Usage: %s ATL_BUILD_DIR OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
atl_build=$(cd "$1" && pwd)
mkdir -p "$2"
output=$(cd "$2" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java_dir=$(pkg-config --variable=libdir art-standalone)/java
mkdir -p "$output/classes"
"${JAVAC:-javac}" -source 8 -target 8 \
	-cp "$atl_build/src/api-impl/hax.jar:$art_java_dir/core-junit_classes.jar" \
	-d "$output/classes" "$source_dir/MatrixCopyTest.java"
dx --dex --output="$output/classes.dex" "$output/classes"
aapt package -f -M "$source_dir/AndroidManifest.xml" \
	-I "$atl_build/res/framework-res/framework-res.apk" -F "$output/matrix-copy-tests.apk"
(cd "$output" && aapt add matrix-copy-tests.apk classes.dex)
printf '%s\n' "$output/matrix-copy-tests.apk"
