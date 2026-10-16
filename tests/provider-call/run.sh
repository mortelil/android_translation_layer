#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Inside atl_dev, with ATL_PREFIX / ATL_BUILD_DIR / ATL_CORE_JAR set.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$(realpath -m "$1")
mkdir -p "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
sh "$repo/tests/provider-call/build.sh" "$ATL_BUILD_DIR" "$out/apks"
timeout 60 "$repo/scripts/mobile/run.sh" "$out/apks/owner.apk" --install-internal > "$out/install.log" 2>&1
sha256sum "$out/apks/owner.apk" "$ANDROID_APP_DATA_DIR/_installed_apks_/org.atl.providerowner.apk" > "$out/apks.sha256"
cmp "$out/apks/owner.apk" "$ANDROID_APP_DATA_DIR/_installed_apks_/org.atl.providerowner.apk"
timeout 90 "$repo/scripts/mobile/run.sh" "$out/apks/caller.apk" > "$out/test.log" 2>&1
grep -q '^PROVIDER_CALL_PASS$' "$out/test.log"
echo PROVIDER_CALL_PASS
