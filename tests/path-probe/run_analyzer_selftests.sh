#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# run_analyzer_selftests.sh - executable self-test for compare.py (G-PATH).
#
# A case PASSES only when:
#   * compare.py exit code equals the intended exit code, AND
#   * for a rejection, stdout carries `REJECT <CODE>` with the intended CODE, AND
#   * a run did NOT emit `ERROR internal`/a traceback unless the case expects it.
# Exits 0 only when every case behaves as intended.
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
FIX="$HERE/fixtures"
PY="${PYTHON:-python3}"
COMPARE="$HERE/compare.py"

fail=0
total=0

echo "== regenerating fixtures from the frozen Android reference =="
[ -f "$FIX/reference.json" ] || { echo "FATAL: $FIX/reference.json missing"; exit 2; }
"$PY" "$HERE/make_fixtures.py" "$FIX/reference.json" "$FIX" || \
    { echo "FATAL: fixture generation failed"; exit 2; }
echo

while IFS=$'\t' read -r name android atl want_exit want_code want_expect want_env want_note; do
    [ -z "$name" ] && continue
    total=$((total + 1))
    [ -n "$android" ] || android="reference.json"
    if [ -n "$want_env" ] && [ "$want_env" != "-" ]; then
        export "$want_env"
    else
        unset PATHPROBE_COMPARE_CRASH 2>/dev/null || true
    fi
    out="$("$PY" "$COMPARE" "$FIX/$android" "$FIX/$atl" 2>&1)"
    rc=$?
    unset PATHPROBE_COMPARE_CRASH 2>/dev/null || true

    status="PASS"; reason=""
    crashed=0
    echo "$out" | grep -q "Traceback (most recent call last)\|ERROR internal" && crashed=1
    if [ "$rc" -ne "$want_exit" ]; then
        status="FAIL"; reason="exit=$rc want=$want_exit"
    fi
    if [ "$want_expect" = "internal" ]; then
        [ "$crashed" -ne 1 ] && { status="FAIL"; reason="${reason:+$reason; }expected internal-error signature"; }
        echo "$out" | grep -qE "^REJECT " && { status="FAIL"; reason="${reason:+$reason; }crash surfaced as REJECT"; }
    else
        [ "$crashed" -eq 1 ] && { status="FAIL"; reason="${reason:+$reason; }crashed (not a semantic rejection)"; }
        if [ "$status" = "PASS" ] && [ "$want_exit" -ne 0 ] && \
           ! echo "$out" | grep -qE "^REJECT ${want_code} "; then
            status="FAIL"; reason="${reason:+$reason; }missing 'REJECT ${want_code}'"
        fi
        if [ "$status" = "PASS" ] && [ "$want_exit" -eq 0 ] && \
           ! echo "$out" | grep -q "^OK "; then
            status="FAIL"; reason="${reason:+$reason; }no OK line on acceptance"
        fi
        if [ "$status" = "PASS" ] && [ "$want_exit" -eq 0 ] && \
           [ -n "$want_note" ] && [ "$want_note" != "-" ] && \
           ! echo "$out" | grep -qF "$want_note"; then
            status="FAIL"; reason="${reason:+$reason; }missing note '$want_note'"
        fi
    fi

    if [ "$status" = "PASS" ]; then
        token="$(echo "$out" | grep -E "^REJECT |^OK |^ERROR internal" | head -1)"
        printf 'PASS  %-30s exit=%s  %s\n' "$name" "$rc" "$token"
    else
        printf 'FAIL  %-30s %s\n' "$name" "$reason"
        echo "$out" | sed 's/^/        | /'
        fail=$((fail + 1))
    fi
done < <("$PY" - "$FIX/cases.json" <<'EOF'
import json, sys
for c in json.load(open(sys.argv[1])):
    print("\t".join([c["name"], c.get("android", "reference.json"), c["atl"],
                     str(c["exit"]), c["code"] or "-",
                     c.get("expect", "reject"), c.get("env") or "-",
                     c.get("note") or "-"]))
EOF
)

echo
if [ "$fail" -eq 0 ]; then
    echo "PATH-COMPARATOR SELFTESTS OK: $total/$total cases behaved as intended"
    exit 0
fi
echo "PATH-COMPARATOR SELFTESTS FAILED: $fail/$total cases wrong"
exit 1
