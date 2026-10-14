#!/bin/sh
# Host-side scenario driver. Run it under resources.py --kind atl; each mode
# launches a separate ATL process through the lane probe runner.
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <signed-probe.apk> <output-root>" >&2
    exit 64
fi
APK=$1
OUTPUT_BASE=$2
RUN_ID=$(date +%Y%m%dT%H%M%S)-$$
OUT_ROOT="$OUTPUT_BASE/$RUN_ID"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LANE_ROOT=${ATL_LANE_ROOT:-/home/morten/ATL/parallel-apps-ab}
LANE=osmand
RUNNER=/home/morten/ATL/parallel-apps/tools/run-atl-probe-lane.sh
APP_DATA="$LANE_ROOT/$LANE/data/apps"
STORE="$APP_DATA/$(basename "$APK")_/files/shortcuts.bin.org.atl.shortcutmanager.bin"

[ -f "$APK" ] || { echo "Missing APK: $APK" >&2; exit 66; }
[ -x "$RUNNER" ] || { echo "Missing runner: $RUNNER" >&2; exit 66; }
mkdir -p "$OUT_ROOT"

run_probe() {
    label=$1
    mode=$2
    extra=$3
    marker=$4
    out="$OUT_ROOT/$label"
    mkdir -p "$out"
    args="-e mode=$mode"
    [ -n "$extra" ] && args="$args $extra"
    env ATL_LANE_ROOT="$LANE_ROOT" ATL_LANE="$LANE" \
        ATL_PROBE_EXTRA_ARGS="$args" \
        sh "$RUNNER" "$APK" "$out" "$marker" 180 probe \
        >"$out/runner.log" 2>&1
}

assert_pass() {
    label=$1
    out="$OUT_ROOT/$label"
    python3 -c 'import json,sys; p=sys.argv[1]; d=json.load(open(p)); assert d.get("status")=="PASS", (p,d); print(p+" "+json.dumps(d,sort_keys=True))' \
        "$out/app-results.json"
    grep -q 'ATL-PROBE STATUS=OK exit=0' "$out/runner.log"
}

wait_for_log() {
    label=$1
    expression=$2
    limit=$3
    logfile="$OUT_ROOT/$label/stdout.log"
    i=0
    while [ "$i" -lt "$limit" ]; do
        if grep -E -m1 "$expression" "$logfile" >/dev/null 2>&1; then
            return 0
        fi
        i=$((i + 1))
        sleep 1
    done
    echo "Timed out waiting for /$expression/ in $logfile" >&2
    return 1
}

app_pid_from_log() {
    label=$1
    expression=$2
    line=$(grep -E -m1 "$expression" "$OUT_ROOT/$label/stdout.log")
    pid=$(printf '%s\n' "$line" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
    [ -n "$pid" ] || { echo "No app pid in: $line" >&2; return 1; }
    printf '%s\n' "$pid"
}

start_killed_mode() {
    label=$1
    mode=$2
    extra=$3
    expression=$4
    out="$OUT_ROOT/$label"
    mkdir -p "$out"
    env ATL_LANE_ROOT="$LANE_ROOT" ATL_LANE="$LANE" \
        ATL_PROBE_EXTRA_ARGS="-e mode=$mode $extra" \
        sh "$RUNNER" "$APK" "$out" NEVER_EMIT_THIS_MARKER 180 probe \
        >"$out/runner.log" 2>&1 &
    runner_pid=$!
    if ! wait_for_log "$label" "$expression" 60; then
        kill "$runner_pid" 2>/dev/null || true
        return 1
    fi
    app_pid=$(app_pid_from_log "$label" "$expression")
    # The shared runner needs one second of startup liveness before it begins
    # marker polling. Don't make an intentional kill look like an early launch
    # failure by killing immediately when the app prints its ready marker.
    sleep 2
}

kill_and_expect_exit3() {
    runner_pid=$1
    app_pid=$2
    echo "$KILL_LABEL SIGKILL target app_pid=$app_pid runner_pid=$runner_pid"
    kill -9 "$app_pid"
    status=0
    wait "$runner_pid" || status=$?
    if [ "$status" -ne 3 ] || ! grep -q 'ATL-PROBE STATUS=EXITED_BEFORE_MARKER exit=3' "$OUT_ROOT/$KILL_LABEL/runner.log"; then
        echo "Expected a deliberate app SIGKILL and runner exit 3; status=$status" >&2
        cat "$OUT_ROOT/$KILL_LABEL/runner.log" >&2
        return 1
    fi
    echo "$KILL_LABEL deliberate SIGKILL pid=$app_pid; runner exit=3"
}

# Baseline public API + package isolation + store corruption/failed-write cases.
run_probe checks checks "" SHORTCUT_MANAGER
assert_pass checks

# Concurrent cross-process read/modify/write; both processes must finish before
# verify checks that all 24 distinct updates survived.
run_probe peer-a peer "-e peer-tag=A -e peer-count=12" SHORTCUT_MANAGER &
peer_a=$!
run_probe peer-b peer "-e peer-tag=B -e peer-count=12" SHORTCUT_MANAGER &
peer_b=$!
wait "$peer_a"
wait "$peer_b"
assert_pass peer-a
assert_pass peer-b
run_probe verify-peers verify "-e peer-count=12" SHORTCUT_MANAGER
assert_pass verify-peers

# An owner holds the stable lock for 45s; two independent contenders start only
# after acquisition, and inspect lock exclusion after 33s (> the old 30s cutoff).
run_probe hold-45 hold "-e hold-seconds=45" 'HOLD RELEASED' &
hold_runner=$!
wait_for_log hold-45 'SHORTCUT_MANAGER HOLD READY' 30
sleep 1
run_probe contender-a contend "-e hold-seconds=35 -e contender-tag=1" SHORTCUT_MANAGER &
contender_a=$!
run_probe contender-b contend "-e hold-seconds=35 -e contender-tag=2" SHORTCUT_MANAGER &
contender_b=$!
wait "$hold_runner"
wait "$contender_a"
wait "$contender_b"
assert_pass hold-45
assert_pass contender-a
assert_pass contender-b

# A holder killed while holding the lock must be released by the kernel without
# deleting/replacing its stable lock inode.
KILL_LABEL=hold-killed
start_killed_mode "$KILL_LABEL" hold "-e hold-seconds=90" 'SHORTCUT_MANAGER HOLD READY'
kill_and_expect_exit3 "$runner_pid" "$app_pid"
run_probe recover recover "" SHORTCUT_MANAGER
assert_pass recover

# Kill a process while it repeatedly commits large replacements, then verify
# the versioned store is complete and the next commit is immediate.
KILL_LABEL=crashloop-killed
start_killed_mode "$KILL_LABEL" crashloop "" 'SHORTCUT_MANAGER CRASH_LOOP'
sleep 2
kill_and_expect_exit3 "$runner_pid" "$app_pid"
run_probe verify-store verify-store "" SHORTCUT_MANAGER
assert_pass verify-store

# Seed the exact checked-in v2 fixture, then migrate and compare every encoded
# field again from a fresh process after migration.
cp "$SCRIPT_DIR/fixtures/previous-v2-14.bin" "$STORE"
rm -f "$STORE.corrupt"
run_probe migrate migrate "" SHORTCUT_MANAGER
assert_pass migrate
run_probe verify-migrated verify-migrated "" SHORTCUT_MANAGER
assert_pass verify-migrated

echo "SHORTCUT_SCENARIOS PASS artifacts=$OUT_ROOT"
