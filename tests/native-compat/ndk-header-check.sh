#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# Compiles tests/native-compat/test-media-drivers.c against the *real* NDK
# headers of AOSP tag android-17.0.0_r1 (commit 94b4c163, this task's reference
# revision) instead of hand-copied prototypes, links the definitions from
# src/libandroid/media.c and runs the documented-outcome checks.
#
# Needs curl, base64 and a C compiler; run it on the host or inside atl_dev.
# The headers are fetched into a temporary directory and are not vendored.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
tag=android-17.0.0_r1
base=https://android.googlesource.com/platform
inc=$(mktemp -d)
out=$(mktemp -d)
trap 'rm -rf "$inc" "$out"' EXIT

fetch() { # fetch <repo> <path> <include-relative destination>
	mkdir -p "$inc/$(dirname "$3")"
	curl -sf "$base/$1/+/refs/tags/$tag/$2?format=TEXT" | base64 -d > "$inc/$3"
	test -s "$inc/$3" || { echo "empty: $1 $2" >&2; exit 1; }
}

# NDK media headers: the families this test covers.
fetch frameworks/av media/ndk/include/media/NdkMediaError.h media/NdkMediaError.h
fetch frameworks/av media/ndk/include/media/NdkMediaCrypto.h media/NdkMediaCrypto.h
fetch frameworks/av media/ndk/include/media/NdkMediaDrm.h media/NdkMediaDrm.h
fetch frameworks/av media/ndk/include/media/NdkImage.h media/NdkImage.h
fetch frameworks/av media/ndk/include/media/NdkImageReader.h media/NdkImageReader.h
# and the platform headers those include.
fetch frameworks/native libs/nativewindow/include/android/hardware_buffer.h android/hardware_buffer.h
fetch frameworks/native libs/nativewindow/include/android/data_space.h android/data_space.h
fetch frameworks/native libs/nativewindow/include/android/native_window.h android/native_window.h
fetch frameworks/native libs/arect/include/android/rect.h android/rect.h
fetch system/core libcutils/include/cutils/native_handle.h cutils/native_handle.h

cc "$repo/tests/native-compat/test-media-drivers.c" "$repo/src/libandroid/media.c" \
	-I"$inc" -o "$out/media-drivers-test"
"$out/media-drivers-test"
