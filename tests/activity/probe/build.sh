#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Lane-C activity seam probe APK build (A05/A06/A07/A08/A09). Run INSIDE the
# atl_dev Toolbox container:
#   FRAMEWORK_RES=<lane>/build-mobile/res/framework-res/framework-res.apk \
#     sh build.sh <TARGET_SDK> <OUTPUT_DIR>
# The targetSdkVersion is substituted into the manifest so the same sources are
# built once per A09 target (27 = pre-P save-before-stop, 28/35 = save-after).
set -eu
if [ "$#" -ne 2 ]; then
	printf 'Usage: %s TARGET_SDK OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
target_sdk=$1
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
: "${FRAMEWORK_RES:?set FRAMEWORK_RES to the lane framework-res.apk}"
output=$(mkdir -p "$2" && cd "$2" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$output/classes"
sed "s/@TARGET_SDK@/$target_sdk/" "$source_dir/AndroidManifest.xml" > "$output/AndroidManifest.xml"
# shellcheck disable=SC2046
"${JAVAC:-javac}" -source 8 -target 8 \
	-cp "$ANDROID_JAR" \
	-d "$output/classes" \
	$(find "$source_dir/src" -name '*.java')
dx --min-sdk-version=24 --dex --output="$output/classes.dex" "$output/classes"
aapt package -f \
	-M "$output/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" \
	-F "$output/activity-probe-unsigned.apk"
(cd "$output" && aapt add activity-probe-unsigned.apk classes.dex)
zipalign -f 4 "$output/activity-probe-unsigned.apk" "$output/activity-probe-sdk${target_sdk}-aligned.apk"
printf '%s\n' "$output/activity-probe-sdk${target_sdk}-aligned.apk"
