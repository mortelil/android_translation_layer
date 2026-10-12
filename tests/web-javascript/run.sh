#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside Alpine, under dbus-run-session and Xvfb (or a disposable display).
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export ATL_BUILD_DIR=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
export ANDROID_APP_DATA_DIR="$out/appdata" ATL_UGLY_ENABLE_WEBVIEW=1
timeout 70 sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/result.log" 2>&1
grep -q '^ATL JAVASCRIPT CONTRACTS PASSED$' "$out/result.log"
# Synthetic secrets in thrown exceptions must not leak through diagnostics.
if grep -q 'DO_NOT_LOG_THIS_SECRET' "$out/result.log"; then exit 1; fi
echo 'PASS: JavaScript evaluation, Java bridge and redacted error logging'
