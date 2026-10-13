#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run inside atl_dev (Alpine).  Builds and runs the focused theme/attribute
# resolution probe against the ATL runtime's libandroidfw and the supplied
# APKs.  The probe uses libandroidfw's public C API and the APK loader ATL
# ships (src/api-impl-jni/apk_assets_cache.c), so it is independent of any
# particular app.
#
# Usage:
#   run.sh <framework-res.apk> <app.apk> <package:style> <package:attr>...
#
# Example (VLC's activity theme chain):
#   run.sh framework-res.apk vlc.apk \
#     org.videolan.vlc:Theme.VLC.Player \
#     org.videolan.vlc:windowActionBar android:windowNoTitle android:windowBackground
set -eu
test "$#" -ge 4 || { echo "usage: $0 <framework-res.apk> <app.apk> <package:style> <package:attr>..." >&2; exit 2; }
fw=$(realpath "$1")
app=$(realpath "$2")
shift 2
out=${ATL_THEME_TEST_OUT:-$(realpath "${TMPDIR:-/tmp}")/theme-chain}
mkdir -p "$out"
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
jni_dir=$(CDPATH= cd -- "$probe_dir/../../src/api-impl-jni" && pwd)
cc -D_LARGEFILE64_SOURCE -Wall -Wextra \
  -I/usr/include/androidfw -I"$jni_dir" $(pkg-config --cflags glib-2.0) \
  "$probe_dir/probe.c" "$jni_dir/apk_assets_cache.c"  \
  -L"${ATL_PREFIX:?Set ATL_PREFIX to the runtime under test}/lib" -Wl,-rpath,"$ATL_PREFIX/lib" -landroidfw -lpng $(pkg-config --libs glib-2.0) \
  -o "$out/theme-chain-probe"
"$out/theme-chain-probe" "$fw" "$app" "$@"
cc -D_LARGEFILE64_SOURCE -Wall -Wextra -I"$jni_dir" $(pkg-config --cflags glib-2.0) "$probe_dir/cache.c" "$jni_dir/apk_assets_cache.c" -L"$ATL_PREFIX/lib" -Wl,-rpath,"$ATL_PREFIX/lib" -landroidfw -lpng $(pkg-config --libs glib-2.0) -o "$out/cache-probe"
"$out/cache-probe" "$app"
