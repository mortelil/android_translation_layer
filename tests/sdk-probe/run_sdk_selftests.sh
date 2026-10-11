#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# run_sdk_selftests.sh - executable self-test for verify_sdk_profile.py.
#
# Regenerates the fixture matrix, then runs the comparator on every case in
# fixtures/cases.json. A case PASSES only when the comparator exit code equals
# the intended code, the expected token is printed, and the run did NOT emit a
# traceback / `ERROR internal` (a crash is never accepted as a semantic
# rejection). Exits 0 only when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_sdk_profile.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$HERE/make_sdk_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name family want_exit want_code allow_bare; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    args=("$HERE/$family")
    [ "$allow_bare" = "1" ] && args+=(--allow-bare)
    out="$("$PY" "$VERIFY" "${args[@]}" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    if echo "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        status="FAIL"; reason="crashed (not a semantic outcome)"
    elif [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"; reason="exit=$rc want=$want_exit"
    elif [ "$want_exit" -eq 0 ]; then
        if ! echo "$out" | grep -q "^OK "; then status="FAIL"; reason="no OK line"; fi
    else
        if ! echo "$out" | grep -qE "^REJECT ${want_code} "; then
            status="FAIL"; reason="missing 'REJECT ${want_code}'"
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
    print("\t".join([c["name"], c["family"], str(c["exit"]), c["code"],
                     "1" if c["allow_bare"] else "0"]))
EOF
)

echo
# Internal/usage failure must be exit 2 and never a semantic REJECT.
total=$((total + 1))
out="$("$PY" "$VERIFY" 2>&1)"; rc=$?
if [ "$rc" -eq 2 ] && echo "$out" | grep -q "^ERROR internal " \
   && ! echo "$out" | grep -qE "^REJECT "; then
    printf 'PASS  %-24s exit=%s  %s\n' "usage_internal_exit2" "$rc" "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-24s exit=%s want=2\n' "usage_internal_exit2" "$rc"
    fail=$((fail + 1))
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "SDK SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "SDK SELFTESTS FAILED: $fail/$total cases wrong"
exit 1