#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside atl_dev; ATL_BUILD_DIR must identify the candidate build.
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
hax="$atl_build/src/api-impl/hax.jar"
sha256sum "$hax" "$atl_build/api-impl.jar"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$hax" -d "$out" "$atl_repo/tests/compat-contracts/TestContracts.java"
dx --dex --min-sdk-version=26 --output="$out/test.jar" "$out"
ulimit -c 0
for row in ${ATL_TEST_ROWS:-extras input wifi}; do
    timeout 30 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/test.jar:${ATL_TEST_RUNTIME_JAR:-$atl_build/api-impl.jar}" TestContracts "$row"
done
