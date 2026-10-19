#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
apks=$1; out=$2
mkdir -p "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 40 sh "$repo/scripts/mobile/run.sh" "$apks/owner.apk" --install-internal > "$out/install.log" 2>&1
timeout 40 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apks/caller.apk" > "$out/caller.log" 2>&1
grep CROSS_PACKAGE_SERVICE_PASS "$out/caller.log"
timeout 40 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apks/owner.apk" > "$out/forward.log" 2>&1
grep CHILD_TO_PARENT_PASS "$out/forward.log"
