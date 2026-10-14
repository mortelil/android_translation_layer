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
# Compile java.* against the target runtime API. -source/-target alone select
# language/bytecode versions, not platform signatures: a newer host JDK can
# otherwise emit methods (e.g. covariant NIO buffer methods) absent at runtime.
"${JAVAC:-javac}" -source 8 -target 8 -Xlint:-options \
	-bootclasspath "$art_java/core-all_classes.jar" \
	-cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" "$source_dir"/*.java
dx --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$source_dir/AndroidManifest.xml" -A "$out/assets" -I "$build/res/framework-res/framework-res.apk" -F "$out/mobile-smoke.apk"
(cd "$out" && aapt add mobile-smoke.apk classes.dex)
