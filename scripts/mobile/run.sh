#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test -f /etc/alpine-release || { echo 'Run this launcher inside Alpine (e.g. Toolbox).' >&2; exit 1; }
test "$#" -ge 1 || { echo 'Usage: run.sh /absolute/path/to/app.apk [ATL options]' >&2; exit 2; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
apk=$(realpath "$1")
shift
test -f "$apk"
test -f "$atl_prefix/share/art/wolfssljni-hostdex.jar" || { echo 'Run scripts/mobile/build.sh first.' >&2; exit 1; }
export ANDROID_APP_DATA_DIR=${ANDROID_APP_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/atl-mobile-experimental}
mkdir -p "$ANDROID_APP_DATA_DIR"
# Media access and scale are opt-in environment settings; retain the desktop session.
ulimit -c 0
cd "$atl_build"
exec ./android-translation-layer "$apk" "$@"
