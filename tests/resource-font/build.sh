#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside atl_dev. Native probe targets the container's architecture.
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 \
  -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" -d "$out/classes" "$src/TestActivity.java"
dx --dex --output="$out/classes.dex" "$out/classes"
cp -R "$src/res" "$out/"
cp "$src/../font-probe/assets/DejaVuSans.ttf" "$out/res/font/regular.ttf"
printf 'not a font' > "$out/res/font/broken.ttf"
aapt2 compile --dir "$out/res" -o "$out/resources.zip"
aapt2 link --manifest "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -o "$out/resource-font.apk" "$out/resources.zip"
(cd "$out" && aapt add resource-font.apk classes.dex)
if [ ! -f "$out/test.jks" ]; then
    keytool -genkeypair -keystore "$out/test.jks" -storepass regression -keypass regression -alias fixture -dname CN=ATL-Test -keyalg RSA -validity 30
fi
jarsigner -keystore "$out/test.jks" -storepass regression -keypass regression "$out/resource-font.apk" fixture
