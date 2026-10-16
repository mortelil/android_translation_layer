#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$1
mkdir -p "$out"
cc -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux \
 "$repo/tests/drawable-container/native.c" $(pkg-config --cflags --libs gtk4) -o "$out/native"
"$out/native"
