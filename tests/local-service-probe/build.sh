#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# LOCAL-SERVICES probe v2 (SVC-002/003/004) APK build. Run INSIDE the atl_dev
# Toolbox container (same toolchain as the lifecycle/font probes):
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/local-services/tests/local-service-probe/build.sh \
#        /home/morten/ATL/local-service-tests/build'
# Produces a zipaligned but UNSIGNED APK; sign it on the HOST with apksigner.
set -eu
if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-/home/morten/ATL/local-services/build-mobile/res/framework-res/framework-res.apk}
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
	-F "$output/svc-probe-v2-unsigned.apk"
(cd "$output" && aapt add svc-probe-v2-unsigned.apk classes.dex)
zipalign -f 4 "$output/svc-probe-v2-unsigned.apk" "$output/svc-probe-v2-aligned.apk"
printf '%s\n' "$output/svc-probe-v2-aligned.apk"
