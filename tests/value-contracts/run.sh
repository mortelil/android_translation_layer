#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
apk=$1; out=$2
mkdir -p "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 70 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/result.log" 2>&1
grep 'PASS values checks=57' "$out/result.log"
