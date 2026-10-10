#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# ACTIVITY-LIFECYCLE / FRAGMENT-TAG probe v2 APK build. Run INSIDE the
# atl_dev Toolbox container:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/review-repairs/tests/lifecycle-probe/build.sh \
#        /home/morten/ATL/review-repairs/tests/lifecycle-probe/build'
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
# shellcheck disable=SC2046
"${JAVAC:-javac}" -source 8 -target 8 \
	-cp "$ANDROID_JAR" \
	-d "$output/classes" \
	$(find "$source_dir/src" -name '*.java')
dx --min-sdk-version=24 --dex --output="$output/classes.dex" "$output/classes"
aapt package -f \
	-M "$source_dir/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" \
	-F "$output/lifecycle-probe-unsigned.apk"
(cd "$output" && aapt add lifecycle-probe-unsigned.apk classes.dex)
zipalign -f 4 "$output/lifecycle-probe-unsigned.apk" "$output/lifecycle-probe-aligned.apk"
printf '%s\n' "$output/lifecycle-probe-aligned.apk"
