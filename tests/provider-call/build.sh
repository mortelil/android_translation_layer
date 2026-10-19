#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
build=$(realpath "$1"); mkdir -p "$2"; out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art=$(pkg-config --variable=libdir art-standalone)/java
for variant in owner caller; do
 mkdir -p "$out/$variant/classes"
 if [ "$variant" = owner ]; then
  # Every provider class goes into the owner APK only: the caller must not be
  # able to load the owner's classes (tests/provider-call/README.md).
  set -- "$src"/*Owner.java
 else
  set -- "$src/Probe.java"
 fi
 /usr/lib/jvm/java-1.8-openjdk/bin/javac -source 8 -target 8 -cp "$build/src/api-impl/hax.jar:$art/core-all_classes.jar" -d "$out/$variant/classes" "$@"
 dx --dex --output="$out/$variant/classes.dex" "$out/$variant/classes"
 cp "$src/$variant.xml" "$out/$variant/AndroidManifest.xml"
 aapt package -f -M "$out/$variant/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/$variant.apk"
 (cd "$out/$variant" && aapt add "../$variant.apk" classes.dex)
done
