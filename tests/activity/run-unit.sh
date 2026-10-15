#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Runs the ATLActivityController unit tests on the lane runtime (dalvikvm).
# Evidence: this script's stdout is captured by the lane evidence run.
#
# A01/A02: PathInvariants  - transition-table / lifecycle-path invariants
# A06:      FocusInvariants - window focus/visibility vs lifecycle state
#
# The controller is compiled together with the tests against the lane's full
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
	"$atl_repo/tests/activity/src/org/atl/tests/activity/PathInvariants.java" \
	"$atl_repo/tests/activity/src/org/atl/tests/activity/FocusInvariants.java"
dx --dex --min-sdk-version=26 --output="$out/path-tests.jar" "$out/classes"
ulimit -c 0
# dalvikvm aborts in its AsynchronousCloseMonitor teardown AFTER the test main
# has finished (known, positional: it happens after the ALL_DONE line), so the
# script's verdict comes from the test markers, not from dalvikvm's exit code.
log="$out/unit.log"
set +e
timeout 60 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/path-tests.jar:$hax" org.atl.tests.activity.PathInvariants 2>&1 | tee -a "$log"
timeout 60 dalvikvm -Xbootclasspath:"$BOOTCLASSPATH" -cp "$out/path-tests.jar:$hax" org.atl.tests.activity.FocusInvariants 2>&1 | tee -a "$log"
set -e
grep -q "^ACTLPATH ALL_DONE .* PASS 0 FAIL$" "$log" || { echo "unit: PathInvariants did not finish cleanly" >&2; exit 1; }
grep -q "^ACTLFOCUS ALL_DONE .* PASS 0 FAIL$" "$log" || { echo "unit: FocusInvariants did not finish cleanly" >&2; exit 1; }
