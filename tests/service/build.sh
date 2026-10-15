#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Gate 2 service probe (org.atl.tests.service, rows S01-S15) APK build.
# Run INSIDE the atl_dev Toolbox container:
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/lifecycle-foundation/service/repo/tests/service/build.sh [BUILD_DIR [OUT_DIR]]'
# Produces a zipaligned but UNSIGNED svc-gate-aligned.apk; the parent signs it on the host.
# Modeled on tests/local-service-probe/build.sh and tests/mobile-smoke/build.sh.
set -eu
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$(cd "${1:-/home/morten/ATL/lifecycle-foundation/service/repo/build-mobile}" && pwd)
out=$(mkdir -p "${2:-$source_dir/out}" && cd "${2:-$source_dir/out}" && pwd)
mkdir -p "$out/classes"
art_java=$(pkg-config --variable=libdir art-standalone)/java
# hax.jar makes android.atl.* (ATLFakeProcessBackend) visible; core-all_classes.jar is the
# target runtime API (see tests/mobile-smoke/build.sh for why it is also the bootclasspath).
# shellcheck disable=SC2046
"${JAVAC:-javac}" -source 8 -target 8 -Xlint:-options \
	-bootclasspath "$art_java/core-all_classes.jar" \
	-cp "$build/src/api-impl/hax.jar:$art_java/core-all_classes.jar" \
	-d "$out/classes" \
	$(find "$source_dir/src" -name '*.java')
dx --min-sdk-version=24 --dex --output="$out/classes.dex" "$out/classes"
aapt package -f \
	-M "$source_dir/AndroidManifest.xml" \
	-I "$build/res/framework-res/framework-res.apk" \
	-F "$out/svc-gate-unsigned.apk"
(cd "$out" && aapt add svc-gate-unsigned.apk classes.dex)
zipalign -f 4 "$out/svc-gate-unsigned.apk" "$out/svc-gate-aligned.apk"
printf '%s\n' "$out/svc-gate-aligned.apk"
