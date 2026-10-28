#!/bin/sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
mkdir -p "$out/android/os" "$out/android/util"
# Isolate the property class from Android initialization and count logging calls.
cat > "$out/android/os/Build.java" <<'JAVA'
package android.os;
public class Build { public static class VERSION { public static int SDK_INT=33; } }
JAVA
cat > "$out/android/util/Log.java" <<'JAVA'
package android.util;
public class Log { public static int calls; public static int i(String tag,String message) {calls++; return 0;} }
JAVA
javac -d "$out" "$out/android/os/Build.java" "$out/android/util/Log.java" \
 "$repo/src/api-impl/android/os/SystemProperties.java" "$repo/tests/system-property-logging/Probe.java"
env -u ATL_DEBUG_SYSTEM_PROPERTIES java -cp "$out" Probe normal
ATL_DEBUG_SYSTEM_PROPERTIES=1 java -cp "$out" Probe debug
ATL_DEBUG_SYSTEM_PROPERTIES=0 java -cp "$out" Probe normal
