#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# C-WEB probe APK build. Run INSIDE the atl_dev Toolbox container:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/app-compat/tests/web-probe/build.sh \
#        /home/morten/ATL/app-compat/tests/web-probe/build'
# Produces a zipaligned but UNSIGNED APK; sign it on the host with apksigner.
# JDK 8 javac is pinned when present (runbook section 8A: javac 21 breaks
# java.nio signatures against the ATL boot dex).
set -eu
if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-$HOME/ATL/app-compat/build-mobile/res/framework-res/framework-res.apk}
if [ -z "${JAVAC:-}" ] && [ -x /usr/lib/jvm/java-1.8-openjdk/bin/javac ]; then
	JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac
fi
: "${JAVAC:=javac}"
output=$(mkdir -p "$1" && cd "$1" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$output/classes"
# shellcheck disable=SC2046
"$JAVAC" -source 8 -target 8 \
	-cp "$ANDROID_JAR" \
	-d "$output/classes" \
	$(find "$source_dir/src" -name '*.java')
dx --min-sdk-version=24 --dex --output="$output/classes.dex" "$output/classes"
aapt package -f \
	-M "$source_dir/AndroidManifest.xml" \
	-A "$source_dir/assets" \
	-I "$FRAMEWORK_RES" \
	-F "$output/web-probe-unsigned.apk"
(cd "$output" && aapt add web-probe-unsigned.apk classes.dex)
zipalign -f 4 "$output/web-probe-unsigned.apk" "$output/web-probe-aligned.apk"
printf '%s\n' "$output/web-probe-aligned.apk"
