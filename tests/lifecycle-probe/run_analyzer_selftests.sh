#!/usr/bin/env bash
# run_analyzer_selftests.sh - executable self-test for verify_lifecycle.py.
#
# Runs the fixture matrix in fixtures/cases.json. A case PASSES only when:
#   * the verifier's exit code equals the intended exit code, AND
#   * for a rejection, stdout carries a `REJECT <CODE> ...` line with the exact
#     intended <CODE>, AND
#   * the run did NOT emit an `ERROR internal` line or a Python traceback
#     (a crash is NOT accepted as a semantic rejection).
#
# Exits 0 ONLY when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_lifecycle.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$HERE/make_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

# Parse cases.json without requiring jq.
while IFS=$'\t' read -r name ref cand man want_exit want_code want_expect want_env; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    if [ -n "$want_env" ]; then
        export "$want_env"
    else
        unset VERIFY_SELFTEST_CRASH 2>/dev/null || true
    fi
    out="$("$PY" "$VERIFY" "$ref" "$cand" "$man" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    crashed=0
    if echo "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        crashed=1
    fi

    if [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"
        reason="exit=$rc want=$want_exit"
    fi

    if [ "$want_expect" = "internal" ]; then
        # A crash must be reported as an internal error and NEVER as a REJECT.
        if [ "$crashed" -ne 1 ]; then
            status="FAIL"
            reason="${reason:+$reason; }expected an internal-error signature"
        fi
        if echo "$out" | grep -qE "^REJECT "; then
            status="FAIL"
            reason="${reason:+$reason; }a crash was wrongly surfaced as a REJECT"
        fi
    else
        if [ "$crashed" -eq 1 ]; then
            status="FAIL"
            reason="${reason:+$reason; }crashed (not a semantic rejection)"
        fi
        if [ "$status" = "PASS" ] && [ "$want_exit" -ne 0 ]; then
            if ! echo "$out" | grep -qE "^REJECT ${want_code} "; then
                status="FAIL"
                reason="${reason:+$reason; }missing 'REJECT ${want_code}' token"
            fi
        fi
        if [ "$status" = "PASS" ] && [ "$want_exit" -eq 0 ]; then
            if ! echo "$out" | grep -q "^OK "; then
                status="FAIL"
                reason="${reason:+$reason; }no OK line on acceptance"
            fi
        fi
    fi
    unset VERIFY_SELFTEST_CRASH 2>/dev/null || true

    if [ "$status" = "PASS" ]; then
        token="$(echo "$out" | grep -E "^REJECT |^OK |^ERROR internal" | head -1)"
        printf 'PASS  %-38s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-38s %s\n' "$name" "$reason"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/cases.json" <<'EOF'
import json, sys
cases = json.load(open(sys.argv[1]))
for c in cases:
    print("\t".join([c["name"], c["reference"], c["candidate"], c["manifest"],
                     str(c["exit"]), c["code"] or "-", c.get("expect", "reject"),
                     c.get("env", "")]))
EOF
)

echo
if [ "$fail" -eq 0 ]; then
    echo "ANALYZER SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "ANALYZER SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
