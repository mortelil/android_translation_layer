#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Rebuild the installed core class files using D8 lambda desugaring.
set -eu
test -f /etc/alpine-release || { echo 'Run inside Alpine.' >&2; exit 1; }
: "${R8_JAR:?Set R8_JAR to the downloaded r8-8.3.37.jar}"
expected=900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199
actual=$(sha256sum "$R8_JAR" | cut -d ' ' -f1)
test "$actual" = "$expected" || { echo 'Unexpected R8 JAR checksum.' >&2; exit 1; }
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
prefix=${ATL_PREFIX:-$(dirname "$repo")/.atl-runtime}
java=${D8_JAVA:-/usr/lib/jvm/java-21-openjdk/bin/java}
classes=${ATL_CORE_CLASSES:-/usr/lib/java/core-all_classes.jar}
original=${ATL_ART_DEX_DIR:-/usr/lib/java/dex/art}
mkdir -p "$prefix/share/art"
work=$(mktemp -d "$prefix/share/art/core-build.XXXXXX")
trap 'rm -rf "$work"' EXIT
"$java" -Xmx2g -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 28 --output "$work" "$classes"
python3 - "$work" "$classes" "$original" <<'PY'
import pathlib, sys, zipfile
work, classes, original = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(work / 'core-all-hostdex.jar', 'w', compression=zipfile.ZIP_DEFLATED) as out:
    seen = set()
    for source in [classes, original / 'core-oj-hostdex.jar', original / 'core-libart-hostdex.jar']:
        with zipfile.ZipFile(source) as src:
            for name in src.namelist():
                if name in seen or name.endswith(('.class', '.dex', '/')) or name.startswith('META-INF/'):
                    continue
                out.writestr(name, src.read(name))
                seen.add(name)
    for dex in sorted(work.glob('classes*.dex')):
        out.write(dex, dex.name)
PY
mv "$work/core-all-hostdex.jar" "$prefix/share/art/core-all-hostdex.jar"
printf 'Built %s/share/art/core-all-hostdex.jar\nEnable explicitly with ATL_CORE_JAR; this does not replace the system boot jars.\n' "$prefix"
