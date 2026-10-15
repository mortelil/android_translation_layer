#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run in Alpine; caller supplies environment.sh runtime variables and X display.
set -eu
test "$#" -eq 3 || { echo 'Usage: run.sh BUILD_DIR PROBE_APK OUTPUT_DIR' >&2; exit 2; }
build=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache" RUN_FROM_BUILDDIR=1
unset ATL_MEDIA_ROOT
mkdir -p "$ANDROID_APP_DATA_DIR" "$XDG_CACHE_HOME"
cd "$build"
timeout 60 ./android-translation-layer "$apk" -l org.atl.smallcompat.Probe > "$out/probe.log" 2>&1
grep -a -q '^SMALL_COMPAT PASS' "$out/probe.log"
timeout 30 ./android-translation-layer "$apk" -l org.atl.smallcompat.FinishLaunch > "$out/finish-launch.log" 2>&1
grep -a -q '^FINISH_LAUNCH PASS' "$out/finish-launch.log"
if test "${TEST_NATIVE_CANCEL:-0}" = 1; then
    ./android-translation-layer "$apk" -l org.atl.smallcompat.NativeCancel > "$out/cancel.log" 2>&1 &
    app=$!
    trap 'kill "$app" 2>/dev/null || true' EXIT INT TERM
    found=0
    for n in $(seq 1 60); do
        id=$(xdotool search --onlyvisible --name '^Open Media File$' 2>/dev/null | head -1)
        if test -n "$id"; then
            sleep 1
            xdotool windowfocus --sync "$id" key --clearmodifiers Escape
            found=1
            break
        fi
        sleep 0.5
    done
    test "$found" = 1
    for n in $(seq 1 60); do
        if grep -a -q '^NATIVE_CANCEL PASS' "$out/cancel.log"; then
            wait "$app"
            trap - EXIT INT TERM
            exit 0
        fi
        sleep 0.5
    done
    exit 1
fi
