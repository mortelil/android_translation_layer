#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
sh "$repo/tests/component-matching/build.sh" "$ATL_BUILD_DIR" "$out/apks"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 60 sh "$repo/scripts/mobile/run.sh" "$out/apks/owner.apk" --install-internal > "$out/install.log" 2>&1
timeout 60 sh "$repo/scripts/mobile/run.sh" "$out/apks/probe.apk" -l org.atl.components.Probe > "$out/result.log" 2>&1
grep -a 'COMPONENT_MATCH_PASS checks=51' "$out/result.log"
! grep -aq COMPONENT_MATCH_FAIL "$out/result.log"
