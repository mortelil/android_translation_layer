#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# APP-COMPAT case F controlled split family build. Run INSIDE the atl_dev
# Toolbox container (has javac/d8/aapt/clang/apksigner + the shared keystore):
#
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/app-compat/tests/split-probe/build.sh \
#        /home/morten/ATL/app-compat/tests/split-probe/build'
#
# Produces (all signed with the SAME key, so install-multiple accepts the set):
#   <out>/family/base.apk
#   <out>/family/split_config.x86_64.apk     (lib/x86_64/libsplitprobe.so)
#   <out>/family/split_config.xxhdpi.apk     (assets/xxhdpi_sentinel.txt, res/raw/density_raw)
#   <out>/family/split_config.en.apk         (assets/lang_sentinel.txt, res/values/strings.xml)
#   <out>/family/split_config.arm64_v8a.apk  (lib/arm64-v8a/libsplitprobe.so, negative)
#   <out>/family/other_pkg.apk               (package org.atl.tests.split2, versionCode 2)
#   <out>/family/splitset.apkm               (.apkm container of the valid members)
#   <out>/family/negative/{neg-arm64,neg-no-base,neg-mismatch,neg-base-only}/
#
# Deterministic: no timestamps are injected by this script (javac/aapt are
# deterministic for fixed inputs); the frozen sha256 is recorded by freeze.sh.
set -eu

if [ "$#" -ne 1 ]; then
	printf 'Usage: %s OUTPUT_DIR\n' "$0" >&2
	exit 2
fi

ANDROID_JAR=${ANDROID_JAR:-/home/morten/Android/Sdk/platforms/android-37.0/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-/home/morten/Android/Sdk/build-tools/37.0.0}
FRAMEWORK_RES=${FRAMEWORK_RES:-/home/morten/ATL/app-compat/build-mobile/res/framework-res/framework-res.apk}
KEYSTORE=${KEYSTORE:-/home/morten/ATL/font-tests/debug.keystore}
KS_ALIAS=${KS_ALIAS:-fontprobe}
JAVAC=${JAVAC:-javac}
D8="$BUILD_TOOLS/d8"
AAPT="$BUILD_TOOLS/aapt"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

for f in "$ANDROID_JAR" "$FRAMEWORK_RES" "$KEYSTORE"; do
	[ -f "$f" ] || { printf 'missing required file: %s\n' "$f" >&2; exit 2; }
done
for t in "$D8" "$AAPT" "$ZIPALIGN" "$APKSIGNER"; do
	[ -x "$t" ] || { printf 'missing required tool: %s\n' "$t" >&2; exit 2; }
done

src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=$(mkdir -p "$1" && cd "$1" && pwd)
work="$out/.work"
rm -rf "$work"
mkdir -p "$work/classes" "$work/dex" "$work/native/x86_64" "$work/native/arm64-v8a" "$out/family"

# ---------------------------------------------------------------- java + dex
"$JAVAC" -source 8 -target 8 -cp "$ANDROID_JAR" -d "$work/classes" \
	$(find "$src/src" -name '*.java')
(cd "$work/classes" && jar cf "$work/classes.jar" .)
"$D8" --min-api 24 --output "$work/dex" "$work/classes.jar"

# ---------------------------------------------------------------- native libs
clang --target=x86_64-linux-gnu -fuse-ld=lld -shared -fPIC -nostdlib -O2 \
	-Wl,-z,max-page-size=16384 -Wl,--build-id=none \
	-o "$work/native/x86_64/libsplitprobe.so" "$src/native/splitprobe.c"
clang --target=aarch64-linux-gnu -fuse-ld=lld -shared -fPIC -nostdlib -O2 \
	-Wl,-z,max-page-size=16384 -Wl,--build-id=none \
	-o "$work/native/arm64-v8a/libsplitprobe.so" "$src/native/splitprobe.c"

# stage native libs with their in-APK path for `aapt add`
mkdir -p "$work/stage-x86_64/lib/x86_64" "$work/stage-arm64/lib/arm64-v8a"
cp "$work/native/x86_64/libsplitprobe.so" "$work/stage-x86_64/lib/x86_64/"
cp "$work/native/arm64-v8a/libsplitprobe.so" "$work/stage-arm64/lib/arm64-v8a/"

# ------------------------------------------------------------------- packaging
# base
"$AAPT" package -f -M "$src/base/AndroidManifest.xml" -A "$src/base/assets" \
	-I "$FRAMEWORK_RES" -F "$work/base-unsigned.apk"
(cd "$work/dex" && "$AAPT" add "$work/base-unsigned.apk" classes.dex)
# other package (mismatch negative)
"$AAPT" package -f -M "$src/other-pkg/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" -F "$work/other-unsigned.apk"
(cd "$work/dex" && "$AAPT" add "$work/other-unsigned.apk" classes.dex)

# splits
"$AAPT" package -f -M "$src/splits/config.x86_64/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" -F "$work/split_config.x86_64-unsigned.apk"
(cd "$work/stage-x86_64" && "$AAPT" add "$work/split_config.x86_64-unsigned.apk" lib/x86_64/libsplitprobe.so)

"$AAPT" package -f -M "$src/splits/config.xxhdpi/AndroidManifest.xml" \
	-A "$src/splits/config.xxhdpi/assets" -S "$src/splits/config.xxhdpi/res" \
	-I "$FRAMEWORK_RES" -F "$work/split_config.xxhdpi-unsigned.apk"

"$AAPT" package -f -M "$src/splits/config.en/AndroidManifest.xml" \
	-A "$src/splits/config.en/assets" -S "$src/splits/config.en/res" \
	-I "$FRAMEWORK_RES" -F "$work/split_config.en-unsigned.apk"

"$AAPT" package -f -M "$src/splits/config.arm64_v8a/AndroidManifest.xml" \
	-I "$FRAMEWORK_RES" -F "$work/split_config.arm64_v8a-unsigned.apk"
(cd "$work/stage-arm64" && "$AAPT" add "$work/split_config.arm64_v8a-unsigned.apk" lib/arm64-v8a/libsplitprobe.so)

# ---------------------------------------------------------- zipalign + sign
sign() { # <unsigned> <final>
	"$ZIPALIGN" -f 4 "$1" "$1.aligned"
	"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
		--ks-pass pass:android --key-pass pass:android \
		--out "$2" "$1.aligned"
}

sign "$work/base-unsigned.apk"                "$out/family/base.apk"
sign "$work/other-unsigned.apk"               "$out/family/other_pkg.apk"
sign "$work/split_config.x86_64-unsigned.apk" "$out/family/split_config.x86_64.apk"
sign "$work/split_config.xxhdpi-unsigned.apk" "$out/family/split_config.xxhdpi.apk"
sign "$work/split_config.en-unsigned.apk"     "$out/family/split_config.en.apk"
sign "$work/split_config.arm64_v8a-unsigned.apk" "$out/family/split_config.arm64_v8a.apk"

# ------------------------------------------------------------------ .apkm
( cd "$out/family" && rm -f splitset.apkm && \
  zip -q -X splitset.apkm base.apk split_config.x86_64.apk \
      split_config.xxhdpi.apk split_config.en.apk )

# --------------------------------------------------------------- negative sets
neg="$out/family/negative"
rm -rf "$neg"
mkdir -p "$neg/neg-arm64" "$neg/neg-no-base" "$neg/neg-mismatch" "$neg/neg-base-only"
cp "$out/family/base.apk" "$neg/neg-arm64/"
cp "$out/family/split_config.arm64_v8a.apk" "$neg/neg-arm64/"
cp "$out/family/split_config.x86_64.apk" "$neg/neg-no-base/"
cp "$out/family/base.apk" "$neg/neg-mismatch/"
cp "$out/family/other_pkg.apk" "$neg/neg-mismatch/"
cp "$out/family/base.apk" "$neg/neg-base-only/"

printf 'built: %s/family\n' "$out"
