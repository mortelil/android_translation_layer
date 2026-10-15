#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Gate 2 service probe driver (rows S01-S15). Run INSIDE the atl_dev Toolbox container
# with ATL_PREFIX/ATL_CORE_JAR configured as for tests/mobile-smoke/run.sh:
#   run.sh BUILD_DIR SIGNED_APK OUT_DIR
# Launches the SAME apk six times (local, fake, caps, fault, ladder, child), each with its own
# environment and out dir, greps the per-scenario "SVC <id> PASS|FAIL" markers from each
# stdout.log and prints ONE summary. Modeled on references/pr31/tests/remote-services/run.sh.
# ATL never self-exits GUI apps: a run is finished when its SVC_GATE_DONE marker shows up
# (or the 300 s timeout expires); the launch group is then killed.
set -eu
test "$#" -eq 3 || { echo 'Usage: run.sh BUILD_DIR SIGNED_APK OUT_DIR' >&2; exit 2; }
build=$(realpath "$1")
apk=$(realpath "$2")
mkdir -p "$3"
out=$(realpath "$3")
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
RUN_TIMEOUT=${SVC_GATE_RUN_TIMEOUT:-300}

# Every launch that runs against the in-loader fake process host (ATLFakeProcessBackend).
COMMON_ENV="ATL_PROCESS_BACKEND=fake"

# Restart-ladder constants (AOSP names, see ATLServiceController ENV_*). MIN_CRASH_INTERVAL_MS is
# 120000 (the AOSP default), NOT 0: with 0 the registry resets the crash count to 1 on every
# death, so no restart escalation and no crash cap could be reached (rows S12, S13c).
RESTART_ENV="ATL_SERVICE_RESTART_DURATION_MS=300 ATL_SERVICE_RESTART_DURATION_FACTOR=4 \
ATL_SERVICE_MIN_RESTART_TIME_BETWEEN_MS=0 ATL_SERVICE_RESET_RUN_DURATION_MS=60000 \
ATL_SERVICE_BOUND_CRASH_RESTART_DURATION_MS=1000 ATL_SERVICE_MIN_CRASH_INTERVAL_MS=120000"
# Row S13a needs the opposite: its kills (~500 ms, ~2000 ms) must count as TWO crash runs, so
# MIN_CRASH_INTERVAL_MS has to sit below the ~1500 ms between them. The launch loop exports the
# values in order, so the later MIN_CRASH_INTERVAL_MS wins. S13b's ack ladder needs the 120000
# default, hence its own launch instead of one shared env.
LADDER_ENV="$RESTART_ENV ATL_SERVICE_MIN_CRASH_INTERVAL_MS=1000"

# name | gate | env (empty = none) | scenario markers expected
launch() {
	name=$1
	gate=$2
	extra=$3
	# The process backend this launch actually runs against, taken from the launch's own env:
	# every row log must name the backend the row ran against.
	backend="none (loader-resident, no child host)"
	case " $extra " in
		*" ATL_PROCESS_BACKEND=fake "*) backend="fake (in-loader ATLFakeProcessBackend)";;
	esac
	case " $extra " in
		*" ATL_SERVICE_PROCESS="*) backend="lane-D host lookup (this loader is a service child)";;
	esac
	dir="$out/run-$name"
	rm -rf "$dir"
	mkdir -p "$dir/data" "$dir/cache"
	# Driver annotation, written before the app starts: it declares the launch's backend and
	# never stands in for an app-emitted marker.
	printf 'SVC_GATE BACKEND %s %s\n' "$name" "$backend" > "$dir/stdout.log"
	echo "SVC_RUN $name env: ATL_SVC_GATE=$gate $extra"
	echo "SVC_GATE BACKEND $name $backend"
	# shellcheck disable=SC2086
	(
		cd "$dir"
		export ATL_BUILD_DIR="$build" ATL_SVC_GATE="$gate"
		export ANDROID_APP_DATA_DIR="$dir/data" XDG_CACHE_HOME="$dir/cache"
		# the three env vars below must be absent unless the run sets them
		unset ATL_PROCESS_BACKEND ATL_SERVICE_PROCESS_FAULT ATL_SERVICE_PROCESS
		unset ATL_SERVICE_BOUND_CRASH_MAX_RETRY
		for kv in $extra; do export "$kv"; done
		exec setsid timeout "$RUN_TIMEOUT" dbus-run-session -- xvfb-run -a sh "$repo/scripts/mobile/run.sh" "$apk" \
			>> "$dir/stdout.log" 2> "$dir/stderr.log"
	) &
	pid=$!
	waited=0
	while kill -0 "$pid" 2>/dev/null; do
		if grep -q "^SVC_GATE_DONE $name\$" "$dir/stdout.log" 2>/dev/null; then
			break
		fi
		sleep 1
		waited=$((waited + 1))
	done
	# kill the whole launch group (setsid made pid the group leader)
	kill -TERM -- "-$pid" 2>/dev/null || true
	sleep 1
	kill -KILL -- "-$pid" 2>/dev/null || true
	wait "$pid" 2>/dev/null || true
	if grep -q "^SVC_GATE_DONE $name\$" "$dir/stdout.log" 2>/dev/null; then
		echo "SVC_RUN $name done after ${waited}s"
	else
		echo "SVC_RUN $name NO SVC_GATE_DONE after ${waited}s (see $dir)"
	fi
}

launch local local ""
launch fake fake "$COMMON_ENV $RESTART_ENV ATL_SERVICE_BOUND_CRASH_MAX_RETRY=3"
launch caps caps "$COMMON_ENV $RESTART_ENV"
launch fault fault "$COMMON_ENV ATL_SERVICE_PROCESS_FAULT=spawn"
launch child child "ATL_SERVICE_PROCESS=:simchild"
# Sixth launch: S13a's restart ladder, which needs MIN_CRASH_INTERVAL_MS below the 300 ms
# first restart delay instead of the 120000 default the other launches keep.
launch ladder ladder "$COMMON_ENV $LADDER_ENV"

# status SCENARIO RUN -> PASS | FAIL <reason> | MISSING
status() {
	log="$out/run-$2/stdout.log"
	if line=$(grep -m1 "^SVC $1 FAIL" "$log" 2>/dev/null); then
		echo "FAIL ${line#SVC $1 FAIL}"
	elif [ "$(grep -c "^SVC $1 PASS\$" "$log" 2>/dev/null || true)" -ge 1 ]; then
		echo PASS
	else
		echo MISSING
	fi
}

fail=0
pass=0
summary="$out/summary.txt"
: > "$summary"
say() { echo "$1" | tee -a "$summary"; }

say "SVC_GATE MATRIX local: (no env)"
say "SVC_GATE MATRIX fake: $COMMON_ENV $RESTART_ENV ATL_SERVICE_BOUND_CRASH_MAX_RETRY=3"
say "SVC_GATE MATRIX caps: $COMMON_ENV $RESTART_ENV (crash cap default 16)"
say "SVC_GATE MATRIX fault: $COMMON_ENV ATL_SERVICE_PROCESS_FAULT=spawn"
say "SVC_GATE MATRIX ladder: $COMMON_ENV $LADDER_ENV"
say "SVC_GATE MATRIX child: ATL_SERVICE_PROCESS=:simchild"
say "SVC_GATE MATRIX backends: local=none (loader-resident) fake|caps|fault|ladder=fake (ATLFakeProcessBackend) child=lane-D host lookup"
say "SVC_GATE MATRIX effective ATL_SERVICE_MIN_CRASH_INTERVAL_MS=120000 (fake,caps,fault) and 1000 (ladder)"

# sub-scenarios and the run each one is emitted by
subs="S01:local S02:local S03:local S04:local S05:local S06:local S07:local S08:local \
S14a:local S14b:local S09:fake S10:fake S11a:fake S11c:fake S11d:fake S12:fake S13c:fake S15:fake \
S13b:caps S13a:ladder S11b:fault S14c:child"
for s in $subs; do
	id=${s%%:*}
	r=${s##*:}
	st=$(status "$id" "$r")
	eval "ST_$id=\$st"
	say "SVC_SUB $id ($r) $st"
done

# combine: every constituent must PASS
combine() {
	id=$1
	shift
	res=PASS
	for c in "$@"; do
		eval "v=\$ST_$c"
		case "$v" in PASS) ;; *) res="${v%% *}"; [ "$res" = PASS ] || res="$c:$v" ;; esac
	done
	if [ "$res" = PASS ]; then
		say "SVC $id PASS"
		pass=$((pass + 1))
	else
		say "SVC $id FAIL $res"
		fail=$((fail + 1))
	fi
}
for id in S01 S02 S03 S04 S05 S06 S07 S08 S09 S10; do combine "$id" "$id"; done
combine S11 S11a S11b S11c S11d
combine S12 S12
combine S13 S13a S13b S13c
combine S14 S14a S14b S14c
combine S15 S15

# Per-row log: every claimed row carries its own extract of its run's stdout.log
# (its events and its single verdict line), so a row can be reviewed - and a
# MISSING row seen to be missing - without the whole run log.
rows="$out/rows"
rm -rf "$rows"
mkdir -p "$rows"
for s in $subs; do
	id=${s%%:*}
	r=${s##*:}
	log="$out/run-$r/stdout.log"
	{
		echo "# row $id - extracted from run-$r/stdout.log"
		echo "# full scenario log: $log"
		grep -m1 "^SVC_GATE BACKEND " "$log" 2>/dev/null || echo "# backend: NOT RECORDED in $log"
		grep "^SVC $id " "$log" 2>/dev/null \
			|| echo "SVC $id MISSING (no event of this row in $log)"
	} > "$rows/$id.log"
done
say "SVC_GATE ROWS $rows"

say "SVC_GATE SUMMARY $pass pass $fail fail"
test "$fail" -eq 0
