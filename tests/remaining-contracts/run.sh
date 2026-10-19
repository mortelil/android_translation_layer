#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
sh "$repo/tests/remaining-contracts/build.sh" "$ATL_BUILD_DIR" "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 60 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$out/remaining.apk" > "$out/result.log" 2>&1
grep -a 'REMAINING_PASS checks=40' "$out/result.log"
