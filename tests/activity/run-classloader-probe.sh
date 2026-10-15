#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# P11: rerun the Firefox classloader probe (5418fb3) as the base app under THIS
# lane's ATL candidate, with the exact Firefox fixture APK attached through the
# documented lane-local opaque hardlink (P11/classloader-reference-r3 method).
# Nothing in the Firefox repo is modified; the probe sources/verifier are used
# read-only and the hardlink is created inside <OUT>.
#   usage: run-classloader-probe.sh <OUTDIR>
set -u
OUT=${1:?usage: run-classloader-probe.sh <OUTDIR>}
# Lane root / Firefox reference tree / host runner are overridable so the
# combined branch can rerun this script from its own worktree.
LANE=${ATL_LANE_DIR:-/home/morten/ATL/lifecycle-foundation/activity}
FR=${FIREFOX_REPO:-/home/morten/ATL/parallel-apps-ab/firefox}
APK=$FR/data/classloader-probe/build3/clprobe-v1.apk
FFAPK=$FR/fixtures/apk/firefox.apk
RUNNER=${ATL_PROBE_RUNNER:-/home/morten/ATL/parallel-apps/tools/run-atl-probe-lane.sh}
mkdir -p "$OUT"; OUT=$(realpath "$OUT")
HARDLINK=$OUT/firefox-extra-opaque
rm -f "$HARDLINK"; ln "$FFAPK" "$HARDLINK" || { echo "hardlink failed"; exit 2; }
stat -c 'inode=%i links=%h size=%s %n' "$FFAPK" "$HARDLINK" | tee "$OUT/hardlink-inode.txt"
sha256sum "$FFAPK" "$HARDLINK" | tee "$OUT/hardlink-sha256.txt"
sha256sum "$APK" "$FFAPK" | tee "$OUT/apk.sha256"
sha256sum "$LANE/repo/build-mobile/android-translation-layer" > "$OUT/atl-binary.sha256"
cd "$LANE/repo" && git rev-parse HEAD > "$OUT/lane-head.txt"
rm -f "$OUT/app-results.json"
ATL_LANE_ROOT=${ATL_LANE_ROOT:-/home/morten/ATL/lifecycle-foundation} ATL_LANE=${ATL_LANE:-activity} \
ANDROID_APP_DATA_DIR=$OUT/data XDG_CACHE_HOME=$OUT/data/cache \
ATL_PROBE_EXTRA_APKS=$HARDLINK \
	python3 "${ATL_RESOURCES:-/home/morten/.omp/profiles/atl-lifecycle/agent/resources.py}" --team activity --kind atl --timeout 600 -- \
	sh "$RUNNER" "$APK" "$OUT" CLPROBE_JSON_END 300 probe > "$OUT/runner.log" 2>&1
echo "runner rc=$?"
tail -3 "$OUT/runner.log"
[ -s "$OUT/app-results.json" ] || { echo "NO RESULTS FILE"; exit 1; }
cp -f "$OUT/app-results.json" "$OUT/results.json" 2>/dev/null
sha256sum "$OUT/app-results.json" | tee "$OUT/results.sha256"
python3 "$FR/repo/tests/classloader-probe/verify_classloader.py" "$OUT/app-results.json" \
	--platform atl --expect-loader-source own-classloader > "$OUT/verifier.log" 2>&1
echo "verifier rc=$?"
tail -6 "$OUT/verifier.log"
