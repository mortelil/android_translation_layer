#!/bin/sh
set -eu
build=$(realpath "$1")
mkdir -p "$2/classes"
out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
art_java=$(pkg-config --variable=libdir art-standalone)/java
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 -cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" -d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
