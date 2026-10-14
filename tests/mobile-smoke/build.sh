#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test "$#" -eq 2 || { echo 'Usage: build.sh BUILD_DIR OUTPUT_DIR' >&2; exit 2; }
build=$(cd "$1" && pwd)
mkdir -p "$2/classes"
out=$(cd "$2" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 "$source_dir/font-fixtures.py" "$out/assets/fonts"
art_java=$(pkg-config --variable=libdir art-standalone)/java
# Compile against the runtime's own core API, never the host JDK platform.
# android.jar (and therefore every real app) declares the Java-8 java.nio
# signatures, e.g. `Buffer position(int)`.  A modern host javac without
# -bootclasspath silently compiles against the Java 9+ platform instead, whose
# covariant `ByteBuffer position(int)` the runtime does not have; the resulting
# test would then fail with NoSuchMethodError that no app can ever hit.
"${JAVAC:-javac}" -source 8 -target 8 -Xlint:-options \
	-bootclasspath "$art_java/core-all_classes.jar" \
	-cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" "$source_dir"/*.java
dx --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$source_dir/AndroidManifest.xml" -A "$out/assets" -I "$build/res/framework-res/framework-res.apk" -F "$out/mobile-smoke.apk"
(cd "$out" && aapt add mobile-smoke.apk classes.dex)