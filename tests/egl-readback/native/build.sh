#!/bin/sh
set -eu
build=$(realpath "$1");mkdir -p "$2";out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case $(uname -m) in x86_64) abi=x86_64;; aarch64) abi=arm64-v8a;; *) exit 2;; esac
mkdir -p "$out/classes" "$out/lib/$abi"
cc -shared -fPIC -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux $(pkg-config --cflags egl) "$src/probe.c" -lEGL -ldl -o "$out/lib/$abi/libeglcheck.so"
"${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}" -source 8 -target 8 -cp "$build/src/api-impl/hax.jar:$(pkg-config --variable=libdir art-standalone)/java/core-all_classes.jar" -d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=21 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/eglcheck.apk"
(cd "$out" && aapt add eglcheck.apk classes.dex "lib/$abi/libeglcheck.so")
