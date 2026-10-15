#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# run_unsupported_remote_selftests.sh - executable self-test for
# verify_unsupported_remote.py (the SVC-014 remote/unsupported contract).
#
# Regenerates the fixture matrix, then runs every case in fixtures/remote/cases.json.
# A case PASSES only when:
#   * the verifier exit code equals the intended code, AND
#   * for an acceptance (exit 0) a leading `OK ` line is present, AND
#   * for a rejection a line `REJECT <CODE> ...` carries the exact intended CODE, AND
#   * the run did NOT emit an `ERROR internal` line or a Python traceback
#     (a crash is never accepted as a semantic rejection).
#
# The matrix's core cases are the negative remote-execution evidence: a missing
# identity reply (the frozen reference's own SVC-014b shape), a reply from the
# caller's own pid, malformed/incomplete payloads, an answered refusal, an
# out-of-order or duplicated reply, and the silent-local instance. Absence of
# the service's own callbacks in the caller's log is NOT sufficient evidence and
# no case accepts it.
#
# Deterministic: regenerating and re-running produces identical stdout.
# Exits 0 ONLY when every case behaves as intended; the final line is
#   UNSUPPORTED-REMOTE SELFTESTS OK: N/N cases behaved as intended
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_unsupported_remote.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$FIX/make_unsupported_remote_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name cand want_exit want_code; do
    [ -z "$name" ] && continue
    total=$((total + 1))

    out="$("$PY" "$VERIFY" "$cand" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    if printf '%s\n' "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        status="FAIL"
        reason="crashed (not a semantic rejection)"
    elif [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"
        reason="exit=$rc want=$want_exit"
    elif [ "$want_exit" -eq 0 ]; then
        if ! printf '%s\n' "$out" | grep -q "^OK "; then
            status="FAIL"
            reason="no OK line on acceptance"
        fi
    elif ! printf '%s\n' "$out" | grep -q "^REJECT ${want_code} "; then
        status="FAIL"
        reason="missing 'REJECT ${want_code}' token"
    fi

    if [ "$status" = "PASS" ]; then
        token="$(printf '%s\n' "$out" | grep -E "^REJECT |^OK " | head -1)"
        printf 'PASS  %-24s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-24s %s\n' "$name" "$reason"
        printf '%s\n' "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/remote/cases.json" <<'EOF'
import json, sys
for c in json.load(open(sys.argv[1])):
    print("\t".join([c["name"], c["cand"], str(c["exit"]), c["code"] or "-"]))
EOF
)

echo
if [ "$fail" -eq 0 ]; then
    echo "UNSUPPORTED-REMOTE SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "UNSUPPORTED-REMOTE SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
