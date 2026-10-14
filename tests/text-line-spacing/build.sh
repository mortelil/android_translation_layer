#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
/usr/lib/jvm/java-1.8-openjdk/bin/javac -source 8 -target 8 -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
 -d "$out/classes" "$repo/tests/mobile-smoke/TestLineSpacing.java" "$repo/tests/text-line-spacing/Probe.java"
dx --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$repo/tests/text-line-spacing/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex)
