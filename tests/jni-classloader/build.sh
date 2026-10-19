#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2; android_libc=${3:?Supply x86_64 Android libc.so (from an emulator or NDK sysroot)}
[ "$(uname -m)" = x86_64 ] || { echo 'This dual-runtime native fixture targets x86_64'; exit 2; }
mkdir -p "$out/classes" "$out/lib/x86_64"
javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
cc -shared -nostdlib -fPIC -fno-stack-protector -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux "$here/probe.c" -Wl,--no-as-needed -Wl,--allow-shlib-undefined "$android_libc" -o "$out/lib/x86_64/libloaderprobe.so"
aapt package -f -M "$here/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex lib/x86_64/libloaderprobe.so)
