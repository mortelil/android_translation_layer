#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# TRN-011/TRN-014 transport probe APK build (adapted from the PR31
# remote-services probe build, baa9b489, and the Firefox local-service-probe
# build, b51e45f). Run INSIDE the atl_dev Toolbox container:
#
#   toolbox run --container atl_dev sh -lc \
#     '/home/morten/ATL/lifecycle-foundation/transport/repo/tests/transport/build.sh \
#        /home/morten/ATL/lifecycle-foundation/transport/probe-build'
#
# The probe compiles against the JUST-BUILT lane hax.jar, so what it exercises
# is the production ATLBinderConnection of this tree, not a snapshot.
# Produces a zipaligned but UNSIGNED APK (ATL loads it; PR31 precedent).
set -eu
if [ "$#" -ne 2 ]; then
	printf 'Usage: %s BUILD_DIR OUTPUT_DIR\n' "$0" >&2
	exit 2
fi
build=$(CDPATH= cd -- "$1" && pwd)
output=$(mkdir -p "$2" && cd "$2" && pwd)
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
hax="$build/src/api-impl/hax.jar"
test -f "$hax" || { echo "hax.jar not built: $hax" >&2; exit 1; }
framework_res="$build/res/framework-res/framework-res.apk"
test -f "$framework_res" || { echo "framework-res not built: $framework_res" >&2; exit 1; }
mkdir -p "$output/classes"
# shellcheck disable=SC2046
"${JAVAC:-javac}" -encoding UTF-8 -source 8 -target 8 \
	-cp "$hax" \
	-d "$output/classes" \
	$(find "$source_dir/src" -name '*.java')
dx --min-sdk-version=26 --dex --output="$output/classes.dex" "$output/classes"
aapt package -f \
	-M "$source_dir/AndroidManifest.xml" \
	-I "$framework_res" \
	-F "$output/transport-probe.apk"
(cd "$output" && aapt add transport-probe.apk classes.dex)
zipalign -f 4 "$output/transport-probe.apk" "$output/transport-probe-aligned.apk"
printf '%s\n' "$output/transport-probe-aligned.apk"
