#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run under a private D-Bus session and Xvfb, with environment.sh sourced.
set -eu
test "$#" -eq 4 || { echo 'Usage: run-folder.sh BUILD APK FIXTURES OUTPUT' >&2; exit 2; }
build=$(realpath "$1"); apk=$(realpath "$2"); fixtures=$(realpath "$3")
mkdir -p "$4"; out=$(realpath "$4")
export ANDROID_APP_DATA_DIR="$out/data" XDG_CACHE_HOME="$out/cache" RUN_FROM_BUILDDIR=1
unset ATL_MEDIA_ROOT
mkdir -p "$ANDROID_APP_DATA_DIR" "$XDG_CACHE_HOME"
cd "$build"
./android-translation-layer "$apk" -l org.atl.smallcompat.FolderProbe > "$out/select.log" 2>&1 &
app=$!
trap 'kill "$app" 2>/dev/null || true' EXIT INT TERM
for n in $(seq 1 60); do
    if grep -a -q 'FOLDER_PROBE PASS' "$out/select.log"; then break; fi
    id=$(xdotool search --onlyvisible --name '^Choose Media Folder$' 2>/dev/null | head -1)
    if test -n "$id"; then
        sleep 1
        xdotool windowfocus --sync "$id" key --clearmodifiers ctrl+l ctrl+a
        xdotool type --clearmodifiers --delay 0 "$fixtures"
        xdotool key Return
        sleep 1
        xdotool key alt+o
        break
    fi
    sleep .5
done
for n in $(seq 1 60); do
    if grep -a -q 'FOLDER_PROBE PASS' "$out/select.log"; then
        wait "$app"
        trap - EXIT INT TERM
        timeout 20 ./android-translation-layer "$apk" -l org.atl.smallcompat.FolderProbe > "$out/persist.log" 2>&1
        grep -a -q 'FOLDER_PROBE PASS' "$out/persist.log"
        exit 0
    fi
    sleep .5
done
exit 1
