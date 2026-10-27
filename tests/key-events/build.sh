#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
"${JAVAC:-javac}" -source 8 -target 8 -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$here/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex)
