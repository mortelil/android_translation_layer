#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir "$out/classes"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$atl_build/src/api-impl/hax.jar" -d "$out/classes" "$atl_repo/tests/native-libs/RuntimeGeneration.java"
dx --dex --output="$out/test.jar" "$out/classes"
ulimit -c 0
sha256sum "$atl_build/api-impl.jar"
timeout 45 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/test.jar:$atl_build/api-impl.jar" RuntimeGeneration "$out/data"
