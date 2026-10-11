#!/usr/bin/env bash
# run_font_glyph_selftests.sh - executable self-test for verify_font_glyph.py.
#
# Regenerates the fixture matrix, then runs the comparator on every case in
# fixtures/cases.json. A case PASSES only when:
#   * the comparator exit code equals the intended exit code, AND
#   * an acceptance prints an `OK ` line, AND
#   * a rejection prints `REJECT <CODE> ...` with the exact intended <CODE>,
#   * and the run did NOT emit a traceback or `ERROR internal` (a crash is not
#     accepted as a semantic rejection).
#
# It also checks that a usage error propagates as exit 2 and that --allow-bare
# accepts the app's plain files/results.json form.
#
# Exits 0 ONLY when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_font_glyph.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$HERE/make_font_glyph_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name ref cand want_exit want_code; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    out="$("$PY" "$VERIFY" "$ref" "$cand" 2>&1)"
    rc=$?

    status="PASS"
    reason=""
    if echo "$out" | grep -q "Traceback (most recent call last)\|ERROR internal"; then
        status="FAIL"
        reason="crashed (not a semantic outcome)"
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
        printf 'PASS  %-30s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-30s %s\n' "$name" "$reason"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/cases.json" <<'EOF'
import json, sys
for c in json.load(open(sys.argv[1])):
    print("\t".join([c["name"], c["android"], c["candidate"],
                     str(c["exit"]), c["code"]]))
EOF
)

echo
# Internal/usage failure must be exit 2 and never a semantic REJECT.
total=$((total + 1))
out="$("$PY" "$VERIFY" "$FIX/valid_complete_pair.android.json" 2>&1)"
rc=$?
if [ "$rc" -eq 2 ] && echo "$out" | grep -q "^ERROR internal " \
   && ! echo "$out" | grep -qE "^REJECT "; then
    printf 'PASS  %-30s exit=%s  %s\n' "usage_internal_exit2" "$rc" \
        "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-30s exit=%s want=2 (internal)\n' "usage_internal_exit2" "$rc"
    echo "$out" | sed 's/^/        | /'
    fail=$((fail + 1))
fi

echo
# --allow-bare must accept the app's plain files/results.json form.
total=$((total + 1))
out="$("$PY" "$VERIFY" --allow-bare \
    "$FIX/valid_complete_pair.android.json" \
    "$FIX/candidate_bare_no_sentinel.candidate.json" 2>&1)"
rc=$?
if [ "$rc" -eq 0 ] && echo "$out" | grep -q "^OK "; then
    printf 'PASS  %-30s exit=%s  %s\n' "bare_json_allowed" "$rc" \
        "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-30s exit=%s want=0\n' "bare_json_allowed" "$rc"
    echo "$out" | sed 's/^/        | /'
    fail=$((fail + 1))
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "FONT-006 SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "FONT-006 SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
