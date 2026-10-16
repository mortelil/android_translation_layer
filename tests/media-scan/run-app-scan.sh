#!/bin/sh
# Rhythm library-scan probe (lane media).
#
# Drives chromahub.rhythm.app through its onboarding with ATL_UI_SCRIPT so the
# app reaches its library screen and runs its own "Library Optimization"
# metadata pass over ATL_MEDIA_ROOT, then captures screenshots at every step.
#
# usage: run-app-scan.sh <evidence-dir> [script]
#   <evidence-dir>  e.g. /home/morten/ATL/agent-work/COMPAT-FIXES/media/app-scan1
#                   gets media/ (fixtures), data/ (fresh app data) and out/.
#
# Env forwarded into the app: ATL_MEDIA_ROOT (synthetic media root) and
# ATL_DEBUG_MEDIA (ATLMediaIndex prints the MediaStore row counts).
set -eu

LANE=${LANE:-/home/morten/ATL/compat-fixes/lane-media}
APK=${APK:-/home/morten/ATL/compat-fixes/apks/rhythm.apk}
EVID=${1:?usage: run-app-scan.sh <evidence-dir> [ui-script]}
SCRIPT=${2:-'wait:50;shot:01;click:482,462;wait:10;shot:02;click:482,462;wait:10;shot:03;click:482,462;wait:10;shot:04;click:482,462;wait:10;shot:05;click:482,462;wait:15;shot:06;wait:40;shot:07;wait:30;shot:08;wait:30;shot:09'}

mkdir -p "$EVID/media" "$EVID/data" "$EVID/out"
python3 "$LANE/tests/media-scan/fixtures.py" "$EVID/media" --clean

export ATL_PROBE_APP_DATA_DIR="$EVID/data"
export ATL_MEDIA_ROOT="$EVID/media"
export ATL_DEBUG_MEDIA=1
export ATL_PROBE_ENV="ATL_MEDIA_ROOT,ATL_DEBUG_MEDIA"
export ATL_UI_SCRIPT="$SCRIPT"

exec /home/morten/ATL/agent-work/COMPAT-FIXES/bin/run.sh \
    "$LANE" "$LANE/build-mobile" "$APK" "$EVID/out" ATL_UI_SMOKE_DONE 320 ui "$SCRIPT"
