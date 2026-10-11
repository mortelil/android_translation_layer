#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# run_service_analyzer_selftests.sh - executable self-test for verify_services.py.
#
# Regenerates the fixture matrix, then runs every case in fixtures/cases.json.
# A case PASSES only when:
#   * the verifier exit code equals the intended code, AND
#   * for an acceptance (exit 0) a leading `OK ` line is present, AND
#   * for a rejection a line `REJECT <CODE> ...` carries the exact intended CODE, AND
#   * the run did NOT emit an `ERROR internal` line or a Python traceback
#     (a crash is never accepted as a semantic rejection).
#
# Deterministic: regenerating and re-running produces identical stdout.
# Exits 0 ONLY when every case behaves as intended; the final line is
#   SERVICE ANALYZER SELFTESTS OK: N/N cases behaved as intended
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_services.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$FIX/make_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name ref cand want_exit want_code want_args; do
    [ -z "$name" ] && continue
    total=$((total + 1))

    cmd=("$PY" "$VERIFY")
    if [ -n "$want_args" ]; then
        read -r -a extra <<< "$want_args"
        cmd+=("${extra[@]}")
    fi
    cmd+=("$ref" "$cand")

    out="$("${cmd[@]}" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    crashed=0
    if printf '%s\n' "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        crashed=1
    fi

    if [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"
        reason="exit=$rc want=$want_exit"
    fi
    if [ "$crashed" -eq 1 ]; then
        status="FAIL"
        reason="${reason:+$reason; }crashed (not a semantic rejection)"
    fi
    if [ "$status" = "PASS" ] && [ "$want_exit" -eq 0 ]; then
        if ! printf '%s\n' "$out" | grep -q "^OK "; then
            status="FAIL"
            reason="no OK line on acceptance"
        fi
    fi
    if [ "$status" = "PASS" ] && [ "$want_exit" -ne 0 ] && [ "$want_code" != "-" ]; then
        if ! printf '%s\n' "$out" | grep -q "^REJECT ${want_code} "; then
            status="FAIL"
            reason="missing 'REJECT ${want_code}' token"
        fi
    fi
    if [ "$status" = "PASS" ] && [ "$want_exit" -ne 0 ] && [ "$want_code" = "-" ]; then
        # Usage error: must not look like an acceptance or a semantic rejection.
        if printf '%s\n' "$out" | grep -q "^OK \|^REJECT "; then
            status="FAIL"
            reason="usage error emitted OK/REJECT"
        fi
    fi

    if [ "$status" = "PASS" ]; then
        token="$(printf '%s\n' "$out" | grep -E "^REJECT |^OK |^ERROR " | head -1)"
        printf 'PASS  %-24s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-24s %s\n' "$name" "$reason"
        printf '%s\n' "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/cases.json" <<'EOF'
import json, sys
cases = json.load(open(sys.argv[1]))
for c in cases:
    print("\t".join([c["name"], c["ref"], c["cand"],
                     str(c["exit"]), c["code"] or "-",
                     " ".join(c.get("args", []))]))
EOF
)

echo
if [ "$fail" -eq 0 ]; then
    echo "SERVICE ANALYZER SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "SERVICE ANALYZER SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
