#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Inside Alpine: run.sh BUILD_DIR SIGNED_APK NEW_OUTPUT_DIR
# Uses only this checkout and the caller's ATL_PREFIX/ATL_CORE_JAR runtime.
set -eu
test "$#" -eq 3 || { echo 'Usage: run.sh BUILD_DIR APK NEW_OUTPUT_DIR' >&2; exit 2; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export ATL_BUILD_DIR=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
test ! -e "$out/stdout.log" || { echo 'Use a new output directory; refusing stale evidence' >&2; exit 2; }
. "$atl_repo/scripts/mobile/environment.sh"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
export ATL_IPC_TIMEOUT_MS=3000
mkdir -p "$ANDROID_APP_DATA_DIR" "$XDG_CACHE_HOME"
seconds=${ATL_TEST_TIMEOUT:-180}
setsid timeout "$seconds" dbus-run-session -- xvfb-run -a sh "$atl_repo/scripts/mobile/run.sh" "$apk" > "$out/stdout.log" 2> "$out/stderr.log" &
pid=$!
trap 'kill -TERM -- "-$pid" 2>/dev/null || true' EXIT HUP INT TERM
n=0
while kill -0 "$pid" 2>/dev/null && [ "$n" -lt "$seconds" ]; do
    if grep -aq 'TRANSPORT BATTERY PASSED' "$out/stdout.log"; then break; fi
    sleep 1; n=$((n + 1))
done
grep -aq 'TRANSPORT BATTERY PASSED' "$out/stdout.log" || { tail -40 "$out/stdout.log"; exit 1; }
python3 "$atl_repo/tests/transport/verify_transport_frames.py" "$out/data/$(basename "$apk")_/files/results.json"
