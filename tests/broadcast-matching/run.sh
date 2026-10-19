#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
sh "$repo/tests/broadcast-matching/build.sh" "$ATL_BUILD_DIR" "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 45 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$out/broadcast.apk" > "$out/result.log" 2>&1
grep -a 'BROADCAST_PASS deliveries=8' "$out/result.log"
