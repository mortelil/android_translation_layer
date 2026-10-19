#!/bin/sh
set -eu
build=$(realpath "$1"); mkdir -p "$2/classes"; out=$(realpath "$2")
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f -M "$here/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/probe.apk"
(cd "$out" && aapt add probe.apk classes.dex)
mkdir -p "$out/fixture/lib/x86_64"
cat > "$out/fixture/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.atl.nativefixture"><uses-sdk android:minSdkVersion="26"/><application/></manifest>
MANIFEST
aapt package -f -M "$out/fixture/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/org.atl.nativefixture.apk"
printf '*' > "$out/fixture/lib/x86_64/libmetadata_fixture.so"
(cd "$out/fixture" && aapt add "$out/org.atl.nativefixture.apk" lib/x86_64/libmetadata_fixture.so)
