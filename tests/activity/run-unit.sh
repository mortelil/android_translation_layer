#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Runs the ATLActivityController unit tests on the lane runtime (dalvikvm).
# Evidence: this script's stdout is captured by the lane evidence run.
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir "$out/classes"
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" \
	-cp "$atl_build/api-impl.jar" \
	-d "$out/classes" \
	"$atl_repo/tests/activity/src/org/atl/tests/activity/PathInvariants.java"
dx --dex --min-sdk-version=26 --output="$out/path-tests.jar" "$out/classes"
ulimit -c 0
timeout 60 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/path-tests.jar" org.atl.tests.activity.PathInvariants
