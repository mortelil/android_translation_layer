#!/bin/sh
set -eu
build=$(realpath "$1"); mkdir -p "$2"; out=$(realpath "$2")
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case $(uname -m) in x86_64) abi=x86_64;; aarch64) abi=arm64-v8a;; *) exit 2;; esac
mkdir -p "$out/classes" "$out/lib/$abi"
cc -shared -fPIC -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux "$src/probe.c" -L"$build" -landroid -o "$out/lib/$abi/libbitmapunlock.so"
art_java=$(pkg-config --variable=libdir art-standalone)/java
javac -source 8 -target 8 -bootclasspath "$art_java/core-all_classes.jar" -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$src/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$src/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex "lib/$abi/libbitmapunlock.so")
