#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here"/*.java
dx --dex --output="$out/classes.dex" "$out/classes"
for app in owner caller; do
 mkdir -p "$out/$app"
 cp "$here/$app.xml" "$out/$app/AndroidManifest.xml"
 aapt package -f -M "$out/$app/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/$app.apk"
 (cd "$out" && aapt add "$app.apk" classes.dex)
done
case $(uname -m) in x86_64) abi=x86_64;; aarch64) abi=arm64-v8a;; *) exit 2;; esac
mkdir -p "$out/lib/$abi"
cc -shared -fPIC -I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux "$here/actions.c" $(pkg-config --cflags --libs gio-2.0) -o "$out/lib/$abi/libpendingactions.so"
mkdir -p "$out/notification"
cp "$here/notification.xml" "$out/notification/AndroidManifest.xml"
aapt package -f -M "$out/notification/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/notification.apk"
(cd "$out" && aapt add notification.apk classes.dex "lib/$abi/libpendingactions.so")
