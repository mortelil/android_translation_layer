#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test "$#" -eq 2 || { echo 'Usage: build.sh ANDROID_JAR OUTPUT_DIR' >&2; exit 2; }
android_jar=$(realpath "$1")
mkdir -p "$2/classes"
out=$(cd "$2" && pwd)
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"${JAVAC:-javac}" -source 8 -target 8 -cp "$android_jar" -d "$out/classes" "$src"/*.java
dx --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$android_jar" -F "$out/probe-unsigned.apk"
(cd "$out" && aapt add probe-unsigned.apk classes.dex)
zipalign -f 4 "$out/probe-unsigned.apk" "$out/probe.apk"
