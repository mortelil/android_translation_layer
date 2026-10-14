#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build the view-capabilities probe APK. Run INSIDE the atl_dev Toolbox container,
# where the host Android SDK is mounted at the same path:
#
#   toolbox run --container atl_dev sh -lc \
#     "sh tests/view-capabilities/build.sh <REPO>/build-mobile <OUT_DIR>"
#
# $1 = the checkout's build-mobile (provides src/api-impl/hax.jar).
# Produces $2/view-capabilities.apk (unsigned; sign separately for Android installation).
set -eu

if [ "$#" -ne 2 ]; then
	printf 'Usage: %s BUILD_DIR OUTPUT_DIR\n' "$0" >&2
	exit 2
fi

build=$(cd "$1" && pwd)
out=$(mkdir -p "$2" && cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
mkdir -p "$out/classes"
"${JAVAC:-javac}" -source 8 -target 8 \
    -bootclasspath "$art_java/core-all_classes.jar" -cp "$build/src/api-impl/hax.jar" \
    -d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/view-capabilities.apk"
(cd "$out" && aapt add view-capabilities.apk classes.dex)
printf 'Unsigned probe: %s\n' "$out/view-capabilities.apk"
