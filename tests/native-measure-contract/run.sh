#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
apk=$(realpath "$1")
mkdir -p "$2"
out=$(realpath "$2")
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache" GTK_A11Y=none
timeout 30 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/result.log" 2>&1
grep -q '^MEASURE_CONTRACT_PASS$' "$out/result.log"
