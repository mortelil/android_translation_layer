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

# last_released_pid <log> <process-name>: pid of the last child of that process
# whose release the child itself logged (the loader closed the link).
last_released_pid() {
	grep -o "ATL_SERVICE_PROCESS_RELEASED pid=[0-9]* process=$2" "$1" | grep -o '[0-9]*' | tail -1
}

# child_exited_with <log> <pid> <status>: the pid's own System.exit evidence line.
child_exited_with() {
	grep -q "($2): System.exit called, status: $3" "$1"
}

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
	# One run's evidence per log file: each row starts from an empty log so a
	# stale failure line from an earlier run can never be mistaken for this run's
	# verdict. P04 appends its two parts to one log and truncates it itself.
	if [ "$row" != "P04" ]; then
		: > "$log"
	fi
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
	P05)
		# Child-side boot order (attach < provider onCreate < Application.onCreate)
		# plus the child's own order assertion staying silent, re-checked here on
		# the same log the probe read (ATL_PROBE_LOG).
		if run_part P05 180 "$log" ATL_PROBE_LOG="$out/proc-P05.log"; then
			order=$(awk -v p="process=org.atl.procprobe:worker" '
				index($0, "ATL_PROBE_APP_ATTACH") && index($0, p) { a = NR }
				index($0, "ATL_PROBE_PROVIDER_ONCREATE") && index($0, p) { b = NR }
				index($0, "ATL_PROBE_APP_ONCREATE") && index($0, p) { c = NR }
				END { printf "%d %d %d", a, b, c }' "$log")
			a=${order%% *}
			b=${order#* }
			b=${b%% *}
			c=${order##* }
			if [ "${a:-0}" -gt 0 ] && [ "${a:-0}" -lt "${b:-0}" ] && [ "${b:-0}" -lt "${c:-0}" ] \
			   && ! grep -q 'ATL_PROBE_CHILD_ORDER_FAIL providers did not run' "$log"; then
				printf 'PROC PASS P05 (runner confirmed: child lines %s < %s < %s, no child-order-failure marker)\n' "$a" "$b" "$c" >> "$log"
			else
				printf 'PROC FAIL P05 (runner: attach=%s provider=%s appOnCreate=%s orderfail=%s)\n' \
					"${a:-0}" "${b:-0}" "${c:-0}" "$(grep -c 'ATL_PROBE_CHILD_ORDER_FAIL providers did not run' "$log")" >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P06)
		# crash-before-ready: the child failed initialization itself; the row log
		# must carry that child-side line.
		if run_part P06 180 "$log" ATL_SERVICE_PROCESS_FAULT=crash-before-ready:org.atl.procprobe:worker ATL_PROBE_LOG="$out/proc-P06.log"; then
			init=$(grep -c 'ATL_SERVICE_PROCESS_INIT_FAILED pid=' "$log")
			if [ "$init" -ge 1 ]; then
				printf 'PROC PASS P06 (runner confirmed: %s child-side ATL_SERVICE_PROCESS_INIT_FAILED line(s), null onBind distinct from host-unavailable)\n' "$init" >> "$log"
			else
				printf 'PROC FAIL P06 (runner: no child-side ATL_SERVICE_PROCESS_INIT_FAILED line)\n' >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P07)
		# Every crashed child logged its own callback failure and exited 10.
		if run_part P07 240 "$log" ATL_PROBE_LOG="$out/proc-P07.log" ATL_PRO_PROBE_WATCHDOG_MS=150000; then
			crashed=$(grep -o 'ATL_SERVICE_PROCESS_CALLBACK_FAILED pid=[0-9]*' "$log" | grep -o '[0-9]*$' | sort -u)
			count=$(printf '%s\n' "$crashed" | grep -c .)
			exits=1
			for pid in $crashed; do
				child_exited_with "$log" "$pid" 10 || exits=0
			done
			if [ "$count" -ge 3 ] && [ "$exits" = 1 ]; then
				printf 'PROC PASS P07 (runner confirmed: %s crashed child pid(s), each logging its own exit 10)\n' "$count" >> "$log"
			else
				printf 'PROC FAIL P07 (runner: crashed children=%s, per-child exit 10=%s)\n' "$count" "$exits" >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P08)
		# The child released after the window closed logged its own exit 0.
		if run_part P08 180 "$log"; then
			child=$(last_released_pid "$log" org.atl.procprobe:worker)
			if [ -n "$child" ] && child_exited_with "$log" "$child" 0; then
				printf 'PROC PASS P08 (runner confirmed: child pid %s exited 0 after the window closed)\n' "$child" >> "$log"
			else
				printf 'PROC FAIL P08 (runner: released pid=%s, no exit-0 line)\n' "${child:-none}" >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P09)
		# The shared child was released by the binding's release (exit 0) and no
		# isolated child was ever spawned.
		if run_part P09 240 "$log" ATL_PROBE_LOG="$out/proc-P09.log" ATL_PRO_PROBE_WATCHDOG_MS=150000; then
			child=$(last_released_pid "$log" org.atl.procprobe:worker)
			isolated=$(grep -c 'process=org.atl.procprobe:isolated' "$log")
			if [ -n "$child" ] && child_exited_with "$log" "$child" 0 && [ "$isolated" = 0 ]; then
				printf 'PROC PASS P09 (runner confirmed: shared child pid %s released and exited 0, no isolated child spawned)\n' "$child" >> "$log"
			else
				printf 'PROC FAIL P09 (runner: released pid=%s, isolated lines=%s)\n' "${child:-none}" "$isolated" >> "$log"
				failed=1
			fi
		else failed=1; fi
		;;
	P10)
		# Two services in one :worker pid while :stallable never gets ready.
		if run_part P10 300 "$log" ATL_SERVICE_PROCESS_FAULT=stall-before-ready:org.atl.procprobe:stallable ATL_SERVICE_PROCESS_READY_TIMEOUT_MS=8000 ATL_PROBE_LOG="$out/proc-P10.log" ATL_PRO_PROBE_WATCHDOG_MS=150000; then
			shared=$(awk -v p="process=org.atl.procprobe:worker" '
				index($0, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker ") && index($0, p) { split($0, f, "pid="); split(f[2], g, " "); a = g[1] }
				index($0, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker2 ") && index($0, p) { split($0, f, "pid="); split(f[2], g, " "); b = g[1] }
				END { if (a != "" && a == b) print a }' "$log")
			stalled=$(grep -c 'stalling before READY in org.atl.procprobe:stallable' "$log")
			ready=$(grep -c 'ATL_SERVICE_PROCESS_READY pid=[0-9]* process=org.atl.procprobe:stallable' "$log")
			gaveup=$(grep -c 'did not report ready within' "$log")
			if [ -n "$shared" ] && [ "$stalled" -ge 1 ] && [ "$ready" = 0 ] && [ "$gaveup" -ge 1 ]; then
				printf 'PROC PASS P10 (runner confirmed: :worker served two services in pid %s, :stallable never got ready (%s stall line(s), %s give-up(s))\n' "$shared" "$stalled" "$gaveup" >> "$log"
			else
				printf 'PROC FAIL P10 (runner: shared pid=%s stall-lines=%s stallable-ready=%s gave-up=%s)\n' "${shared:-none}" "$stalled" "$ready" "$gaveup" >> "$log"
				failed=1
			fi
		else failed=1; fi
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
