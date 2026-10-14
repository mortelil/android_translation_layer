#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Runs the ATLActivityController unit tests on the lane runtime (dalvikvm).
# Evidence: this script's stdout is captured by the lane evidence run.
#
# The controller is compiled together with the test against the lane's full
# api-impl jar (hax.jar). build-mobile/api-impl.jar is a thin 1-class jar and
# must not be on the classpath. Production registration of the controller in
# src/api-impl/meson.build is a separate integration patch (D owns meson).
set -eu
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
hax="$atl_build/src/api-impl/hax.jar"
test -f "$hax" || { echo "missing $hax — build first" >&2; exit 2; }
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/classes"
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" \
	-cp "$hax" \
	-d "$out/classes" \
	"$atl_repo/src/api-impl/android/atl/ATLActivityController.java" \
	"$atl_repo/tests/activity/src/org/atl/tests/activity/PathInvariants.java"
dx --dex --min-sdk-version=26 --output="$out/path-tests.jar" "$out/classes"
ulimit -c 0
timeout 60 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/path-tests.jar:$hax" org.atl.tests.activity.PathInvariants
