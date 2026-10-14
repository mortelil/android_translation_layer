#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Build the AudioTrack contract probe, run it on ATL, and prove that the 1 kHz
# tone it emits reached the real output sink by recording that sink's monitor.
#
# Usage: run.sh [OUT_DIR] [sink|nosink]
#   sink   (default) a real device is present: the recording of the sink monitor
#          must contain the 1 kHz tone the probe wrote.
#   nosink ATL_AUDIO_PCM_DEVICE points at a missing device: the probe must report
#          an uninitialized track and refuse writes, and NO tone may appear on
#          the sink (negative control for the capture/analysis itself).
# Exit: 0 only when both the probe contract and the sink expectation hold.
set -u
LANE=/home/morten/ATL/parallel-apps-ab/vlc
REPO=$LANE/repo
WRAP=/home/morten/.omp/profiles/atl-parallel-ab/agent/resources.py
RUNNER=/home/morten/ATL/parallel-apps/tools/run-atl-probe-lane.sh
OUT=${1:-$LANE/logs/audio-probe-$(date +%Y%m%d-%H%M%S)}
MODE=${2:-sink}
mkdir -p "$OUT"
rc=0

echo "== [1/5] build probe =="
python3 "$WRAP" --team vlc --kind build --timeout 900 -- \
  toolbox run --container atl_dev sh -lc \
    "ATL_WORKSPACE=$LANE/env ATL_PREFIX=$LANE/prefix ATL_BUILD_DIR=$REPO/build-mobile \
     sh $REPO/tests/audio-probe/build.sh $REPO/build-mobile $OUT/build" \
  > "$OUT/build.log" 2>&1 || { echo "BUILD FAILED"; tail -20 "$OUT/build.log"; exit 1; }
APK="$OUT/build/audio-probe.apk"
[ -s "$APK" ] || { echo "no APK produced"; exit 1; }
echo "apk=$APK"

echo "== [2/5] find sink monitor =="
SINK=$(toolbox run --container atl_dev sh -lc "pactl list short sinks 2>/dev/null | head -1 | cut -f2")
[ -n "$SINK" ] || { echo "no pulse sink found in atl_dev"; exit 1; }
echo "sink=$SINK monitor=$SINK.monitor"

echo "== [3/5] start sink monitor capture =="
toolbox run --container atl_dev sh -lc \
  "timeout 40 parec --device=$SINK.monitor --format=s16le --rate=48000 --channels=2 > $OUT/sinkmon.raw 2>$OUT/sinkmon.err" &
CAP=$!
sleep 2

echo "== [4/5] run probe under ATL =="
if [ "$MODE" = "nosink" ]; then
    export ATL_AUDIO_PCM_DEVICE=${ATL_AUDIO_PCM_DEVICE:-atl-no-such-device}
    export ATL_PROBE_ENV="ATL_AUDIO_PCM_DEVICE${ATL_PROBE_ENV:+,$ATL_PROBE_ENV}"
    echo "nosink mode: ATL_AUDIO_PCM_DEVICE=$ATL_AUDIO_PCM_DEVICE"
fi
python3 "$WRAP" --team vlc --kind atl --timeout 300 -- \
  env ATL_LANE_ROOT=/home/morten/ATL/parallel-apps-ab ATL_LANE=vlc \
      sh "$RUNNER" "$APK" "$OUT/atl" AUDIO_PROBE_DONE 120 probe \
  > "$OUT/run.log" 2>&1 || rc=$?
cat "$OUT/run.log"
wait "$CAP" 2>/dev/null
echo "capture bytes: $(wc -c < "$OUT/sinkmon.raw" 2>/dev/null || echo 0)"

echo "== [5/5] analyse captured sink PCM =="
if [ "$MODE" = "nosink" ]; then
    # no tone is expected: the analysis must FAIL, which is the negative control
    python3 "$REPO/tests/audio-probe/analyze-sink.py" "$OUT/sinkmon.raw" "$OUT/analysis.txt" 48000 2 || true
else
    python3 "$REPO/tests/audio-probe/analyze-sink.py" "$OUT/sinkmon.raw" "$OUT/analysis.txt" 48000 2 || rc=1
fi

echo "== probe assertions =="
grep -h -- "ASSERT\|AUDIO_PROBE_DONE\|RESULT failures" "$OUT"/atl/stdout.log "$OUT/run.log" 2>/dev/null | sort -u | tail -40
FAILS=$(grep -h -o "RESULT failures=[0-9]*" "$OUT"/atl/stdout.log "$OUT/run.log" 2>/dev/null | head -1)
echo "probe $FAILS ; runner rc=$rc ; out=$OUT"
case "$FAILS" in
  "RESULT failures=0") : ;;
  *) rc=1 ;;
esac
if [ "$MODE" = "nosink" ]; then
    grep -q "AUDIO_PROBE_DONE failures=0 sink=no" "$OUT"/atl/stdout.log || rc=1
    grep -q "SINK_TONE_VERDICT: FAIL" "$OUT/analysis.txt" || { echo "tone leaked with no device!"; rc=1; }
else
    grep -q "SINK_TONE_VERDICT: PASS" "$OUT/analysis.txt" || rc=1
    grep -q "AUDIO_PROBE_DONE failures=0 sink=yes" "$OUT"/atl/stdout.log || rc=1
fi
echo "OVERALL: $([ $rc -eq 0 ] && echo PASS || echo FAIL) (out=$OUT)"
exit $rc