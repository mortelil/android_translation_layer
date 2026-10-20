#!/bin/sh
set -eu
build=$(realpath "$1"); mkdir -p "$2/classes"; out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
javac -source 8 -target 8 -bootclasspath "$art_java/core-all_classes.jar" -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$src/ExplodingApplication.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex)
if [ ! -f "$out/test.jks" ]; then
    keytool -genkeypair -keystore "$out/test.jks" -storepass regression -keypass regression -alias fixture -dname CN=ATL-Test -keyalg RSA -validity 30
fi
jarsigner -keystore "$out/test.jks" -storepass regression -keypass regression "$out/probe.apk" fixture
