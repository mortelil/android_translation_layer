#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Runs every provider-publication row plus the original cross-package suite,
# one at a time, each in its own disposable profile and private data dir.
# Usage: run-all.sh <outdir>
set -eu
here=$(dirname -- "$0")
out=$(realpath -m "$1")
failures=0
# run <row> [VAR=value ...]: extra assignments go to the app's environment.
run() {
	row=$1
	shift
	echo "=== row $row"
	if env ATL_PROVIDER_ROW=$row "$@" sh "$here/run.sh" "$out/$row"; then :; else
		echo "ROW FAILED: $row"
		failures=$((failures + 1))
	fi
}
run legacy
run init-order
run oncreate-false
run ctor-throw
run attach-throw
run oncreate-throw
run reentrant
run retry-after-failure
run single-init ATL_LATCH_SLEEP_MS=1200
run timeout ATL_LATCH_SLEEP_MS=3000 ATL_PROVIDER_READY_TIMEOUT_MS=250
run interrupted-waiter ATL_LATCH_SLEEP_MS=1200 ATL_LATCH_INTERRUPT_PROBE=1
if [ "$failures" -ne 0 ]; then
	echo "PROVIDER_ROWS_FAILED $failures"
	exit 1
fi
echo PROVIDER_ROWS_PASS
