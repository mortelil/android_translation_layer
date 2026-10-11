#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# D-SDK probe APK build. Run INSIDE the atl_dev Toolbox container:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/app-compat/tests/sdk-probe/build.sh \
#        /home/morten/ATL/app-compat/tests/sdk-probe/build'
#
# Produces TWO zipaligned but UNSIGNED APKs (same code/resources) that pin BOTH
# parts of the corrected SDK profile, plus the getSerial Q boundary:
#   sdk-dprobe-m21-t28-aligned.apk  minSdk 21, targetSdk 28
#       -> default must be max(21,21) = 21 (RELEASE "5.0"); pre-Q getSerial
#          semantics (READ_PHONE_STATE -> "unknown")
#   sdk-dprobe-m28-t30-aligned.apk  minSdk 28, targetSdk 30
#       -> default must be max(28,21) = 28 (RELEASE "9"); >=Q getSerial semantics
#          (SecurityException)
# The APK's declared minSdk is the input to the corrected profile, so the two
# APKs independently pin "floor at 21" vs "floored up by the app's minSdk".
# Sign them on the host with apksigner (see CONTRACT.md).
#
# NOTE: the Alpine `aapt` (v0.2-android-16.0.0_r2) aborts ("Vector itemLocation")
# on ANY `aapt package -S` resource compile in this container, so resource
# compilation uses `aapt2` (present at /usr/bin/aapt2 and verified working).
set -eu
if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
FRAMEWORK_RES=${FRAMEWORK_RES:-/home/morten/ATL/app-compat/build-mobile/res/framework-res/framework-res.apk}
JAVAC=${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}
output=$(mkdir -p "$1" && cd "$1" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

build_one() {
	min_sdk=$1
	target_sdk=$2
	tag=$3
	vdir="$output/$tag"
	rm -rf "$vdir"
	mkdir -p "$vdir/gen" "$vdir/classes"
	# Compile + link version-qualified resources (aapt2, see note above).
	aapt2 compile --dir "$source_dir/res" -o "$vdir/res.zip"
	aapt2 link -o "$vdir/sdk-dprobe-$tag-unsigned.apk" \
		--manifest "$source_dir/AndroidManifest.xml" \
		--java "$vdir/gen" \
		-I "$FRAMEWORK_RES" \
		--min-sdk-version "$min_sdk" --target-sdk-version "$target_sdk" \
		"$vdir/res.zip"
	# Compile probe + generated R.java (JDK 8 javac: see tooling.md 8A).
	# shellcheck disable=SC2046
	"$JAVAC" -source 8 -target 8 -cp "$ANDROID_JAR" -d "$vdir/classes" \
		$(find "$source_dir/src" "$vdir/gen" -name '*.java')
	dx --min-sdk-version="$min_sdk" --dex --output="$vdir/classes.dex" "$vdir/classes"
	(cd "$vdir" && aapt add "sdk-dprobe-$tag-unsigned.apk" classes.dex)
	zipalign -f 4 "$vdir/sdk-dprobe-$tag-unsigned.apk" "$vdir/sdk-dprobe-$tag-aligned.apk"
	printf '%s\n' "$vdir/sdk-dprobe-$tag-aligned.apk"
}

build_one 21 28 m21-t28
build_one 28 30 m28-t30