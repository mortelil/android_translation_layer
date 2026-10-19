#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
apks=$1;out=$2
mkdir -p "$out"
for sdk in 26 31 34; do
 ANDROID_APP_DATA_DIR="$out/data$sdk" XDG_CACHE_HOME="$out/cache$sdk" timeout 40 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apks/sdk$sdk.apk" > "$out/sdk$sdk.log" 2>&1
 grep "PENDING_SDK_PASS sdk=$sdk checks=6" "$out/sdk$sdk.log"
done
