#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Sign the aligned KEYSTORE-001 probe APK on the HOST.
#   sh sign.sh <aligned.apk> <output.apk>
set -eu
if [ "$#" -ne 2 ]; then
	printf 'Usage: %s ALIGNED_APK OUTPUT_APK\n' "$0" >&2
	exit 2
fi
APKSIGNER=${APKSIGNER:-/home/morten/Android/Sdk/build-tools/37.0.0/apksigner}
KS=${KS:-/home/morten/ATL/font-tests/debug.keystore}
"$APKSIGNER" sign \
	--ks "$KS" --ks-key-alias fontprobe \
	--ks-pass pass:android --key-pass pass:android \
	--out "$2" "$1"
"$APKSIGNER" verify --print-certs "$2" >&2
sha256sum "$2"
