#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside Alpine under dbus-run-session and xvfb-run.
set -eu
apk=$(realpath "$1")
mkdir -p "$2"
out=$(realpath "$2")
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export ANDROID_APP_DATA_DIR="$out/profile"
timeout -k 3 35 sh "$repo/scripts/mobile/run.sh" "$apk" > "$out/atl.log" 2>&1 &
runner=$!
trap 'kill "$runner" 2>/dev/null || true' EXIT
for i in $(seq 1 25); do
 grep -aq READY "$out/atl.log" && break
 kill -0 "$runner" || { cat "$out/atl.log"; exit 1; }
 sleep 1
done
grep -aq READY "$out/atl.log"
# Only one app exists on this test X server; focus its text control.
window=$(xdotool search --onlyvisible --name 'org.atl.editorenter' | head -1)
if [ -z "$window" ]; then
 window=$(xdotool search --onlyvisible --class 'android-translation-layer' | head -1)
fi
test -n "$window"
xdotool windowfocus "$window" mousemove --window "$window" 100 70 click 1 key Return
wait "$runner"
trap - EXIT
grep -a 'EDITOR_ENTER_PASS' "$out/atl.log"
