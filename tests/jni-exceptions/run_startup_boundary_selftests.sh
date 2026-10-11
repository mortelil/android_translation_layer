#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# run_startup_boundary_selftests.sh - executable self-test for
# verify_startup_boundary.py (JNI-EXC-001 cases H/I).
#
# Regenerates the fixture matrix and runs the analyzer on every case in
# fixtures-startup/cases.json. A case PASSES only when the analyzer exit code
# equals the intended code AND the acceptance prints `OK ` / the rejection
# prints the exact `REJECT <CODE> ` token, with no traceback or `ERROR internal`.
#
# Also checks:
#   * usage/internal failure is exit 2 (never a semantic REJECT);
#   * a nonexistent run dir is an internal error (exit 2);
#   * wrapper failure propagation: run-case.sh rejects an unknown case nonzero;
#   * RED negative control: the recorded pre-fix baseline logs
#     runs/AJ-baseline-1/{revanced,youtube-x86} MUST make the analyzer FAIL for
#     the pending-exception abort (logs are read-only; never edited here).
#
# Exits 0 ONLY when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures-startup"
VERIFY="$HERE/verify_startup_boundary.py"
RUN_CASE="$HERE/run-case.sh"
PY="${PYTHON:-python3}"
BASELINE_ROOT="${JNIEXC_BASELINE_ROOT:-/home/morten/ATL/agent-work/APP-COMPAT/runs/AJ-baseline-1}"

fail=0
total=0

echo "== regenerating startup-boundary fixtures =="
"$PY" "$HERE/make_startup_boundary_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name dir want_exit want_code; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    out="$("$PY" "$VERIFY" "$FIX/$dir" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    if echo "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        status="FAIL"
        reason="crashed / internal error (not a semantic outcome)"
    elif [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"
        reason="exit=$rc want=$want_exit"
    elif [ "$want_exit" -eq 0 ]; then
        if ! echo "$out" | grep -q "^OK "; then
            status="FAIL"
            reason="no OK line on acceptance"
        fi
    else
        if ! echo "$out" | grep -qE "^REJECT ${want_code} "; then
            status="FAIL"
            reason="missing 'REJECT ${want_code}' token"
        fi
    fi

    if [ "$status" = "PASS" ]; then
        token="$(echo "$out" | grep -E "^REJECT |^OK " | head -1)"
        printf 'PASS  %-24s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-24s %s\n' "$name" "$reason"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/cases.json" <<'EOF'
import json, sys
for c in json.load(open(sys.argv[1])):
    print("\t".join([c["name"], c["dir"], str(c["exit"]), c["code"]]))
EOF
)

echo
# Internal/usage failure must be exit 2 and never a semantic REJECT.
total=$((total + 1))
out="$("$PY" "$VERIFY" 2>&1)"
rc=$?
if [ "$rc" -eq 2 ] && echo "$out" | grep -q "^ERROR internal " \
   && ! echo "$out" | grep -qE "^REJECT "; then
    printf 'PASS  %-24s exit=%s  %s\n' "usage_internal_exit2" "$rc" "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-24s exit=%s want=2 (internal)\n' "usage_internal_exit2" "$rc"
    echo "$out" | sed 's/^/        | /'
    fail=$((fail + 1))
fi

echo
# Nonexistent run dir must also be an internal error, not a semantic REJECT.
total=$((total + 1))
out="$("$PY" "$VERIFY" "$FIX/does-not-exist" 2>&1)"
rc=$?
if [ "$rc" -eq 2 ] && echo "$out" | grep -q "^ERROR internal "; then
    printf 'PASS  %-24s exit=%s  %s\n' "usage_missing_dir_exit2" "$rc" "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-24s exit=%s want=2 (internal)\n' "usage_missing_dir_exit2" "$rc"
    echo "$out" | sed 's/^/        | /'
    fail=$((fail + 1))
fi

echo
# Wrapper failure propagation: an unknown case must fail fast (nonzero) and must
# not launch ATL. run-case.sh validates args before invoking the runner.
total=$((total + 1))
if [ -x "$RUN_CASE" ]; then
    out="$("$RUN_CASE" bogus "$FIX/wrapper-bogus" 2>&1)"
    rc=$?
    if [ "$rc" -ne 0 ] && echo "$out" | grep -qi "unknown case\|usage"; then
        printf 'PASS  %-24s exit=%s  %s\n' "wrapper_unknown_case" "$rc" "$(echo "$out" | head -1)"
    else
        printf 'FAIL  %-24s exit=%s want=nonzero+usage\n' "wrapper_unknown_case" "$rc"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
else
    printf 'FAIL  %-24s run-case.sh missing or not executable\n' "wrapper_unknown_case"
    fail=$((fail + 1))
fi

echo
# RED negative control: the recorded pre-fix baseline logs must be REJECTED for
# the pending-exception abort (never edited; read-only fixture inputs).
for b in revanced youtube-x86; do
    total=$((total + 1))
    dir="$BASELINE_ROOT/$b"
    if [ ! -f "$dir/atl-ui-stderr.log" ]; then
        printf 'FAIL  %-24s baseline log missing: %s\n' "baseline_$b" "$dir"
        fail=$((fail + 1))
        continue
    fi
    out="$("$PY" "$VERIFY" "$dir" 2>&1)"
    rc=$?
    if [ "$rc" -eq 1 ] && echo "$out" | grep -qE "^REJECT SECONDARY_ABORT "; then
        printf 'PASS  %-24s exit=%s  %s\n' "baseline_$b" "$rc" "$(echo "$out" | grep -E '^REJECT ' | head -1)"
    else
        printf 'FAIL  %-24s exit=%s want=1 + REJECT SECONDARY_ABORT\n' "baseline_$b" "$rc"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done

echo
if [ "$fail" -eq 0 ]; then
    echo "JNI-EXC-001 STARTUP-BOUNDARY SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "JNI-EXC-001 STARTUP-BOUNDARY SELFTESTS FAILED: $fail/$total cases wrong"
exit 1