#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Build the lane-D process probe APK against a finished ATL build.
# Reused (attribution): PR #31 baa9b489 tests/remote-services/build.sh
# (javac 8 against the built hax.jar + core-all, dx, aapt).
# Usage (inside the ATL container, e.g. toolbox run --container atl_dev):
#   build.sh <atl-build-dir> <out-dir>
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -encoding UTF-8 -source 8 -target 8 \
  -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" -d "$out/classes" \
  "$source_dir/ProbeState.java" "$source_dir/ProbeApp.java" "$source_dir/ProbeProvider.java" \
  "$source_dir/MainProvider.java" "$source_dir/WorkerProvider.java" "$source_dir/ProbeService.java" \
  "$source_dir/SvcWorker.java" "$source_dir/SvcWorker2.java" "$source_dir/SvcSecond.java" "$source_dir/SvcStall.java" "$source_dir/SvcIsolated.java" "$source_dir/SvcCrash.java" "$source_dir/SvcNullBind.java" \
  "$source_dir/TestActivity.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$source_dir/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/procprobe.apk"
(cd "$out" && aapt add procprobe.apk classes.dex)
echo "built $out/procprobe.apk"
