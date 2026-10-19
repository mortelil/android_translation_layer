#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/AttachmentActivity.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$here/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/attachment.apk"
(cd "$out" && aapt add attachment.apk classes.dex)
