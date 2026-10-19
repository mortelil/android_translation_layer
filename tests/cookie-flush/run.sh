#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
export ATL_BUILD_DIR=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
test ! -e "$out/data" || { echo 'Use a fresh output directory'; exit 1; }
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache" ATL_UGLY_ENABLE_WEBVIEW=1
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
rc=0
timeout -s KILL 20 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/first.log" 2>&1 || rc=$?
test "$rc" = 137
grep -a -q COOKIE_FLUSH_READY_FOR_KILL "$out/first.log"
export ATL_TEST_COOKIE_IO_FAILURE=1
timeout -k 3 25 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/restart.log" 2>&1
grep -a COOKIE_FLUSH_RESTART_PASS "$out/restart.log"
grep -a COOKIE_FLUSH_IO_FAILURE_PASS "$out/restart.log"
