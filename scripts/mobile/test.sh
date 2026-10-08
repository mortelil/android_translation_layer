#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
test -f /etc/alpine-release || { echo 'Run these tests inside Alpine (e.g. Toolbox).' >&2; exit 1; }
atl_repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
. "$atl_repo/scripts/mobile/environment.sh"
export JAVAC=${JAVAC:-/usr/lib/jvm/java-1.8-openjdk/bin/javac}
output="$atl_repo/build/mobile-tests"
cd "$atl_repo"
tests/graphics/matrix-copy/build.sh "$atl_build" "$output/matrix"
tests/graphics/matrix-copy/run.sh "$atl_build" "$output/matrix/matrix-copy-tests.apk" "$output/matrix-run"
tests/mobile-smoke/build.sh "$atl_build" "$output/smoke"
tests/mobile-smoke/run.sh "$atl_build" "$output/smoke/mobile-smoke.apk" "$output/smoke-run"
sh "$atl_workspace/bionic_translation/tests/mobile/run.sh"
sh "$atl_workspace/art_standalone/tests/mobile/run.sh"
