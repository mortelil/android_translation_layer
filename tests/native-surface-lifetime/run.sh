#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
apks=$1; out=$2
mkdir -p "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 45 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apks/probe.apk" > "$out/composited.log" 2>&1
grep NATIVE_SURFACE_PASS "$out/composited.log"
ATL_DIRECT_EGL=1 GDK_DEBUG=gl-egl timeout 45 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apks/probe.apk" > "$out/direct-x11.log" 2>&1
grep NATIVE_SURFACE_PASS "$out/direct-x11.log"
