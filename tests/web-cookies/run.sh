#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside Alpine after build.sh; ATL_PREFIX and dependencies must be configured.
set -eu
build=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export ATL_BUILD_DIR="$build" ATL_UGLY_ENABLE_WEBVIEW=1 ATL_WEBVIEW_DIAGNOSTICS=1
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 50 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/stdout.log" 2> "$out/stderr.log"
grep -q 'ATL WEB COOKIE CONTRACTS PASSED' "$out/stdout.log"
! grep -q 'COOKIE CONTRACT FAILED' "$out/stderr.log"
echo 'PASS: HTTP / JavaScript / Java share cookies; HttpOnly, Secure, domain isolation, worker access and deletion'
