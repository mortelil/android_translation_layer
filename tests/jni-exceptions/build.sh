#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Build the JNI-EXC-001 probe APKs (A/B/C). Run INSIDE the atl_dev Toolbox
# container:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/jni-exceptions/tests/jni-exceptions/build.sh \
#        /home/morten/ATL/jni-exceptions/tests/jni-exceptions/build'
#
# Produces three zipaligned but UNSIGNED APKs; sign them on the host with
# apksigner (see README.md). Nothing here touches src/**.
set -eu
if [ "$#" -ne 1 ]; then
    printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
    exit 2
fi

ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-/home/morten/ATL/jni-exceptions/build-mobile/res/framework-res/framework-res.apk}

output=$(mkdir -p "$1" && cd "$1" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

[ -f "$ANDROID_JAR" ] || { echo "build.sh: missing android.jar: $ANDROID_JAR" >&2; exit 2; }
[ -f "$FRAMEWORK_RES" ] || { echo "build.sh: missing framework-res.apk: $FRAMEWORK_RES" >&2; exit 2; }

build_one() {
    variant=$1
    activity=$2
    odir="$output/$variant"
    mkdir -p "$odir/classes"
    # shellcheck disable=SC2046
    "${JAVAC:-javac}" -source 8 -target 8 \
        -cp "$ANDROID_JAR" \
        -d "$odir/classes" \
        "$source_dir/src/org/atl/tests/jniexc/ProbeJson.java" \
        "$source_dir/src/org/atl/tests/jniexc/$variant/$activity.java"
    dx --min-sdk-version=24 --dex --output="$odir/classes.dex" "$odir/classes"
    # This aapt build requires the manifest basename to be AndroidManifest.xml.
    cp -f "$source_dir/AndroidManifest-$variant.xml" "$odir/AndroidManifest.xml"
    aapt package -f \
        -M "$odir/AndroidManifest.xml" \
        -I "$FRAMEWORK_RES" \
        -F "$odir/jniexc-001-$variant-unsigned.apk"
    (cd "$odir" && aapt add "jniexc-001-$variant-unsigned.apk" classes.dex)
    zipalign -f 4 "$odir/jniexc-001-$variant-unsigned.apk" "$odir/jniexc-001-$variant-aligned.apk"
    printf '%s\n' "$odir/jniexc-001-$variant-aligned.apk"
}

build_one a ScenarioAActivity
build_one b ScenarioBActivity
build_one c ScenarioCActivity
