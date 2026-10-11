#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# run-case.sh - single harness entry point for JNI-EXC-001 probe scenarios.
#
# Usage:
#   tests/jni-exceptions/run-case.sh <A|B|C|D|E|F|G> <OUT_DIR>
#
# It launches the frozen probe APK on ATL through the shared, bounded runner
# (tools/run-atl-probe.sh: marker completion, never process exit; a
# terminal-failure child exits/crashes), collects stdout/stderr, seals them with
# a completion trailer, then runs the fail-closed analyzer verify_jni_exc.py.
#
# Exit codes:
#   0  analyzer PASS
#   1  analyzer REJECT (contract violation; baseline A is expected here)
#   2  usage / internal error
#
# ATL under test (same APK, identical inputs on both sides):
#   ATL_PROBE_SOURCE_DIR / ATL_PROBE_BUILD_DIR   honoured (passed through to the
#       runner). Default to the preserved frozen baseline when unset.
#   JNIEXC_APK_DIR   directory holding jniexc-001-<a|b|c>.apk
#                    (default /home/morten/ATL/agent-work/JNI-EXCEPTIONS/JNI-EXC-001/apk)
#   JNIEXC_TIMEOUT   bounded wait seconds (default 300)
#   JNIEXC_RUNNER    run-atl-probe.sh path (default /home/morten/ATL/tools/run-atl-probe.sh)
set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERIFY="$HERE/verify_jni_exc.py"
ANALYZER_PY=${PYTHON:-python3}

if [ "$#" -ne 2 ]; then
    echo "run-case.sh: usage: run-case.sh <A|B|C|D|E|F|G> <OUT_DIR>" >&2
    exit 2
fi

CASE=$1
OUT_DIR=$2

case "$CASE" in
    A|B|C|D|E|F|G) ;;
    *) echo "run-case.sh: unknown case '$CASE' (want A through G)" >&2; exit 2 ;;
esac
[ -n "$OUT_DIR" ] || { echo "run-case.sh: OUT_DIR is empty" >&2; exit 2; }

LOWER=$(printf '%s' "$CASE" | tr 'A-Z' 'a-z')
APK_DIR=${JNIEXC_APK_DIR:-/home/morten/ATL/agent-work/JNI-EXCEPTIONS/JNI-EXC-001/apk}
APK="$APK_DIR/jniexc-001-$LOWER.apk"
RUNNER=${JNIEXC_RUNNER:-/home/morten/ATL/tools/run-atl-probe.sh}
TIMEOUT=${JNIEXC_TIMEOUT:-300}

[ -f "$APK" ] || { echo "run-case.sh: APK not found: $APK" >&2; exit 2; }
[ -x "$RUNNER" ] || { echo "run-case.sh: runner not executable: $RUNNER" >&2; exit 2; }

# Baseline defaults (frozen base SHA build), overridable by the caller.
: "${ATL_PROBE_SOURCE_DIR:=/home/morten/ATL/jni-exceptions}"
: "${ATL_PROBE_BUILD_DIR:=/home/morten/ATL/agent-work/JNI-EXCEPTIONS/baseline/build-mobile}"
export ATL_PROBE_SOURCE_DIR ATL_PROBE_BUILD_DIR

case "$CASE" in
    A|B|E|F|G) MARKER=JNIEXC001_JSON_END ;;
    C)   MARKER=JNIEXC001_POS_DONE ;;
    D) MARKER=JNIEXC_D_DONE ;;
esac

[ ! -e "$OUT_DIR" ] || { echo "refusing stale output directory" >&2; exit 2; }
mkdir -p "$OUT_DIR" || { echo "run-case.sh: cannot create $OUT_DIR" >&2; exit 2; }
OUT_DIR=$(cd "$OUT_DIR" && pwd)

RUN_ID="jniexc001-${LOWER}-$(date +%Y%m%dT%H%M%S)-$$"
ATL_DIR="$OUT_DIR/atl"
mkdir -p "$ATL_DIR"

echo "== JNI-EXC-001 case $CASE =="
echo "run_id=$RUN_ID"
echo "apk=$APK"
echo "atl_source_dir=$ATL_PROBE_SOURCE_DIR"
echo "atl_build_dir=$ATL_PROBE_BUILD_DIR"
echo "marker=$MARKER timeout=${TIMEOUT}s"

"$RUNNER" "$APK" "$ATL_DIR" "$MARKER" "$TIMEOUT"
RUNNER_EXIT=$?
echo "runner_exit=$RUNNER_EXIT"

# Hash the implementation, not just the launcher. Loaded origin must also be
# corroborated with runner maps / the ART classpath in stderr.
sha256sum "$ATL_PROBE_BUILD_DIR/android-translation-layer" \
    "$ATL_PROBE_BUILD_DIR/libtranslation_layer_main.so" \
    "$ATL_PROBE_BUILD_DIR/api-impl.jar" > "$OUT_DIR/runtime-sha256.txt" || exit 2

# Collect the captured streams into the run directory (deterministic order).
: > "$OUT_DIR/stdout.log"
: > "$OUT_DIR/stderr.log"
[ -f "$ATL_DIR/stdout.log" ] && cp -f "$ATL_DIR/stdout.log" "$OUT_DIR/stdout.log"
[ -f "$ATL_DIR/stderr.log" ] && cp -f "$ATL_DIR/stderr.log" "$OUT_DIR/stderr.log"

{
    cat "$OUT_DIR/stdout.log"
    printf '\n'
    cat "$OUT_DIR/stderr.log"
    printf '\nJNIEXC001_RUN_COMPLETE run_id=%s runner_exit=%d\n' "$RUN_ID" "$RUNNER_EXIT"
} > "$OUT_DIR/combined.log"

if [ -f "$ATL_DIR/stdout.log" ]; then
    grep -E '^ATL-PROBE STATUS=' "$ATL_DIR/stdout.log" | tail -1 > "$OUT_DIR/runner.txt" || true
fi

APK_SHA=$(sha256sum "$APK" | cut -d' ' -f1)
ATL_BIN_SHA=$(grep -m1 -E '^[0-9a-f]{64}  ' "$ATL_DIR/atl-binary.txt" 2>/dev/null | cut -d' ' -f1)
[ -n "$ATL_BIN_SHA" ] || ATL_BIN_SHA="unknown"

RUNNER_STATUS=$(cat "$OUT_DIR/runner.txt" 2>/dev/null || true)

"$ANALYZER_PY" - "$OUT_DIR/run-meta.json" \
    "$RUN_ID" "$CASE" "$APK" "$APK_SHA" "$ATL_BIN_SHA" "$RUNNER_EXIT" "$RUNNER_STATUS" <<'PYEOF'
import json, sys
path, run_id, case, apk, apk_sha, atl_bin_sha, runner_exit, status = sys.argv[1:9]
meta = {
    "task_id": "JNI-EXC-001",
    "case": case,
    "run_id": run_id,
    "apk": apk,
    "apk_sha256": apk_sha,
    "atl_binary_sha256": atl_bin_sha,
    "runner_exit": int(runner_exit),
    "runner_status": status,
}
with open(path, "w", encoding="utf-8") as fh:
    json.dump(meta, fh, indent=2, sort_keys=True)
    fh.write("\n")
PYEOF

"$ANALYZER_PY" "$VERIFY" "$OUT_DIR" > "$OUT_DIR/analysis.txt" 2>&1
ANALYZER_EXIT=$?
cat "$OUT_DIR/analysis.txt"

if [ "$ANALYZER_EXIT" -eq 0 ]; then
    echo "JNI-EXC-001 case $CASE: PASS (run_id=$RUN_ID)"
else
    echo "JNI-EXC-001 case $CASE: FAIL (analyzer exit $ANALYZER_EXIT, run_id=$RUN_ID)"
fi
exit "$ANALYZER_EXIT"
