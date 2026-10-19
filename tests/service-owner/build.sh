#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here"/*.java
dx --dex --output="$out/classes.dex" "$out/classes"
for app in owner caller; do
 mkdir -p "$out/$app"
 cp "$here/$app.xml" "$out/$app/AndroidManifest.xml"
 aapt package -f -M "$out/$app/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/$app.apk"
 (cd "$out" && aapt add "$app.apk" classes.dex)
done
