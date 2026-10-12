#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -encoding UTF-8 -source 8 -target 8 \
  -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" -d "$out/classes" "$source_dir/TestActivity.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$source_dir/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/web-cookies.apk"
(cd "$out" && aapt add web-cookies.apk classes.dex)
