#!/usr/bin/env bash
# run_web_selftests.sh - executable self-test for verify_websettings.py.
#
# Regenerates the fixture matrix, then runs the comparator on every case in
# fixtures/cases.json. A case PASSES only when:
#   * the comparator exit code equals the intended exit code, AND
#   * an acceptance prints an `OK ` line (and the intended token if any), AND
#   * a rejection prints `REJECT <CODE> ...` with the exact intended <CODE>,
#   * and the run did NOT emit a traceback or `ERROR internal` (a crash is not
#     accepted as a semantic rejection).
#
# Exits 0 ONLY when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
VERIFY="$HERE/verify_websettings.py"
PY="${PYTHON:-python3}"

fail=0
total=0

echo "== regenerating fixtures =="
"$PY" "$HERE/make_web_fixtures.py" || { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name ref cand want_exit want_code want_token; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    out="$("$PY" "$VERIFY" "$FIX/$ref" "$FIX/$cand" 2>&1)"
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
        elif [ -n "$want_token" ] && ! echo "$out" | grep -qF "$want_token"; then
            status="FAIL"
            reason="missing token: $want_token"
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
                     str(c["exit"]), c["code"], c.get("token", "")]))
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
# Wrapper failure propagation: a tampered fixture must make the loop FAIL
# (non-zero wrapper exit), proving wrong outcomes are not silently accepted.
total=$((total + 1))
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
"$PY" "$HERE/make_web_fixtures.py" >/dev/null
# Corrupt the task id so a case expecting acceptance now rejects.
sed 's/"C-WEB"/"FONT-003"/' "$FIX/valid_complete_pair.candidate.json" \
    > "$tmpdir/broken.candidate.json"
out="$("$PY" "$VERIFY" "$FIX/valid_complete_pair.android.json" \
    "$tmpdir/broken.candidate.json" 2>&1)"
rc=$?
if [ "$rc" -eq 1 ] && echo "$out" | grep -qE "^REJECT WRONG_TASK "; then
    printf 'PASS  %-30s exit=%s  propagation=%s\n' "wrapper_propagation" "$rc" \
        "$(echo "$out" | head -1)"
else
    printf 'FAIL  %-30s exit=%s want=1 (WRONG_TASK)\n' "wrapper_propagation" "$rc"
    echo "$out" | sed 's/^/        | /'
    fail=$((fail + 1))
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "C-WEB SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "C-WEB SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
