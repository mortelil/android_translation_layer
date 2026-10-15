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

# ATL_PROBE_WM=<wm> runs a window manager inside the Xvfb session. Production
# ATL always has one; Xvfb alone has none, which changes WM close semantics.
WM_PRE=""
[ -n "${ATL_PROBE_WM:-}" ] && WM_PRE="$ATL_PROBE_WM & sleep 2; "
INNER="cd $REPO; export ATL_WORKSPACE=$LANE/env ATL_PREFIX=$LANE/prefix ATL_BUILD_DIR=$REPO/build-mobile \
ANDROID_APP_DATA_DIR=$DATA XDG_CACHE_HOME=$DATA/.cache; ulimit -c 0; \
dbus-run-session -- xvfb-run -a -s '-screen 0 1280x1024x24' sh -c '${WM_PRE}sh scripts/mobile/run.sh $APK'"

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
# Liveness after the marker: the probe is done and the run waits for the
# external close (S3D2 is externally driven), so the app must still be alive.
# Match this run's APK too: other lanes leave ATL processes alive in the shared
# container, so a bare binary pattern would report their liveness.
_apkbn=$(basename "$APK")
_live=$(podman exec atl_dev sh -c "pgrep -f \"[a]ndroid-translation-layer.*$_apkbn\"" 2>/dev/null | tr '\n' ' ' || true)
echo "ATL_PROBE_LIVENESS after_marker=${i}s alive=${_live:-no}"
# Optional external close (A04 S3D2 strict variant): attach the WM_DELETE_WINDOW
# helper to the RUNNING ATL window. DISPLAY/XAUTHORITY are read out of the live
# ATL process environ and fed to the helper through a real script, because a
# second shell that guesses the Xvfb display fails X auth.
# Source-available WM-protocol sender (tests/activity/x11-wm-close.c): the older
# binary helper in the evidence dir had no source and its semantics could not be
# re-checked, so the runner builds this one into the lane's private probe dir.
CLOSE_TOOL=${ATL_PROBE_CLOSE_HELPER:-$LANE/probe-build/x11-wm-close}
if [ "${ATL_PROBE_EXTERNAL_CLOSE:-no}" = yes ]; then
	mkdir -p "$LANE/probe-build"
	podman exec atl_dev sh -c "cc -O0 -o $CLOSE_TOOL $REPO/tests/activity/x11-wm-close.c -lX11" \
		|| echo "ATL_PROBE_EXTERNAL_CLOSE warning: could not build $CLOSE_TOOL"
	# The pattern must select THIS run's ATL process: other lanes leave their own
	# ATL processes (and live Xvfb sessions) in the shared container, and a bare
	# binary pattern picked the first of those, which sent the close to the wrong
	# display (observed 2026-10-15: closed an org.atl.tests.audio window while
	# this lane's app never saw the message).
	cat > "$OUT/atl-x-env.sh" <<'EOS'
p=$(pgrep -f "$1" | head -1)
[ -n "$p" ] || exit 3
tr '\0' '\n' < "/proc/$p/environ" | grep -E '^(DISPLAY|XAUTHORITY)='
EOS
	podman exec atl_dev sh "$OUT/atl-x-env.sh" "[a]ndroid-translation-layer.*$_apkbn" > "$OUT/atl-x-env.txt" 2>/dev/null || true
	echo "ATL_PROBE_EXTERNAL_CLOSE env: $(tr '\n' ' ' < "$OUT/atl-x-env.txt")"
	if grep -q '^DISPLAY=' "$OUT/atl-x-env.txt"; then
		{
			# export, not a bare assignment: a bare VAR=value sets only a
			# shell variable, so the helper child would still see it unset.
			sed 's/^/export /' "$OUT/atl-x-env.txt"
			echo "echo ATL_X_ENV DISPLAY=\$DISPLAY XAUTHORITY=\$XAUTHORITY"
			echo "exec $CLOSE_TOOL"
		} > "$OUT/atl-x-close.sh"
		podman exec atl_dev sh "$OUT/atl-x-close.sh" > "$OUT/close.log" 2>&1
		echo "ATL_PROBE_EXTERNAL_CLOSE helper rc=$? out=$(tr '\n' ' ' < "$OUT/close.log")"
		t=0
		while [ "$t" -lt 20 ]; do
			# [a] trick: the pattern cannot match this exec'd shell's own argv.
			podman exec atl_dev sh -c "pgrep -f \"[a]ndroid-translation-layer.*$_apkbn\" >/dev/null" 2>/dev/null || break
			t=$((t + 1)); sleep 1
		done
		echo "ATL_PROBE_EXTERNAL_CLOSE atl_exited_after=${t}s"
	else
		echo "ATL_PROBE_EXTERNAL_CLOSE could not read DISPLAY/XAUTHORITY - no external close sent"
	fi
fi
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
