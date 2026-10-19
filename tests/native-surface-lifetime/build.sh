#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
case $(uname -m) in x86_64) abi=x86_64;; aarch64) abi=arm64-v8a;; *) exit 2;; esac
mkdir -p "$out/lib/$abi"
cc -shared -fPIC -pthread -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux "$here/probe.c" $(pkg-config --cflags --libs gtk4 egl) -L"$build" -landroid -o "$out/lib/$abi/libsurfaceprobe.so"
aapt package -f -M "$here/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex "lib/$abi/libsurfaceprobe.so")
