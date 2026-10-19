#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$1; out=$2
mkdir -p "$out/classes"
javac -cp "$build/src/api-impl/hax.jar" -d "$out/classes" "$here/Probe.java"
dx --min-sdk-version=26 --dex --output="$out/classes.dex" "$out/classes"
for sdk in 26 31 34; do
 mkdir -p "$out/$sdk"
 cat > "$out/$sdk/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.atl.pendingsdk"><uses-sdk android:minSdkVersion="$sdk" android:targetSdkVersion="35"/><application><activity android:name=".Probe" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>
XML
 aapt package -f -M "$out/$sdk/AndroidManifest.xml" -I "$build/res/framework-res/framework-res.apk" -F "$out/sdk$sdk.apk"
 (cd "$out" && aapt add "sdk$sdk.apk" classes.dex)
done
