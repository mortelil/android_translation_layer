#!/bin/sh
set -eu
build=$1; out=$2
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$out/classes"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$src/Probe.java"
dx --dex --min-sdk-version=26 --output="$out/classes.dex" "$out/classes"
for variant in owner probe; do
 cp "$src/$variant.xml" "$out/AndroidManifest.xml"
 aapt package -f -M "$out/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/$variant.apk"
 (cd "$out" && aapt add "$variant.apk" classes.dex)
done
