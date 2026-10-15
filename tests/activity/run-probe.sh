#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Lane-C activity seam probe runner (host side, bounded).
#
#   run-probe.sh <APK> <EVIDENCE_DIR> <MARKER> <TIMEOUT_SECONDS>
#
# Launches the lane ATL build exactly like the A04 run (toolbox atl_dev with
# the lane ATL_WORKSPACE/ATL_PREFIX/ATL_BUILD_DIR, a lane-private
# ANDROID_APP_DATA_DIR, dbus-run-session + xvfb-run), polls the captured stdout
# for the probe's terminal marker, then stops ONLY this launch (by APK path) and
# copies the app-written artifacts out of the container-shared data dir.
set -u
APK=$1; OUT=$2; MARKER=$3; TIMEOUT=$4
# Lane root / resource wrapper are overridable so the combined branch can run
# the same script from its own worktree without editing this file.
LANE=${ATL_LANE_DIR:-/home/morten/ATL/lifecycle-foundation/activity}
REPO=${ATL_LANE_REPO:-$LANE/repo}
RESOURCES=${ATL_RESOURCES:-/home/morten/.omp/profiles/atl-lifecycle/agent/resources.py}
DATA=$OUT/data
run_atl() { python3 "$RESOURCES" --team activity --kind atl "$@"; }
mkdir -p "$OUT" "$DATA"
rm -f "$OUT/atl-stdout.log"

INNER="cd $REPO; export ATL_WORKSPACE=$LANE/env ATL_PREFIX=$LANE/prefix ATL_BUILD_DIR=$REPO/build-mobile \
ANDROID_APP_DATA_DIR=$DATA XDG_CACHE_HOME=$DATA/.cache; ulimit -c 0; \
dbus-run-session -- xvfb-run -a -s '-screen 0 1280x1024x24' sh scripts/mobile/run.sh $APK"

run_atl --timeout $((TIMEOUT + 60)) -- toolbox run --container atl_dev sh -lc "$INNER" \
	> "$OUT/atl-stdout.log" 2>&1 &
WRAPPER=$!

found=no
i=0
while [ "$i" -lt "$TIMEOUT" ]; do
	if grep -q "$MARKER" "$OUT/atl-stdout.log" 2>/dev/null; then found=yes; break; fi
	kill -0 "$WRAPPER" 2>/dev/null || break
	i=$((i + 1)); sleep 1
done

sleep 3
podman exec atl_dev sh -c "pkill -f '$APK'" >/dev/null 2>&1 || true
sleep 1
kill -TERM "$WRAPPER" 2>/dev/null || true
sleep 3
kill -KILL "$WRAPPER" 2>/dev/null || true

for f in aprobe-trace.log results.json trace.log; do
	src=$(ls "$DATA"/*_/files/"$f" 2>/dev/null | head -1)
	[ -n "$src" ] && cp -f "$src" "$OUT/$f" && echo "collected $f from $src"
done
if [ "$found" = yes ]; then
	echo "PROBE_MARKER_FOUND $MARKER after ${i}s"
else
	echo "PROBE_MARKER_MISSING $MARKER after ${i}s"; exit 2
fi
