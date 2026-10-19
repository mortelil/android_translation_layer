#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
sh "$repo/tests/nsd-registration/build.sh" "$ATL_BUILD_DIR" "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 45 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$out/registration.apk" > "$out/result.log" 2>&1
grep -a NSD_REG_PASS "$out/result.log"
ATL_TEST_NSD_UNAVAILABLE=1 DBUS_SYSTEM_BUS_ADDRESS=unix:path=/nonexistent-atl-test-bus \
 timeout 15 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$out/registration.apk" > "$out/unavailable.log" 2>&1
grep -a NSD_UNAVAILABLE_PASS "$out/unavailable.log"
