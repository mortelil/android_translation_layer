#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Host driver; requires Toolbox, an external ATL probe runner, and PulseAudio.
# Usage: run.sh OUT_DIR [sink|nosink]
set -eu
REPO=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
: "${ATL_PROBE_RUNNER:?Set ATL_PROBE_RUNNER to run-atl-probe-lane.sh}"
OUT=$(realpath -m "${1:?Specify an isolated output directory}")
MODE=${2:-sink}
case "$MODE" in sink|nosink) ;; *) echo 'Expected sink or nosink' >&2; exit 2;; esac
mkdir -p "$OUT"
export ATL_PROBE_SOURCE_DIR="$REPO"
export ATL_PROBE_BUILD_DIR=${ATL_PROBE_BUILD_DIR:-$REPO/build-mobile}
export ATL_LANE_ROOT="$OUT/data" ATL_LANE=audio
# Companion runtime variables ATL_PROBE_PREFIX/CORE_JAR are passed unchanged.
toolbox run --container "${ATL_CONTAINER:-atl_dev}" env JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
    sh "$REPO/tests/audio-probe/build.sh" "$ATL_PROBE_BUILD_DIR" "$OUT/build" > "$OUT/build.log" 2>&1
APK="$OUT/build/audio-probe.apk"
SINK=atl_probe_$$
MODULE=$(toolbox run --container "${ATL_CONTAINER:-atl_dev}" pactl load-module module-null-sink "sink_name=$SINK")
CAP=
cleanup() {
    if [ -n "$CAP" ]; then kill "$CAP" 2>/dev/null || true; wait "$CAP" 2>/dev/null || true; fi
    toolbox run --container "${ATL_CONTAINER:-atl_dev}" pactl unload-module "$MODULE" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM
# Record only our private output sink, never the microphone or desktop sink.
toolbox run --container "${ATL_CONTAINER:-atl_dev}" timeout 30 parec \
    "--device=$SINK.monitor" --format=s16le --rate=48000 --channels=2 > "$OUT/sink.raw" 2> "$OUT/capture.log" &
CAP=$!
export PULSE_SINK="$SINK"
export ATL_PROBE_ENV="PULSE_SINK${ATL_PROBE_ENV:+,$ATL_PROBE_ENV}"
if [ "$MODE" = nosink ]; then
    export ATL_AUDIO_PCM_DEVICE=atl-no-such-device
    export ATL_PROBE_ENV="$ATL_PROBE_ENV,ATL_AUDIO_PCM_DEVICE"
fi
rc=0
sh "$ATL_PROBE_RUNNER" "$APK" "$OUT/atl" AUDIO_PROBE_DONE 90 probe > "$OUT/run.log" 2>&1 || rc=$?
wait "$CAP" || true
CAP=
cat "$OUT/run.log"
analysis_rc=0
python3 "$REPO/tests/audio-probe/analyze-sink.py" "$OUT/sink.raw" "$OUT/analysis.txt" 48000 2 > "$OUT/analysis.log" || analysis_rc=$?
if [ "$MODE" = sink ]; then
    test "$analysis_rc" = 0
    grep -q 'AUDIO_PROBE_DONE failures=0 sink=yes' "$OUT/atl/stdout.log"
else
    test "$analysis_rc" = 1
    grep -q 'SINK_TONE_VERDICT: FAIL' "$OUT/analysis.txt"
    grep -q 'AUDIO_PROBE_DONE failures=0 sink=no' "$OUT/atl/stdout.log"
fi
test "$rc" = 0
printf 'AUDIO_SUITE PASS mode=%s artifacts=%s\n' "$MODE" "$OUT"
