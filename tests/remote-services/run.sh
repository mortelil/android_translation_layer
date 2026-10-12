#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run in Alpine with ATL_PREFIX/ATL_CORE_JAR and the usual runtime configured.
set -eu
build=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export ATL_BUILD_DIR="$build" ATL_ENABLE_REMOTE_SERVICES=1
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache"
timeout 75 dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/stdout.log" 2> "$out/stderr.log"
grep -q 'REMOTE CONTRACTS PASSED' "$out/stdout.log"
echo 'PASS: separate service process and Binder/FD/callback/death contracts'
