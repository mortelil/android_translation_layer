#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Inside atl_dev, with ATL_PREFIX / ATL_BUILD_DIR / ATL_CORE_JAR set.
#
# Runs one caller row (Probe.java) against a freshly installed owner APK into a
# private data dir, so every row starts from a fresh owner process:
#
#   ATL_PROVIDER_ROW=timeout ATL_LATCH_SLEEP_MS=15000 \
#   ATL_PROVIDER_READY_TIMEOUT_MS=250 tests/provider-call/run.sh <outdir>
# READY now precedes provider initialization, so acquisition can overlap boot.
#
# The row environment is read in the owner process and is inherited through
# scripts/mobile/run.sh. Default row: the original cross-package suite
# (run-all.sh runs every row).
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$(realpath -m "$1")
row=${ATL_PROVIDER_ROW:-legacy}
# The row is selected inside the app process (Probe.java), so it must be in the
# environment of scripts/mobile/run.sh and not just of this script.
export ATL_PROVIDER_ROW="$row"
mkdir -p "$out"
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
sh "$repo/tests/provider-call/build.sh" "$ATL_BUILD_DIR" "$out/apks"
timeout 60 "$repo/scripts/mobile/run.sh" "$out/apks/owner.apk" --install-internal > "$out/install.log" 2>&1
sha256sum "$out/apks/owner.apk" "$ANDROID_APP_DATA_DIR/_installed_apks_/org.atl.providerowner.apk" > "$out/apks.sha256"
cmp "$out/apks/owner.apk" "$ANDROID_APP_DATA_DIR/_installed_apks_/org.atl.providerowner.apk"
status=0
timeout 120 "$repo/scripts/mobile/run.sh" "$out/apks/caller.apk" > "$out/test.log" 2>&1 || status=$?
# The caller and the owner process print to the same log, and their writes can
# interleave mid-line (observed: "PROVIDER_CALL_PASS" glued to the owner's
# stdout), so the marker is matched unanchored. The exit status is still
# required: a caller that dies reports status 1 and prints no marker.
if [ "$row" = legacy ]; then marker='PROVIDER_CALL_PASS'; else marker="PROVIDER_CALL_PASS $row"; fi
grep -q "$marker" "$out/test.log"
test "$status" = 0
echo "PROVIDER_CALL_PASS $row"
