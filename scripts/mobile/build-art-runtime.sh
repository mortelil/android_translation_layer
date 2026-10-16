#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Build the native ART runtime into a private overlay, not the system prefix.
set -eu
test -f /etc/alpine-release || { echo 'Run inside Alpine.' >&2; exit 1; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
art=$atl_workspace/art_standalone
arch=$(uname -m)
case $arch in
	x86_64) cc='gcc -fpatchable-function-entry=16'; cxx='g++ -fpatchable-function-entry=16' ;;
	aarch64) cc=gcc; cxx=g++ ;;
	*) echo "Untested ART runtime architecture: $arch" >&2; exit 1 ;;
esac
# Keep compiler configurations separate: make does not track compiler flag changes.
out=${ATL_ART_OUT:-$art/out-messenger-$arch}
mkdir -p "$out"
out=$(CDPATH= cd -- "$out" && pwd)
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-1.8-openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
# ART's historical output directory is named linux-x86 on aarch64 too.
libs=$out/host/linux-x86/lib64
make -C "$art" -j "${JOBS:-4}" \
	"$libs/libart.so" "$libs/libart-compiler.so" "$libs/libutils.so" "$libs/libandroidfw.so" "$libs/libnativehelper.so" \
	OUT_DIR="$out" HOST_CC="$cc" HOST_CXX="$cxx" ____LIBDIR=lib
# All required libraries must exist before publishing the private overlay.
for name in libart libart-compiler libutils libandroidfw libnativehelper; do
    test -s "$libs/$name.so" || { echo "Missing built library: $libs/$name.so" >&2; exit 1; }
done
mkdir -p "$atl_prefix/lib" "$atl_prefix/java/dex"
: > "$atl_prefix/native-libraries.txt.new"
for library in "$libs"/*.so; do
	# Retain private-build symbols; no system install or stripping is performed.
	test -s "$library"
	ln -sfn "$library" "$atl_prefix/lib/$(basename "$library")"
	basename "$library" >> "$atl_prefix/native-libraries.txt.new"
done
mv "$atl_prefix/native-libraries.txt.new" "$atl_prefix/native-libraries.txt"
# ART locates packaged JNI libraries relative to libart.so's load directory.
test -d "$atl_art_dex/natives"
if test -e "$atl_prefix/java/dex/art" && test ! -L "$atl_prefix/java/dex/art"; then
	echo "Refusing to replace existing directory: $atl_prefix/java/dex/art" >&2
	exit 1
fi
ln -sfn "$atl_art_dex" "$atl_prefix/java/dex/art"
printf 'Built private ART runtime in %s (source outputs must remain available).\n' "$atl_prefix"
printf 'Use the same ATL_PREFIX when building and launching ATL.\n'
