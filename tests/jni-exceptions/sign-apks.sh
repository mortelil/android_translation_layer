#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# sign-apks.sh - sign the built JNI-EXC-001 APKs on the HOST and freeze them.
#
#   sign-apks.sh <BUILD_DIR> <FREEZE_DIR>
#
# <BUILD_DIR> is the output of build.sh (inside atl_dev); <FREEZE_DIR> receives
# the immutable signed APKs jniexc-001-{a,b,c}.apk plus apk-sha256.txt.
#
# Refuses to overwrite an existing frozen APK (each attempt keeps its own copy).
set -eu
if [ "$#" -ne 2 ]; then
    echo "usage: sign-apks.sh <BUILD_DIR> <FREEZE_DIR>" >&2
    exit 2
fi

BUILD_DIR=$(cd "$1" && pwd)
FREEZE_DIR=$2
APKSIGNER=${APKSIGNER:-/home/morten/Android/Sdk/build-tools/37.0.0/apksigner}
KS=${JNIEXC_KEYSTORE:-/home/morten/ATL/font-tests/debug.keystore}

command -v "$APKSIGNER" >/dev/null 2>&1 || [ -x "$APKSIGNER" ] || {
    echo "sign-apks.sh: apksigner not found: $APKSIGNER" >&2; exit 2; }
[ -f "$KS" ] || { echo "sign-apks.sh: keystore not found: $KS" >&2; exit 2; }

mkdir -p "$FREEZE_DIR"

for v in a b c; do
    SRC="$BUILD_DIR/$v/jniexc-001-$v-aligned.apk"
    DST="$FREEZE_DIR/jniexc-001-$v.apk"
    [ -f "$SRC" ] || { echo "sign-apks.sh: missing $SRC" >&2; exit 2; }
    if [ -e "$DST" ]; then
        echo "sign-apks.sh: refusing to overwrite existing $DST" >&2
        exit 2
    fi
    "$APKSIGNER" sign \
        --ks "$KS" --ks-key-alias fontprobe \
        --ks-pass pass:android --key-pass pass:android \
        --out "$DST" "$SRC"
    echo "signed $DST"
done

( cd "$FREEZE_DIR" && sha256sum jniexc-001-a.apk jniexc-001-b.apk jniexc-001-c.apk ) \
    > "$FREEZE_DIR/apk-sha256.txt"
cat "$FREEZE_DIR/apk-sha256.txt"

for v in a b c; do
    "$APKSIGNER" verify --print-certs "$FREEZE_DIR/jniexc-001-$v.apk" | head -2
done
