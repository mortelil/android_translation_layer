#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# FONT-006 (G-A glyph) probe APK build. Run INSIDE the atl_dev Toolbox:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/review-repairs/tests/font-probe-glyph/build.sh \
#        /home/morten/ATL/review-repairs/tests/font-probe-glyph/build'
# Produces a zipaligned but UNSIGNED APK; sign it on the host with apksigner.
set -eu
if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-$HOME/ATL/review-repairs/build-mobile/res/framework-res/framework-res.apk}
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
	-A "$source_dir/assets" \
	-I "$FRAMEWORK_RES" \
	-F "$output/font-glyph-probe-unsigned.apk"
(cd "$output" && aapt add font-glyph-probe-unsigned.apk classes.dex)
zipalign -f 4 "$output/font-glyph-probe-unsigned.apk" "$output/font-glyph-probe-aligned.apk"
printf '%s\n' "$output/font-glyph-probe-aligned.apk"
