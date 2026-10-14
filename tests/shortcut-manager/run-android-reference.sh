#!/bin/sh
# API-reference runner. Invoke on the host under resources.py --kind android.
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <signed-probe.apk> <output-dir>" >&2
    exit 64
fi
APK=$1
OUT=$2
ADB=${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
PACKAGE=org.atl.shortcutmanager
ACTIVITY=$PACKAGE/.Probe

[ -f "$APK" ] || { echo "Missing APK: $APK" >&2; exit 66; }
[ -x "$ADB" ] || { echo "Missing ADB executable: $ADB" >&2; exit 66; }
mkdir -p "$OUT"
installed=$("$ADB" -s "$SERIAL" shell pm path "$PACKAGE")
if [ -n "$installed" ]; then
    "$ADB" -s "$SERIAL" uninstall "$PACKAGE" >"$OUT/uninstall.log" 2>&1
else
    printf '%s\n' "Package not installed before this run" >"$OUT/uninstall.log"
fi
"$ADB" -s "$SERIAL" install -r -g "$APK" >"$OUT/install.log" 2>&1
"$ADB" -s "$SERIAL" shell cmd shortcut reset-throttling >"$OUT/reset-throttling.log" 2>&1
"$ADB" -s "$SERIAL" logcat -c
"$ADB" -s "$SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY" >"$OUT/start.log" 2>&1
"$ADB" -s "$SERIAL" logcat -d >"$OUT/logcat.txt"
python3 -c 'import json,re,sys; text=open(sys.argv[1], encoding="utf-8", errors="replace").read(); fails=re.findall(r"SHORTCUT_MANAGER FAIL[^\r\n]*", text); passes=re.findall(r"SHORTCUT_MANAGER PASS checks=(\d+)", text); assert not fails and passes, "reference probe did not pass: failures=%r passes=%r" % (fails, passes); result={"status":"PASS","checks":int(passes[-1]),"device":sys.argv[2]}; open(sys.argv[3],"w").write(json.dumps(result,sort_keys=True)+"\n"); print(json.dumps(result,sort_keys=True))' \
    "$OUT/logcat.txt" "$SERIAL" "$OUT/app-results.json"
echo "ANDROID_SHORTCUT_REFERENCE PASS artifacts=$OUT"
