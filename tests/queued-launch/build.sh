#!/bin/sh
set -eu
build=$(realpath "$1");mkdir -p "$2/classes";out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 -cp "${ANDROID_JAR:-$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar}" -d "$out/classes" "$src"/*.java
dx --min-sdk-version=21 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/queued-launch.apk"
(cd "$out" && aapt add queued-launch.apk classes.dex)
