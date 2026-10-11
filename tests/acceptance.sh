#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# acceptance.sh - the ONE fail-closed acceptance command for the REVIEW-REPAIRS
# repair repo (gate version: see tests/ACCEPTANCE-GATE.md, v1).
#
# It wires only commands that were actually executed (tooling.md / progress.md):
#   * build      - meson compile in this checkout's configured build-mobile (atl_dev)
#   * identity   - sha256 of the loaded ATL artifacts + source commit/dirty state
#                  + frozen APK / comparator hashes, against a recorded manifest
#   * probe      - /home/morten/ATL/tools/run-atl-probe.sh for FONT-003/004/006/007
#                  and the lifecycle v2.1 APK (marker-based completion)
#   * contract   - the four font comparators (ref vs fresh candidate, --allow-bare)
#                  and verify_lifecycle.py (android ref vs fresh trace, pinned to
#                  the RED ATL baseline)
#   * selftests  - run_analyzer_selftests.sh + the four run_font_*_selftests.sh
#   * regression - tests/mobile-smoke (App/API smoke)
#
# Verdicts are kept SEPARATE - contract, evidence validity, negative control
# (selftests), regression, artifact identity - and combined ONLY for the final
# verdict. READY_FOR_REVIEW requires every required stage to pass.
#
# FAIL CLOSED: a missing mandatory input/tool, an empty or unparseable evidence
# file, an APK/artifact/comparator hash mismatch, or a non-zero stage never
# yields exit 0.
#
# Usage:
#   tests/acceptance.sh [--workdir DIR] [--manifest FILE] [--stage NAME] [-h]
#   tests/acceptance.sh --record-manifest [FILE]
#
#   --stage NAME   run preflight plus NAME only (diagnostic / negative-control
#                  demonstration). A stage run NEVER prints READY_FOR_REVIEW.
#                  NAME in: build identity probe contract selftests regression
#   --record-manifest [FILE]
#                  (re)write the identity manifest from the current build and
#                  exit; does not run acceptance.
#
# Env overrides (defaults are the frozen REVIEW-REPAIRS evidence):
#   ACCEPT_REPO, ACCEPT_AGENT_WORK, ACCEPT_WORKDIR, ACCEPT_MANIFEST,
#   ACCEPT_ATL_PROBE_RUNNER, ACCEPT_APK_<F003|F004|F006|F007|LC>,
#   ACCEPT_REF_<F003|F004|F006|F007>, ACCEPT_LC_ANDROID_TRACE,
#   ACCEPT_LC_BASELINE_TRACE,
#   ACCEPT_CAND_<F003|F004|F006|F007> (contract stage input override)
#
set -u

HERE=$(cd -- "$(dirname -- "$0")" && pwd)
REPO=${ACCEPT_REPO:-$(cd -- "$HERE/.." && pwd)}
AGENT_WORK=${ACCEPT_AGENT_WORK:-$HOME/ATL/agent-work/REVIEW-REPAIRS}
TOOLBOX_CONTAINER=atl_dev
ATL_PROBE_RUNNER=${ACCEPT_ATL_PROBE_RUNNER:-$HOME/ATL/tools/run-atl-probe.sh}
PROBE_TIMEOUT=${ACCEPT_PROBE_TIMEOUT:-300}

# ---- frozen inputs (verified to exist; never invented) --------------------
APK_F003=${ACCEPT_APK_F003:-$AGENT_WORK/font-metrics/font-003-probe.apk}
APK_F004=${ACCEPT_APK_F004:-$AGENT_WORK/font-bounds/font-bounds-probe.apk}
APK_F006=${ACCEPT_APK_F006:-$AGENT_WORK/font-glyph/font-006-glyph-probe.apk}
APK_F007=${ACCEPT_APK_F007:-$AGENT_WORK/font-gb/font-gb-probe.apk}
APK_LC=${ACCEPT_APK_LC:-$AGENT_WORK/probe-v2.1/lifecycle-fragment-probe-v2.1.apk}

REF_F003=${ACCEPT_REF_F003:-$AGENT_WORK/font-metrics-verify/attempt-1/android/android-results-sentinel.txt}
REF_F004=${ACCEPT_REF_F004:-$AGENT_WORK/font-bounds-verify/attempt-1/android/android-sentinel.txt}
REF_F006=${ACCEPT_REF_F006:-$AGENT_WORK/font-glyph-verify/android/results.json}
REF_F007=${ACCEPT_REF_F007:-$AGENT_WORK/font-gb-verify/attempt-1/android/results.json}

LC_ANDROID_TRACE=${ACCEPT_LC_ANDROID_TRACE:-$AGENT_WORK/lifecycle-v2.1/android/trace.log}
LC_BASELINE_TRACE=${ACCEPT_LC_BASELINE_TRACE:-$AGENT_WORK/lifecycle-v2.1/atl/trace.log}

CMP_F003=$REPO/tests/font-probe/verify_font_metrics.py
CMP_F004=$REPO/tests/font-probe-bounds/verify_font_bounds.py
CMP_F006=$REPO/tests/font-probe-glyph/verify_font_glyph.py
CMP_F007=$REPO/tests/font-probe-gb/verify_font_gb.py
CMP_LC=$REPO/tests/lifecycle-probe/verify_lifecycle.py
LC_MANIFEST=$REPO/tests/lifecycle-probe/manifest.json

SELFTESTS=(
  "$REPO/tests/lifecycle-probe/run_analyzer_selftests.sh"
  "$REPO/tests/font-probe/run_font_selftests.sh"
  "$REPO/tests/font-probe-bounds/run_font_bounds_selftests.sh"
  "$REPO/tests/font-probe-glyph/run_font_glyph_selftests.sh"
  "$REPO/tests/font-probe-gb/run_font_gb_selftests.sh"
)

MARKER_F003=FONT003_JSON_END
MARKER_F004=FONT004_JSON_END
MARKER_F006=FONT006_JSON_END
MARKER_F007=FONT007_JSON_END
MARKER_LC=TERMINAL_THROW_DONE

MANIFEST=${ACCEPT_MANIFEST:-$REPO/tests/acceptance/artifacts.manifest}
WORKDIR=${ACCEPT_WORKDIR:-/tmp/atl-acceptance/run-$(date +%Y%m%d-%H%M%S)}

# ---- args -----------------------------------------------------------------
STAGE=""
RECORD=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --workdir) WORKDIR=$2; shift 2 ;;
    --manifest) MANIFEST=$2; shift 2 ;;
    --stage) STAGE=$2; shift 2 ;;
    --record-manifest)
      RECORD=1
      if [ "$#" -ge 2 ] && [ "${2#--}" = "$2" ]; then MANIFEST=$2; shift 2; else shift; fi
      ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) echo "acceptance.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

VALID_STAGES="build identity probe contract selftests regression"
if [ -n "$STAGE" ]; then
  case " $VALID_STAGES " in
    *" $STAGE "*) : ;;
    *) echo "acceptance.sh: unknown --stage '$STAGE' (valid: $VALID_STAGES)" >&2; exit 2 ;;
  esac
fi

die() { echo "acceptance.sh: $*" >&2; exit 2; }
sha() { sha256sum "$1" | awk '{print $1}'; }

# dirty-state digest of the tracked working tree ('-' when clean).
dirty_digest() {
  local d
  d=$(git -C "$REPO" diff --no-ext-diff --binary 2>/dev/null | sha256sum | awk '{print $1}')
  if [ -z "$(git -C "$REPO" status --porcelain 2>/dev/null | grep -v '^??')" ]; then
    echo "-"
  else
    echo "$d"
  fi
}

# ---- record mode ----------------------------------------------------------
if [ "$RECORD" = 1 ]; then
  mkdir -p "$(dirname "$MANIFEST")"
  {
    echo "gate_version=1"
    echo "recorded_at=$(date -Iseconds)"
    echo "source_commit=$(git -C "$REPO" rev-parse HEAD)"
    echo "source_dirty_sha256=$(dirty_digest)"
    echo "libtranslation_layer_main_so=$(sha "$REPO/build-mobile/libtranslation_layer_main.so")"
    echo "api_impl_jar=$(sha "$REPO/build-mobile/api-impl.jar")"
    echo "android_translation_layer=$(sha "$REPO/build-mobile/android-translation-layer")"
    echo "apk_font_probe=$(sha "$APK_F003")"
    echo "apk_font_probe_bounds=$(sha "$APK_F004")"
    echo "apk_font_probe_glyph=$(sha "$APK_F006")"
    echo "apk_font_probe_gb=$(sha "$APK_F007")"
    echo "apk_lifecycle=$(sha "$APK_LC")"
    echo "verifier_font_metrics=$(sha "$CMP_F003")"
    echo "verifier_font_bounds=$(sha "$CMP_F004")"
    echo "verifier_font_glyph=$(sha "$CMP_F006")"
    echo "verifier_font_gb=$(sha "$CMP_F007")"
    echo "verifier_lifecycle=$(sha "$CMP_LC")"
    echo "manifest_lifecycle=$(sha "$LC_MANIFEST")"
  } > "$MANIFEST"
  echo "recorded manifest: $MANIFEST"
  cat "$MANIFEST"
  exit 0
fi

# ---- stage state ----------------------------------------------------------
FAIL_CONTRACT=0; FAIL_EVIDENCE=0; FAIL_SELFTEST=0; FAIL_REGRESSION=0
FAIL_IDENTITY=0; FAIL_BUILD=0; FAIL_PROBE=0
RESULTS=""   # per-check one-line results

note()  { RESULTS="${RESULTS}$1"$'\n'; printf '%s\n' "$1"; }
ok()    { printf 'PASS  %s\n' "$1"; }
bad()   { printf 'FAIL  %s\n' "$1"; }

# ===========================================================================
# preflight - mandatory inputs must exist and be non-empty; tools must exist.
# ===========================================================================
preflight() {
  local rc=0 f
  for t in python3 sha256sum git realpath toolbox; do
    command -v "$t" >/dev/null 2>&1 || { bad "tool missing: $t"; rc=1; }
  done
  for f in "$ATL_PROBE_RUNNER" "$CMP_F003" "$CMP_F004" "$CMP_F006" "$CMP_F007" \
           "$CMP_LC" "$LC_MANIFEST" \
           "$REF_F003" "$REF_F004" "$REF_F006" "$REF_F007" \
           "$LC_ANDROID_TRACE" "$LC_BASELINE_TRACE" \
           "$APK_F003" "$APK_F004" "$APK_F006" "$APK_F007" "$APK_LC"; do
    if [ ! -s "$f" ]; then bad "mandatory input missing/empty: $f"; rc=1; fi
  done
  for s in "${SELFTESTS[@]}"; do
    [ -x "$s" ] || [ -s "$s" ] || { bad "selftest missing: $s"; rc=1; }
  done
  [ -s "$MANIFEST" ] || { bad "identity manifest missing/empty: $MANIFEST (run --record-manifest)"; rc=1; }
  return $rc
}

# ===========================================================================
# build
# ===========================================================================
stage_build() {
  echo "-- build (container $TOOLBOX_CONTAINER)"
  if toolbox run --container "$TOOLBOX_CONTAINER" sh -lc \
      "cd \$HOME/$(rel "$REPO") && meson compile -C build-mobile -j 2" \
      > "$WORKDIR/build.log" 2>&1; then
    ok "build exit 0 ($(tail -1 "$WORKDIR/build.log"))"
    return 0
  fi
  bad "build failed (see $WORKDIR/build.log)"
  FAIL_BUILD=1
  return 1
}

# path relative to $HOME (the container mounts the host $HOME).
rel() {
  case "$1" in
    "$HOME"/*) echo "${1#"$HOME"/}" ;;
    *) echo "$1" ;;
  esac
}

# ===========================================================================
# identity
# ===========================================================================
stage_identity() {
  # shellcheck disable=SC1090
  . "$MANIFEST"
  local rc=0 key actual
  check() { # key path
    actual=$(sha "$2")
    if [ "$actual" != "$1" ]; then
      bad "identity mismatch $3: recorded=$1 actual=$actual"
      rc=1
    fi
  }
  check "$libtranslation_layer_main_so" "$REPO/build-mobile/libtranslation_layer_main.so" libtranslation_layer_main.so
  check "$api_impl_jar" "$REPO/build-mobile/api-impl.jar" api-impl.jar
  check "$android_translation_layer" "$REPO/build-mobile/android-translation-layer" android-translation-layer
  check "$apk_font_probe" "$APK_F003" apk-font-003
  check "$apk_font_probe_bounds" "$APK_F004" apk-font-004
  check "$apk_font_probe_glyph" "$APK_F006" apk-font-006
  check "$apk_font_probe_gb" "$APK_F007" apk-font-007
  check "$apk_lifecycle" "$APK_LC" apk-lifecycle
  check "$verifier_font_metrics" "$CMP_F003" comparator-font-003
  check "$verifier_font_bounds" "$CMP_F004" comparator-font-004
  check "$verifier_font_glyph" "$CMP_F006" comparator-font-006
  check "$verifier_font_gb" "$CMP_F007" comparator-font-007
  check "$verifier_lifecycle" "$CMP_LC" comparator-lifecycle
  check "$manifest_lifecycle" "$LC_MANIFEST" lifecycle-manifest
  local head; head=$(git -C "$REPO" rev-parse HEAD)
  if [ "$head" != "$source_commit" ]; then
    # The gate files themselves live under tests/; committing them advances HEAD
    # without changing the reviewed implementation. Anything outside tests/ is a
    # real source change and fails closed.
    if git -C "$REPO" diff --quiet "$source_commit" "$head" -- . ':(exclude)tests'; then
      echo "  note: HEAD advanced past $source_commit by tests-only commits (implementation source unchanged)"
    else
      bad "source commit mismatch: recorded=$source_commit actual=$head (non-tests source differs)"
      rc=1
    fi
  fi
  local dd; dd=$(dirty_digest)
  if [ "$dd" != "$source_dirty_sha256" ]; then bad "source dirty state mismatch: recorded=$source_dirty_sha256 actual=$dd"; rc=1; fi
  if [ "$rc" = 0 ]; then ok "artifact + source identity matches manifest (commit $head, dirty ${source_dirty_sha256})"; else FAIL_IDENTITY=1; fi
  return $rc
}

# ===========================================================================
# probe helpers
# ===========================================================================
run_probe() { # apk outdir marker extra_desc
  local apk=$1 out=$2 marker=$3 desc=$4
  rm -rf "$out"; mkdir -p "$out"
  ATL_PROBE_SOURCE_DIR="$REPO" ATL_PROBE_BUILD_DIR="$REPO/build-mobile" \
    "$ATL_PROBE_RUNNER" "$apk" "$out" "$marker" "$PROBE_TIMEOUT" > "$out/runner.log" 2>&1
  local rc=$?
  if [ "$rc" != 0 ]; then
    bad "probe $desc helper exit=$rc ($(tail -1 "$out/runner.log"))"
    return 1
  fi
  if [ ! -s "$out/app-results.json" ]; then
    bad "probe $desc produced empty app-results.json"
    FAIL_EVIDENCE=1
    return 1
  fi
  echo "probe $desc: $out/app-results.json"
  return 0
}

# validated candidate JSON summary. mode "status" (font probes) requires
# status==OK; mode "lifecycle" requires run_id + non-empty events + no
# exceptions (the lifecycle aggregate has no `status` field).
json_summary() {
  python3 - "$1" "${2:-status}" <<'PY'
import json,sys
p,mode=sys.argv[1],sys.argv[2]
try:
    d=json.load(open(p,encoding="utf-8"))
except Exception as e:
    sys.stderr.write("unparseable JSON %s: %s\n"%(p,e)); sys.exit(1)
if not isinstance(d,dict):
    sys.stderr.write("JSON not an object: %s\n"%p); sys.exit(1)
if mode=="status":
    if d.get("status") != "OK":
        sys.stderr.write("candidate status=%r (not OK): %s\n"%(d.get("status"),p)); sys.exit(1)
    cells=d.get("cells"); n=len(cells) if isinstance(cells,(list,dict)) else (cells or "?")
else:
    if not d.get("run_id"):
        sys.stderr.write("candidate missing run_id: %s\n"%p); sys.exit(1)
    if d.get("exceptions"):
        sys.stderr.write("candidate carries %d exception(s): %s\n"%(len(d["exceptions"]),p)); sys.exit(1)
    n=len(d.get("events") or [])
    if n==0:
        sys.stderr.write("candidate has no events: %s\n"%p); sys.exit(1)
print(d.get("task_id","?"), d.get("run_id","?"), d.get("status","-"), n)
PY
}

# Splice a lifecycle-probe v2.1 trace from ATL stdout + the app's results.json
# (same algorithm as the verified lifecycle-v2.1/extract_trace.py).
extract_trace() { # raw_log results_json out_trace
  python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
raw_path, results_path, out_path = sys.argv[1:4]
PREFIXES = ("LIFE_TRACE_BEGIN", "LIFE_EVT ", "LIFE_SCEN_BEGIN ", "LIFE_SCEN_END ",
            "LIFE_ALL_DONE", "LIFE_JSON_BEGIN", "LIFE_JSON_END",
            "LIFE_PROBE_MARK ", "LIFE_EXCEPTION ")
LOGCAT_RE = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+System\.out:\s?(.*)$")
raw = open(raw_path, encoding="utf-8", errors="replace").read().split("\n")
msgs = [(LOGCAT_RE.match(l).group(1) if LOGCAT_RE.match(l) else l) for l in raw]
start = next((i for i, m in enumerate(msgs) if m.startswith("LIFE_TRACE_BEGIN")), None)
if start is None:
    sys.exit("no LIFE_TRACE_BEGIN in %s" % raw_path)
probe = [m for m in msgs[start:] if m.startswith(PREFIXES)]
if not probe:
    sys.exit("no probe lines after header in %s" % raw_path)
agg = json.load(open(results_path, encoding="utf-8"))
compact = json.dumps(agg, separators=(",", ":"))
out, i = [], 0
while i < len(probe):
    out.append(probe[i])
    if probe[i] == "LIFE_JSON_BEGIN":
        j = i + 1
        while j < len(probe) and probe[j] != "LIFE_JSON_END":
            j += 1
        out.append(compact)
        if j < len(probe):
            out.append("LIFE_JSON_END")
            i = j
    i += 1
open(out_path, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("wrote %s (%d lines, run_id=%s)" % (out_path, len(out), agg.get("run_id")))
PY
}

# ===========================================================================
# probe stage
# ===========================================================================
stage_probe() {
  mkdir -p "$WORKDIR/cand"
  local rc=0 s
  set -- \
    "F003|$APK_F003|$MARKER_F003" \
    "F004|$APK_F004|$MARKER_F004" \
    "F006|$APK_F006|$MARKER_F006" \
    "F007|$APK_F007|$MARKER_F007"
  for spec in "$@"; do
    local fam=${spec%%|*}; spec=${spec#*|}
    local apk=${spec%%|*}; local marker=${spec#*|}
    local out="$WORKDIR/probe/$fam"
    if run_probe "$apk" "$out" "$marker" "$fam"; then
      if s=$(json_summary "$out/app-results.json" 2>&1); then
        cp -f "$out/app-results.json" "$WORKDIR/cand/$fam.json"
        echo "  evidence $fam: $s"
      else
        bad "evidence invalid $fam: $s"; FAIL_EVIDENCE=1; rc=1
      fi
    else
      rc=1
    fi
  done
  # lifecycle probe + trace splice
  local lcout="$WORKDIR/probe/lifecycle"
  if run_probe "$APK_LC" "$lcout" "$MARKER_LC" "lifecycle"; then
    if s=$(json_summary "$lcout/app-results.json" lifecycle 2>&1); then
      echo "  evidence lifecycle: $s"
    else
      bad "evidence invalid lifecycle: $s"; FAIL_EVIDENCE=1; rc=1
    fi
    if extract_trace "$lcout/stdout.log" "$lcout/app-results.json" \
        "$WORKDIR/cand/lifecycle.trace"; then
      [ -s "$WORKDIR/cand/lifecycle.trace" ] || { bad "empty lifecycle trace"; FAIL_EVIDENCE=1; rc=1; }
    else
      bad "lifecycle trace extraction failed"; FAIL_EVIDENCE=1; rc=1
    fi
  else
    rc=1
  fi
  [ "$rc" = 0 ] && ok "all probes produced valid evidence"
  FAIL_PROBE=$rc
  return $rc
}

# ===========================================================================
# contract stage
# ===========================================================================
run_comparator() { # name comparator ref cand [extra...]
  local name=$1 cmp=$2 ref=$3 cand=$4; shift 4
  [ -s "$cand" ] || { bad "$name: candidate missing/empty: $cand"; FAIL_EVIDENCE=1; FAIL_CONTRACT=1; return 1; }
  local out; out=$(python3 "$cmp" "$ref" "$cand" --allow-bare "$@" 2>&1); local rc=$?
  if [ "$rc" = 0 ] && printf '%s' "$out" | grep -q '^OK '; then
    ok "$name: ${out%%$'\n'*}"
    return 0
  fi
  bad "$name: exit=$rc ${out%%$'\n'*}"; FAIL_CONTRACT=1; return 1
}

stage_contract() {
  local rc=0
  local c_f003=${ACCEPT_CAND_F003:-$WORKDIR/cand/F003.json}
  local c_f004=${ACCEPT_CAND_F004:-$WORKDIR/cand/F004.json}
  local c_f006=${ACCEPT_CAND_F006:-$WORKDIR/cand/F006.json}
  local c_f007=${ACCEPT_CAND_F007:-$WORKDIR/cand/F007.json}
  local c_lc=${ACCEPT_CAND_LC:-$WORKDIR/cand/lifecycle.trace}
  run_comparator "contract FONT-003" "$CMP_F003" "$REF_F003" "$c_f003" || rc=1
  run_comparator "contract FONT-004" "$CMP_F004" "$REF_F004" "$c_f004" || rc=1
  run_comparator "contract FONT-006" "$CMP_F006" "$REF_F006" "$c_f006" || rc=1
  run_comparator "contract FONT-007" "$CMP_F007" "$REF_F007" "$c_f007" || rc=1
  # lifecycle: ref vs candidate, pinned to RED baseline
  if [ ! -s "$c_lc" ]; then
    bad "contract lifecycle: candidate trace missing/empty: $c_lc"; FAIL_EVIDENCE=1; FAIL_CONTRACT=1; rc=1
  else
    local out; out=$(python3 "$CMP_LC" "$LC_ANDROID_TRACE" "$c_lc" \
      --baseline="$LC_BASELINE_TRACE" 2>&1); local lrc=$?
    if [ "$lrc" = 0 ] && printf '%s' "$out" | grep -q '^OK '; then
      ok "contract lifecycle: $(printf '%s' "$out" | grep -c '^DIVERGENCE ') pinned DIVERGENCE lines; $(printf '%s' "$out" | grep '^OK ')"
    else
      bad "contract lifecycle: exit=$lrc $(printf '%s' "$out" | tail -1)"; FAIL_CONTRACT=1; rc=1
    fi
  fi
  [ "$rc" = 0 ] && ok "all contract verdicts PASS"
  return $rc
}

# ===========================================================================
# selftests (negative control)
# ===========================================================================
stage_selftests() {
  local rc=0 s
  mkdir -p "$WORKDIR/selftests"
  for s in "${SELFTESTS[@]}"; do
    local name; name=$(basename "$(dirname "$s")")/$(basename "$s")
    if bash "$s" > "$WORKDIR/selftests/$(basename "$(dirname "$s")").log" 2>&1; then
      ok "selftest $name: $(tail -1 "$WORKDIR/selftests/$(basename "$(dirname "$s")").log")"
    else
      bad "selftest $name failed (see $WORKDIR/selftests/)"; FAIL_SELFTEST=1; rc=1
    fi
  done
  return $rc
}

# ===========================================================================
# regression
# ===========================================================================
stage_regression() {
  echo "-- regression (mobile-smoke)"
  local out="$WORKDIR/regression"; mkdir -p "$out"
  if toolbox run --container "$TOOLBOX_CONTAINER" sh -lc \
      ". \$HOME/ATL/test-env.sh; export ATL_BUILD_DIR=\$HOME/$(rel "$REPO")/build-mobile; atl_repo=\$HOME/$(rel "$REPO"); . \$HOME/$(rel "$REPO")/scripts/mobile/environment.sh; cd \$HOME/$(rel "$REPO"); export JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac; tests/mobile-smoke/build.sh \"\$atl_build\" build/mobile-tests/smoke && tests/mobile-smoke/run.sh \"\$atl_build\" build/mobile-tests/smoke/mobile-smoke.apk build/mobile-tests/smoke-run" \
      > "$out/smoke.log" 2>&1 && grep -q '^ATL TEST APK PASSED$' "$out/smoke.log"; then
    ok "regression mobile-smoke: ATL TEST APK PASSED"
    return 0
  fi
  bad "regression mobile-smoke failed (see $out/smoke.log)"; FAIL_REGRESSION=1
  return 1
}

# ===========================================================================
# driver
# ===========================================================================
mkdir -p "$WORKDIR"

echo "== acceptance =="
echo "repo=$REPO workdir=$WORKDIR manifest=$MANIFEST stage=${STAGE:-all}"

if ! preflight; then
  echo "PREFLIGHT FAILED - missing mandatory input(s); aborting."
  exit 1
fi
echo "preflight OK"

if [ -n "$STAGE" ]; then
  rc=0
  case "$STAGE" in
    build)      stage_build || rc=1 ;;
    identity)   stage_identity || rc=1 ;;
    probe)      stage_probe || rc=1 ;;
    contract)   stage_contract || rc=1 ;;
    selftests)  stage_selftests || rc=1 ;;
    regression) stage_regression || rc=1 ;;
  esac
  echo "STAGE $STAGE exit=$rc (stage runs never grant READY_FOR_REVIEW)"
  exit "$rc"
fi

stage_build      || true
stage_identity   || true
if [ "$FAIL_IDENTITY" = 1 ]; then
  echo "identity FAILED - refusing to run probes against an unverified artifact."
  FAIL_PROBE=1; FAIL_CONTRACT=1; FAIL_EVIDENCE=1
else
  stage_probe || true
fi
# contract runs whenever candidates exist (independent of probe bookkeeping)
stage_contract || true
stage_selftests || true
stage_regression || true

# ---- separate verdicts, combined only here --------------------------------
echo
echo "== VERDICTS =="
C=PASS; [ "$FAIL_CONTRACT" != 0 ] && C=FAIL
E=PASS; [ "$FAIL_EVIDENCE" = 0 ] || E=FAIL
N=PASS; [ "$FAIL_SELFTEST" = 0 ] || N=FAIL
R=PASS; [ "$FAIL_REGRESSION" = 0 ] || R=FAIL
I=PASS; [ "$FAIL_IDENTITY" = 0 ] || I=FAIL
B=PASS; [ "$FAIL_BUILD" = 0 ] || B=FAIL
echo "artifact identity : $I"
echo "build             : $B"
echo "evidence validity : $E"
echo "contract verdicts : $C"
echo "negative control  : $N"
echo "regression        : $R"

if [ "$I" = PASS ] && [ "$B" = PASS ] && [ "$E" = PASS ] && \
   [ "$C" = PASS ] && [ "$N" = PASS ] && [ "$R" = PASS ]; then
  echo "ACCEPTANCE VERDICT: READY_FOR_REVIEW"
  exit 0
fi
echo "ACCEPTANCE VERDICT: NOT_READY"
exit 1
