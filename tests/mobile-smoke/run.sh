#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test "$#" -eq 3 || { echo 'Usage: run.sh BUILD_DIR APK OUTPUT_DIR' >&2; exit 2; }
build=$(cd "$1" && pwd)
apk=$(realpath "$2")
mkdir -p "$3"
out=$(cd "$3" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export ANDROID_APP_DATA_DIR="$out/appdata" ATL_MEDIA_ROOT="$out/fixtures"
mkdir -p "$ANDROID_APP_DATA_DIR"
export RUN_FROM_BUILDDIR=1 LD_LIBRARY_PATH="$build${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
python3 "$source_dir/fixtures.py" "$ATL_MEDIA_ROOT"
cd "$build"
if ! timeout "${ATL_TEST_TIMEOUT:-60}" ./android-translation-layer "$apk" --install-internal > "$out/install.log" 2>&1; then
	cat "$out/install.log"
	exit 1
fi
status=0
timeout "${ATL_TEST_TIMEOUT:-60}" ./android-translation-layer "$apk" -l TestActivity > "$out/result.log" 2>&1 || status=$?
cat "$out/result.log"
test "$status" -eq 0
grep -q '^ATL TEST APK PASSED$' "$out/result.log"
