#!/bin/sh
set -eu
build=$(realpath "$1");mkdir -p "$2/classes";out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 -cp "${ANDROID_JAR:-$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar}" -d "$out/classes" "$src"/*.java
jar cf "$out/classes.jar" -C "$out/classes" .
java -cp "${R8_JAR:?Set R8_JAR to a D8/R8 jar}" com.android.tools.r8.D8 --min-api 21 --lib "$art_java/core-all_classes.jar" --lib "$build/src/api-impl/hax.jar" --output "$out" "$out/classes.jar"
[ "${CLASSES_ONLY:-0}" != 1 ] || exit 0
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/fragment-lifecycle.apk"
(cd "$out" && aapt add fragment-lifecycle.apk classes.dex)
