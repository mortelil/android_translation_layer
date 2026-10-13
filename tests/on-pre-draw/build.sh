#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Build the on-pre-draw probe APK. Run INSIDE the atl_dev Toolbox container,
# where the host Android SDK is mounted at the same path:
#
#   toolbox run --container atl_dev sh -lc \
#     "sh tests/on-pre-draw/build.sh <REPO>/build-mobile <OUT_DIR>"
#
# $1 = the checkout's build-mobile (provides src/api-impl/hax.jar).
# Produces $2/on-pre-draw.apk (zipaligned + signed with the shared probe key).
set -eu

if [ "$#" -ne 2 ]; then
	printf 'Usage: %s BUILD_DIR OUTPUT_DIR\n' "$0" >&2
	exit 2
fi

build=$(cd "$1" && pwd)
out=$(mkdir -p "$2" && cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-/home/morten/Android/Sdk/build-tools/37.0.0}
KEYSTORE=${KEYSTORE:-/home/morten/ATL/font-tests/debug.keystore}
KS_ALIAS=${KS_ALIAS:-fontprobe}

mkdir -p "$out/classes"
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 \
	-cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"

"$BUILD_TOOLS/aapt" package -f -M "$src/AndroidManifest.xml" -I "$ANDROID_JAR" \
	-F "$out/on-pre-draw-unsigned.apk"
(cd "$out" && "$BUILD_TOOLS/aapt" add "$out/on-pre-draw-unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$out/on-pre-draw-unsigned.apk" "$out/on-pre-draw-aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
	--ks-pass pass:android --key-pass pass:android \
	--out "$out/on-pre-draw.apk" "$out/on-pre-draw-aligned.apk"

printf 'built: %s\n' "$out/on-pre-draw.apk"
