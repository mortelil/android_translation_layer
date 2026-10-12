#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Run in atl_dev. Uses actual ATL classes on the JVM; no native calls needed.
set -eu
build=$(realpath "$1")
mkdir -p "$2"
out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(realpath "$src/../..")
# Compile the real current Parcel source so candidate testing needn't replace
# a build that is still being used by another app test.
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -bootclasspath "$art_java/core-all_classes.jar" -cp "$build/src/api-impl/hax.jar" \
 -d "$out" "${PARCEL_SOURCE:-$repo/src/api-impl/android/os/Parcel.java}" "$src/TestTyped.java"
"${JAVA:-/usr/lib/jvm/java-1.8-openjdk/bin/java}" -cp "$out:$build/src/api-impl/hax.jar" TestTyped
