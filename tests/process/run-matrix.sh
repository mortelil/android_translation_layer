#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Lane D PLAN-4 row runner. Runs INSIDE the ATL container (Alpine) with
# ATL_WORKSPACE / ATL_PREFIX / ATL_BUILD_DIR exported by the caller:
#   run-matrix.sh <apk> <out-dir> <row>...
# Reused (attribution): PR #31 baa9b489 tests/remote-services/run.sh
# (timeout + dbus-run-session + xvfb-run + scripts/mobile/run.sh, merged
# capture, private data/cache dirs).
#
# Per row: merged stdout+stderr into <out-dir>/proc-<row>.log, a private
# data+cache dir, and one appended "PROC PASS <row> (runner confirmed ...)"
# line written only after the probe's own part marker(s) and the row's extra
# checks hold. The runner never prints PASS for a row the probe failed.
set -u
test "$#" -ge 3 || { echo "usage: run-matrix.sh <apk> <out-dir> <row>..." >&2; exit 2; }
apk=$(realpath "$1"); shift
out=$(realpath -m "$1"); shift
mkdir -p "$out"
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
failed=0

# run_part <part> <timeout> <log> [extra env assignments...]
# Runs one probe scenario; the scenario prints "PROC PASS <part>" itself.
run_part() {
	part=$1; tmo=$2; log=$3; shift 3
	export ATL_PRO_PROBE="$part"
	export ANDROID_APP_DATA_DIR="$out/data-$part"
	export XDG_CACHE_HOME="$out/cache-$part"
	mkdir -p "$ANDROID_APP_DATA_DIR" "$XDG_CACHE_HOME"
	echo "=== part $part timeout=${tmo}s extra-env=${*:-none}" >> "$log"
	env "$@" timeout "$tmo" dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" >> "$log" 2>&1
	rc=$?
	if [ "$rc" = 0 ] && grep -q "^PROC PASS $part\$" "$log"; then
		echo "part $part: PASS (rc=0) -> $log"
		return 0
	fi
	printf 'PROC FAIL %s (runner: rc=%s, part marker absent)\n' "$part" "$rc" >> "$log"
	echo "part $part: FAIL (rc=$rc) -> $log" >&2
	return 1
}

for row in "$@"; do
	log="$out/proc-$row.log"
	case "$row" in
	P01)
		if run_part P01 120 "$log"; then
			printf 'PROC PASS P01 (runner confirmed: scenario marker present, rc=0)\n' >> "$log"
		else failed=1; fi
		;;
	P02)
		if run_part P02 120 "$log" ATL_SERVICE_PROCESS_FAULT=stall-before-ready:org.atl.procprobe:stallable ATL_SERVICE_PROCESS_READY_TIMEOUT_MS=8000; then
			printf 'PROC PASS P02 (runner confirmed: scenario marker present, rc=0)\n' >> "$log"
		else failed=1; fi
		;;
	P03)
		if run_part P03 120 "$log" ATL_SERVICE_PROCESS_FAULT=stall-before-ready:org.atl.procprobe:second ATL_SERVICE_PROCESS_READY_TIMEOUT_MS=4000 ATL_PROBE_FAKE_READY=1; then
			fake=$(grep -c 'ATL_PROBE_FAKE_READY_WRITTEN' "$log")
			real=$(grep -c '^ATL_SERVICE_PROCESS_READY' "$log")
			gaveup=$(grep -c 'did not report ready within' "$log")
			if [ "$fake" -ge 1 ] && [ "$real" -ge 2 ] && [ "$gaveup" -ge 1 ]; then
				printf 'PROC PASS P03 (runner confirmed: %s fake READY line(s), %s real READY line(s), loader still timed the fake out)\n' "$fake" "$real" >> "$log"
			else
				printf 'PROC FAIL P03 (runner: fake=%s real=%s gaveup=%s)\n' "$fake" "$real" "$gaveup" >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P04)
		: > "$log"
		ok=1
		run_part P04identity 120 "$log" || ok=0
		run_part P04split 90 "$log" ATL_PROBE_EXTRA_APKS=probe-split.apk || ok=0
		if [ "$ok" = 1 ] && grep -q "^PROC PASS P04identity\$" "$log" && grep -q "^PROC PASS P04split\$" "$log"; then
			printf 'PROC PASS P04 (runner confirmed: STATUS identity + split refusal)\n' >> "$log"
		else
			printf 'PROC FAIL P04 (runner: identity/split part missing)\n' >> "$log"
			failed=1
		fi
		;;
	*)
		echo "run-matrix.sh: unknown row $row" >&2
		failed=1
		;;
	esac
done

if [ "$failed" = 0 ]; then
	echo "run-matrix.sh: all requested rows passed"
else
	echo "run-matrix.sh: at least one row failed" >&2
fi
exit "$failed"
