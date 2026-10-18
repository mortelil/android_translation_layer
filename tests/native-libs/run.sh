#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Host-JVM test for runtime native-library extraction
# (src/api-impl/android/atl/ATLNativeLibs.java). The class under test has no
# Android dependencies: no emulator and no ATL runtime are involved.
#
#   sh tests/native-libs/run.sh
#
# This compiles the checkout's source in the same command that runs the test and
# passes the source path and its sha256 in, so the test can prove that the class
# it exercises was produced by the file being changed. Exits 0 only when the
# final NATIVE_LIBS_PASS marker has been printed.
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
src=$repo/src/api-impl/android/atl/ATLNativeLibs.java
# Use the container's pinned JDK 8 when it is present (the ATL build's javac).
if [ -z "${JAVAC:-}" ] && [ -x /usr/lib/jvm/java-1.8-openjdk/bin/javac ]; then
	JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac
	JAVA=/usr/lib/jvm/java-1.8-openjdk/bin/java
fi
javac=${JAVAC:-javac}
java=${JAVA:-java}
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/classes"
sha=$(sha256sum "$src" | cut -d' ' -f1)
echo "native-libs: source $src sha256=$sha"
echo "native-libs: compiling with $javac"
"$javac" -source 8 -target 8 -d "$out/classes" \
	"$src" "$repo/tests/native-libs/TestNativeLibs.java"
"$java" -Datl.test.source="$src" -Datl.test.source.sha256="$sha" \
	-cp "$out/classes" TestNativeLibs
