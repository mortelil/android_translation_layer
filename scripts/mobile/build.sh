#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test -f /etc/alpine-release || { echo 'Run this build inside Alpine (e.g. Toolbox), not on the Mint host.' >&2; exit 1; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
python3 "$atl_repo/scripts/mobile/check-dependencies.py"
jobs=${JOBS:-4}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-1.8-openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
test -d "$atl_workspace/bionic_translation/.git"
test -d "$atl_workspace/art_standalone/.git"
mkdir -p "$atl_prefix/lib" "$atl_prefix/share/art"
bionic="$atl_workspace/bionic_translation"
if test ! -f "$bionic/build-mobile/build.ninja"; then
	meson setup "$bionic/build-mobile" "$bionic" --prefix="$atl_prefix" --libdir=lib -Dbuildtype=debug
fi
meson compile -C "$bionic/build-mobile" -j "$jobs"
meson install -C "$bionic/build-mobile"
art="$atl_workspace/art_standalone"
make -C "$art" -j "$jobs" libutils ____PREFIX=/usr ____LIBDIR=lib
cp "$art/out/host/linux-x86/lib64/libutils.so" "$atl_prefix/lib/"
mkdir -p "$art/wolfssl-build/classes"
find "$art/external/wolfssljni/src/java" -name '*.java' ! -name WolfSSLJDK8Helper.java > "$art/wolfssl-build/sources.txt"
javac -source 8 -target 8 -d "$art/wolfssl-build/classes" @"$art/wolfssl-build/sources.txt"
jar cf "$art/wolfssl-build/wolfssljni.jar" -C "$art/wolfssl-build/classes" .
dx --dex --output="$atl_prefix/share/art/wolfssljni-hostdex.jar" "$art/wolfssl-build/wolfssljni.jar"
if test ! -f "$atl_build/build.ninja"; then
	meson setup "$atl_build" "$atl_repo" --prefix=/usr --libdir=lib -Dbuildtype=debug
fi
meson compile -C "$atl_build" -j "$jobs"
meson compile -C "$atl_build" test_runner.jar
printf 'Built ATL in %s; private runtime overlay in %s\n' "$atl_build" "$atl_prefix"
