#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
if [ "$#" -ne 3 ]; then
	printf 'Usage: %s ATL_BUILD_DIR TEST_APK OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
atl_build=$(cd "$1" && pwd)
apk=$(realpath "$2")
mkdir -p "$3"
output=$(cd "$3" && pwd)
export ANDROID_APP_DATA_DIR="$output/appdata"
export RUN_FROM_BUILDDIR=1
# DT_NEEDED libraries must come from this build, even if another ATL is installed.
export LD_LIBRARY_PATH="$atl_build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
mkdir -p "$ANDROID_APP_DATA_DIR"
cd "$atl_build"
if ! timeout "${ATL_TEST_TIMEOUT:-60}" ./android-translation-layer "$apk" \
	--install-internal > "$output/install.log" 2>&1; then
	cat "$output/install.log"
	exit 1
fi
status=0
timeout "${ATL_TEST_TIMEOUT:-60}" ./android-translation-layer "$apk" \
	--instrument android.test.InstrumentationTestRunner \
	-e class=org.atl.tests.graphics.MatrixCopyTest > "$output/result.log" 2>&1 || status=$?
cat "$output/result.log"
if [ "$status" -ne 0 ]; then
	exit "$status"
fi
# ATL's Instrumentation.finish() can exit zero even when JUnit reports failures.
# Require completion of this suite, not merely a successful process launch.
grep -q '^OK (6 tests)$' "$output/result.log"
