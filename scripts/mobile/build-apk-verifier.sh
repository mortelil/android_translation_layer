#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test -f /etc/alpine-release || { echo 'Build the verifier inside Alpine.' >&2; exit 1; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
output="$atl_prefix/libexec/atl-apk-verifier"
mkdir -p "$output/classes"
jar_path=${ATL_APKSIG_JAR:-$output/apksig-8.6.1.jar}
if test ! -f "$jar_path"; then
  curl -fL --connect-timeout 15 --max-time 120 \
    https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.6.1/apksig-8.6.1.jar \
    -o "$jar_path"
fi
printf '%s  %s\n' c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89 "$jar_path" | sha256sum -c -
if test "$jar_path" != "$output/apksig-8.6.1.jar"; then cp "$jar_path" "$output/apksig-8.6.1.jar"; fi
/usr/lib/jvm/java-1.8-openjdk/bin/javac -cp "$jar_path" -d "$output/classes" "$atl_repo/tools/apk-verifier/VerifyApk.java"
cat > "$output/run" <<'RUN'
#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# The helper runs under OpenJDK, not ART: do not load ART's JVM-native libraries.
unset LD_LIBRARY_PATH LD_PRELOAD BOOTCLASSPATH CLASSPATH
exec /usr/lib/jvm/java-1.8-openjdk/bin/java -Xmx256m -cp "$root/classes:$root/apksig-8.6.1.jar" org.atl.tools.VerifyApk "$@"
RUN
chmod +x "$output/run"
printf 'Optional verified signer helper: %s/run\n' "$output"
