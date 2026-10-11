#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# run-sdk-atl.sh - host-side ATL runner for the D-SDK probe APK.
#
# The shared tools/run-atl-probe.sh cannot forward ATL *launcher* arguments, and
# the D-SDK override case needs `--sdk-int 28`. This helper does, while keeping
# the runbook section 8 rules: managed background launch, marker-based
# completion (never process exit), a unique out dir, and cleanup of only this
# invocation's process group.
#
# Usage (HOST):
#   run-sdk-atl.sh <APK_ABS> <OUT_DIR> <RUN_KIND> <SCENARIO> <TIMEOUT> [OVERRIDE_SDK_INT]
#     RUN_KIND   android|atl-default|atl-override  (recorded in the probe JSON)
#     SCENARIO   perm_none|perm_phone_state|default
#     OVERRIDE   when set, ATL is launched with `--sdk-int <value>`
#
# The probe's completion marker is SDKPROBE_JSON_END; results.json is copied to
# <OUT_DIR>/app-results.json. Exit: 0 marker+results, 2 timeout, 3 early exit,
# 1 usage/internal.
set -u
[ "$#" -eq 5 ] || [ "$#" -eq 6 ] || {
    echo "usage: run-sdk-atl.sh <APK_ABS> <OUT_DIR> <RUN_KIND> <SCENARIO> <TIMEOUT> [SDK_INT]" >&2
    exit 1
}
APK=$1; OUT_DIR=$2; RUN_KIND=$3; SCENARIO=$4; TIMEOUT=$5; SDKN=${6:-}
MARKER=SDKPROBE_JSON_END
SOURCE_DIR=${ATL_PROBE_SOURCE_DIR:-$HOME/ATL/android_translation_layer}
BUILD_DIR=${ATL_PROBE_BUILD_DIR:-$SOURCE_DIR/build-mobile}
ATL_BIN=$BUILD_DIR/android-translation-layer
CONTAINER=atl_dev

case "$APK" in /*) ;; *) echo "APK must be absolute" >&2; exit 1 ;; esac
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
mkdir -p "$OUT_DIR"; OUT_DIR=$(realpath "$OUT_DIR")
STDOUT_LOG=$OUT_DIR/stdout.log; STDERR_LOG=$OUT_DIR/stderr.log
APPDATA=$OUT_DIR/appdata; mkdir -p "$APPDATA"
APK_BASE=$(basename "$APK")
RESULTS_SRC="$APPDATA/${APK_BASE}_/files/results.json"

EXTRA=""
[ -n "$SDKN" ] && EXTRA=" --sdk-int $SDKN"

# Each pathological value is embedded literally inside single quotes.
case "$APK$OUT_DIR$RUN_KIND$SCENARIO$SDKN" in *"'"*) echo "single quotes unsupported" >&2; exit 1 ;; esac

{
    echo "atl-binary: $ATL_BIN"
    [ -f "$ATL_BIN" ] && sha256sum "$ATL_BIN"
    sha256sum "$APK"
    echo "run_kind: $RUN_KIND"; echo "scenario: $SCENARIO"
    echo "sdk_int_override: ${SDKN:-none}"; echo "marker: $MARKER"; echo "date: $(date -Is)"
} > "$OUT_DIR/atl-binary.txt"

CONTAINER_CMD="APK='$APK'; OUT_DIR='$OUT_DIR'; RUN_KIND='$RUN_KIND'; SCENARIO='$SCENARIO'; MARKER='$MARKER'; TIMEOUT='$TIMEOUT'; \
. \"\$HOME/ATL/test-env.sh\"; export ATL_BUILD_DIR='$BUILD_DIR'; \
export ANDROID_APP_DATA_DIR='$APPDATA'; export XDG_CACHE_HOME='$OUT_DIR/cache'; \
export SDKPROBE_RUN_KIND='$RUN_KIND'; export SDKPROBE_SCENARIO='$SCENARIO'; \
mkdir -p \"\$ANDROID_APP_DATA_DIR\" \"\$XDG_CACHE_HOME\"; \
cd '$SOURCE_DIR' || exit 91; \
setsid dbus-run-session -- xvfb-run -a sh scripts/mobile/run.sh \"\$APK\"$EXTRA \
  > \"\$OUT_DIR/stdout.log\" 2> \"\$OUT_DIR/stderr.log\" & LP=\$!; \
START=\$(date +%s); \
while :; do \
  grep -q -- \"\$MARKER\" \"\$OUT_DIR/stdout.log\" 2>/dev/null && { echo MARKER_FOUND; break; }; \
  kill -0 \$LP 2>/dev/null || { echo LAUNCHER_EXITED; break; }; \
  [ \$(( \$(date +%s) - START )) -ge \"\$TIMEOUT\" ] && { echo TIMEOUT; break; }; \
  sleep 2; \
done; \
kill -TERM -\$LP 2>/dev/null || true; sleep 1; kill -KILL -\$LP 2>/dev/null || true"

setsid sh -c 'exec toolbox run --container atl_dev sh -lc "$1"' __sdkatl__ "$CONTAINER_CMD" \
    </dev/null >"$OUT_DIR/host.log" 2>&1 &
HPID=$!
i=0
while [ "$i" -lt "$TIMEOUT" ]; do
    if grep -q MARKER_FOUND "$OUT_DIR/host.log" 2>/dev/null; then break; fi
    if ! kill -0 "$HPID" 2>/dev/null; then break; fi
    i=$((i + 2)); sleep 2
done
if grep -q MARKER_FOUND "$OUT_DIR/host.log" 2>/dev/null; then
    # results.json lands a moment after the marker.
    j=0
    while [ "$j" -lt 20 ]; do [ -s "$RESULTS_SRC" ] && break; j=$((j + 1)); sleep 1; done
    if [ -s "$RESULTS_SRC" ]; then
        cp -f "$RESULTS_SRC" "$OUT_DIR/app-results.json"
        echo "ATL-SDK STATUS=OK exit=0 run_kind=$RUN_KIND scenario=$SCENARIO results=$OUT_DIR/app-results.json"
        exit 0
    fi
    echo "ATL-SDK STATUS=MARKER_NO_RESULTS exit=1"; exit 1
fi
if grep -q TIMEOUT "$OUT_DIR/host.log" 2>/dev/null; then
    echo "ATL-SDK STATUS=TIMEOUT exit=2"; exit 2
fi
echo "ATL-SDK STATUS=EXITED_BEFORE_MARKER exit=3"; exit 3