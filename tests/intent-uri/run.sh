#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside atl_dev; ATL_BUILD_DIR must identify the candidate build.
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
out=${1:-$(mktemp -d)}
mkdir -p "$out/classes"
if test "$#" -eq 0; then trap 'rm -rf "$out"' EXIT; fi
hax="$atl_build/src/api-impl/hax.jar"
sha256sum "$hax" "$atl_build/api-impl.jar"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$hax" -d "$out/classes" "$atl_repo/tests/intent-uri/TestIntentUri.java"
dx --dex --min-sdk-version=26 --output="$out/test.jar" "$out/classes"
ulimit -c 0
timeout 30 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/test.jar:${ATL_TEST_RUNTIME_JAR:-$atl_build/api-impl.jar}" TestIntentUri
