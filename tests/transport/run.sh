#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# TRN-011/TRN-014 transport battery runner (HOST side).
#
# Wraps the shared lane probe runner so the battery runs exactly like the
# other lane evidence: this lane's repo/build-mobile/prefix, dbus session +
# Xvfb through the loader's own run path, logs captured under the lane
# evidence directory, results.json collected from the app files dir.
#
#   /home/morten/ATL/lifecycle-foundation/transport/repo/tests/transport/run.sh \
#     /path/to/transport-probe-aligned.apk [EVIDENCE_DIR]
#
# Environment:
#   ATL_IPC_TIMEOUT_MS  forwarded into the app (deadline of every wait);
#                       default 30000. The T09 timeout case always uses
#                       deadlineMs + 3s as its server delay, so any value
#                       >= 1000 works.
set -eu
if [ "$#" -lt 1 ]; then
	printf 'Usage: %s APK [EVIDENCE_DIR]\n' "$0" >&2
	exit 2
fi
apk=$(realpath "$1")
lane_root=${ATL_LANE_ROOT:-/home/morten/ATL/lifecycle-foundation}
evidence=${2:-$lane_root/transport}
mkdir -p "$evidence" "$lane_root/transport/data/apps" "$lane_root/transport/data/cache"
: "${ATL_IPC_TIMEOUT_MS:=30000}"
export ATL_IPC_TIMEOUT_MS
export ATL_LANE_ROOT="$lane_root"
export ATL_LANE=transport
export ATL_PROBE_ENV=ATL_IPC_TIMEOUT_MS
exec /home/morten/ATL/parallel-apps/tools/run-atl-probe-lane.sh \
	"$apk" "$evidence" 'TRANSPORT BATTERY PASSED' 900
