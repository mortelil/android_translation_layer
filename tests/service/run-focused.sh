#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# Focused run of the child-originated stopSelf contract (acceptance row S09 + S09x)
# without the seven-launch gate.
#
# Why this exists: the full matrix boots seven system_server launches in a row (minutes
# each), which is the right cost for the release gate and the wrong cost for iterating on
# one row. This driver selects exactly the two launches those rows live in:
#
#   fake     - S09: a remote record's stopSelf(int)/stopSelfResult(int) against the fake
#              process host (ATLFakeProcessBackend), where a child can be killed on demand.
#   realhost - S09x: stopSelfResult() with the control link to the policy authority gone,
#              in a really spawned child (ATL_SERVICE_PROCESS_FAULT=stopself-broken-link:s09x,
#              scoped to that row's own process). S09 itself also re-runs there against a
#              real child, which is the same row on the production path.
#
# Same interface as run.sh: BUILD_DIR SIGNED_APK OUT_DIR. Every other row is reported
# NOTCLAIMED (never PASS, never FAIL) and the exit code still means "every claimed row
# passed". For the release evidence use run.sh without SVC_GATE_LAUNCHES.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SVC_GATE_LAUNCHES='fake realhost'
export SVC_GATE_LAUNCHES
exec "$here/run.sh" "$@"
