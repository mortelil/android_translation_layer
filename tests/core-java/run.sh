#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir "$out/classes"
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -d "$out/classes" "$atl_repo/tests/core-java/TestStreams.java"
dx --dex --min-sdk-version=26 --output="$out/streams.jar" "$out/classes"
ulimit -c 0
timeout 60 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/streams.jar" TestStreams
