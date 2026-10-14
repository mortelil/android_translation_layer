#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Builds the shortcut-manager probe APK. Run INSIDE the atl_dev container,
# against this checkout's own build (see README.md for the lane wrapper):
#
#   sh tests/shortcut-manager/build.sh BUILD_DIR OUTPUT_DIR
#
#   BUILD_DIR   this lane's build directory (contains src/api-impl/hax.jar and
#               res/framework-res/framework-res.apk)
#   OUTPUT_DIR  created/reused; receives classes/, classes.dex, the unsigned
#               APK and the zipaligned APK
#
# The signature is produced on the host from the lane key (README.md); nothing
# here installs or rebuilds shared runtime dependencies.
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 \
	-cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" \
	-I "$build/res/framework-res/framework-res.apk" -F "$out/shortcut-manager-unsigned.apk"
(cd "$out" && aapt add shortcut-manager-unsigned.apk classes.dex)
zipalign -f 4 "$out/shortcut-manager-unsigned.apk" "$out/shortcut-manager.apk"
