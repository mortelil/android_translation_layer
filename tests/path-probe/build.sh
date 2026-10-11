#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# G-PATH path-probe APK build. Run INSIDE the atl_dev Toolbox container with the
# JDK-8 javac (see tooling.md 8A):
#   toolbox run --container atl_dev sh -lc \
#     'JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
#        /home/morten/ATL/app-compat/tests/path-probe/build.sh /abs/out'
# Produces a zipaligned but UNSIGNED APK; sign it on the host with apksigner.
set -eu
if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-/home/morten/ATL/android_translation_layer/build-mobile/res/framework-res/framework-res.apk}
output=$(mkdir -p "$1" && cd "$1" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$output/classes"
"${JAVAC:-javac}" -source 8 -target 8 \
	-cp "$ANDROID_JAR" \
	-d "$output/classes" \
	"$source_dir/src/org/atl/tests/path/ProbeActivity.java"
dx --dex --output="$output/classes.dex" "$output/classes"
aapt package -f \
	-M "$source_dir/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" \
	-F "$output/path-probe-unsigned.apk"
(cd "$output" && aapt add path-probe-unsigned.apk classes.dex)
zipalign -f 4 "$output/path-probe-unsigned.apk" "$output/path-probe-aligned.apk"
printf '%s\n' "$output/path-probe-aligned.apk"
