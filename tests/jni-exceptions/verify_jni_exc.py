#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed analyzer for JNI-EXC-001 probe runs.

Usage:  verify_jni_exc.py <RUN_DIR>

<RUN_DIR> is produced by run-case.sh (or a self-test fixture) and contains:
    run-meta.json   harness metadata (task_id, case, run_id, apk sha256, ...)
    combined.log    stdout+stderr of the ATL run, terminated by the harness
                    trailer line `JNIEXC001_RUN_COMPLETE run_id=<id> runner_exit=<n>`

Verdicts (exit codes):
    0  PASS      the scenario contract holds
    1  REJECT    a specific contract violation (code on stdout)
    2  ERROR     usage / internal failure (never a semantic rejection)

Scenario A (negative: uncaught onCreate throw at a native-owned boundary)
    PASS requires: the original `java.lang.IllegalStateException: JNI-EXC-001-A`
    AND its cause `java.lang.UnsupportedOperationException: origin-A` present,
    NO CheckJNI pending-exception abort, and the post-failure sentinel absent.

Scenario C (positive control: the throwable is caught inside the Java callback)
    PASS requires: marker `JNIEXC001_POS_DONE` present, the JSON block present,
    and no uncaught/fatal record.

Scenario B (formatter-throws variant)
    PASS requires identity witnessed by the handler, fallback message/cause,
    formatter failure and a terminal record, without a secondary JNI abort.
    original_present remains the old literal-class diagnostic, not identity.
"""

import json
import os
import re
import sys

TASK_ID = "JNI-EXC-001"
COMBINED = "combined.log"
META = "run-meta.json"

ORIGINAL_RE = re.compile(r"java\.lang\.IllegalStateException: JNI-EXC-001-A\b")
CAUSE_RE = re.compile(r"java\.lang\.UnsupportedOperationException: origin-A\b")
ABORT_RES = [
    re.compile(r"called with pending exception"),
    re.compile(r"JNI DETECTED ERROR IN APPLICATION"),
]
FATAL_RE = re.compile(
    r"FATAL EXCEPTION|JNI DETECTED ERROR IN APPLICATION|called with pending exception|fatal:|Fatal signal|System.exit called, status: [1-9]")
SENTINEL_A = "SENTINEL-JNI-EXC-001-A"
SENTINEL_B = "SENTINEL-JNI-EXC-001-B"
POS_DONE = "JNIEXC001_POS_DONE"
JSON_BEGIN = "JNIEXC001_JSON_BEGIN"
JSON_END = "JNIEXC001_JSON_END"
RUN_COMPLETE_RE = re.compile(
    r"^JNIEXC001_RUN_COMPLETE run_id=(\S+) runner_exit=(-?\d+)\s*$", re.M)
# Passive CheckJNI confirmation for the process under test (env/build-report.md
# §5): ATL hard-codes -Xcheck:jni; a normal run whose stderr shows
# "JVM launched successfully" and no rejected VM option has CheckJNI active.
CHECKJNI_OK = "JVM launched successfully"
CHECKJNI_BAD_RE = re.compile(r"Unknown argument|Unable to Launch JVM")

REJECT = 1
INTERNAL = 2
PASS = 0


class Verdict(Exception):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code
        self.detail = detail


def emit(case, run_id, verdict, code, detail, observations):
    print("%s %s case=%s run_id=%s detail=%s" % (
        "OK" if verdict == "PASS" else "REJECT", code, case, run_id or "?", detail))
    print("ANALYSIS " + json.dumps({
        "task_id": TASK_ID,
        "case": case,
        "run_id": run_id,
        "verdict": verdict,
        "code": code,
        "detail": detail,
        "observations": observations,
    }, sort_keys=True))
    return PASS if verdict == "PASS" else REJECT


def any_match(regexes, text):
    return any(r.search(text) for r in regexes)


def analyze(run_dir):
    meta_path = os.path.join(run_dir, META)
    if not os.path.isfile(meta_path):
        raise Verdict("EVIDENCE_META", "missing %s" % META)
    try:
        with open(meta_path, "r", encoding="utf-8") as fh:
            meta = json.load(fh)
    except (OSError, ValueError) as exc:
        raise Verdict("EVIDENCE_META", "unreadable %s: %s" % (META, exc))

    case = meta.get("case")
    run_id = meta.get("run_id")
    if meta.get("task_id") != TASK_ID:
        raise Verdict("EVIDENCE_META", "task_id %r != %r" % (meta.get("task_id"), TASK_ID))
    if case not in tuple("ABCDEFG"):
        raise Verdict("EVIDENCE_META", "unsupported case %r" % (case,))
    if not run_id:
        raise Verdict("EVIDENCE_META", "empty run_id")

    combined_path = os.path.join(run_dir, COMBINED)
    if not os.path.isfile(combined_path):
        raise Verdict("EVIDENCE_MISSING", "missing %s" % COMBINED)
    with open(combined_path, "rb") as fh:
        raw = fh.read()
    if len(raw) == 0:
        raise Verdict("EVIDENCE_EMPTY", "%s is empty" % COMBINED)
    text = raw.decode("utf-8", "replace")

    trailer = RUN_COMPLETE_RE.search(text)
    if trailer is None:
        raise Verdict("EVIDENCE_TRUNCATED",
                      "no JNIEXC001_RUN_COMPLETE trailer (capture incomplete/truncated)")
    if trailer.group(1) != run_id:
        raise Verdict("EVIDENCE_STALE",
                      "trailer run_id %s != meta run_id %s" % (trailer.group(1), run_id))

    if CHECKJNI_OK not in text or CHECKJNI_BAD_RE.search(text):
        raise Verdict("EVIDENCE_NO_CHECKJNI",
                      "no passive CheckJNI confirmation for this process "
                      "('%s' absent or a VM option was rejected)" % CHECKJNI_OK)

    if case == "A":
        return analyze_a(case, run_id, text)
    if case == "B":
        return analyze_b(case, run_id, text)
    if case == "C":
        return analyze_c(case, run_id, text)
    if any_match(ABORT_RES, text) or "WRONG_IDENTITY" in text or "SENTINEL_" in text:
        raise Verdict("EXTENDED_FAILURE", "wrong identity, invalid JNI or continued callback")
    if case == "D":
        if "JNIEXC_NATIVE_CAUGHT_D" not in text or "JNIEXC_RESUME_CAUGHT_D" not in text or "JNIEXC_D_DONE" not in text or FATAL_RE.search(text):
            raise Verdict("D_NOT_CAUGHT", "native exception identity / subsequent Looper not verified")
    else:
        if "JNIEXC_IDENTITY_" + case not in text:
            raise Verdict("IDENTITY_MISSING", "handler did not receive the identical Throwable")
        require_terminal(text)
        if case == "F" and "from Looper.loop" not in text:
            raise Verdict("WRONG_BOUNDARY", "Looper failure not routed at GLib boundary")
        if case == "G" and ("handler-failed-G" not in text or text.count("  caused by:") != 15):
            raise Verdict("CYCLE_REPORT", "bounded 16-node fallback not observed")
    return emit(case, run_id, "PASS", "PASS", "boundary and identity checks passed", {})


def require_terminal(text):
    if not re.search(r"System.exit called, status: 10|fatal: (?:uncaught|unhandled) Java exception from", text):
        raise Verdict("TERMINAL_MISSING", "no verified expected terminal path")


def analyze_a(case, run_id, text):
    if any_match(ABORT_RES, text):
        raise Verdict("A_SECONDARY_ABORT",
                      "CheckJNI pending-exception abort replaced the original failure")
    if SENTINEL_A in text:
        raise Verdict("A_SENTINEL_EXECUTED",
                      "post-failure sentinel executed (ATL continued past the failed onCreate)")
    if not ORIGINAL_RE.search(text):
        raise Verdict("A_ORIGINAL_MISSING",
                      "original IllegalStateException: JNI-EXC-001-A not present")
    if not CAUSE_RE.search(text):
        raise Verdict("A_CAUSE_MISSING",
                      "cause UnsupportedOperationException: origin-A not present")
    require_terminal(text)
    obs = {
        "original_present": True,
        "cause_present": True,
        "secondary_abort_present": False,
        "sentinel_present": False,
        "checkjni_active": True,
        "evidence_bytes": len(text.encode("utf-8", "replace")),
    }
    return emit(case, run_id, "PASS", "PASS", "original throwable is the terminal record", obs)


def analyze_c(case, run_id, text):
    if POS_DONE not in text:
        raise Verdict("C_MARKER_MISSING", "positive-control marker %s absent" % POS_DONE)
    if FATAL_RE.search(text):
        raise Verdict("C_FATAL", "uncaught/fatal record present in positive control")
    if JSON_BEGIN not in text or JSON_END not in text:
        raise Verdict("C_NO_EVIDENCE", "scenario JSON block absent")
    obs = {"marker_present": True, "fatal_present": False, "json_block_present": True,
           "checkjni_active": True}
    return emit(case, run_id, "PASS", "PASS", "positive control completed normally", obs)


def analyze_b(case, run_id, text):
    # Keep literal-class presence separate from the identity witness. The
    # fallback reports the subclass, not a full original stack trace.
    if any_match(ABORT_RES, text):
        raise Verdict("B_SECONDARY_ABORT",
                      "CheckJNI pending-exception abort in formatter-throws variant")
    if SENTINEL_B in text:
        raise Verdict("B_SENTINEL_EXECUTED",
                      "post-failure sentinel executed in formatter-throws variant")
    if ("JNIEXC_IDENTITY_B" not in text or "HostileThrowable: JNI-EXC-001-A" not in text or not CAUSE_RE.search(text)
            or "JNI-EXC-001-B formatter failure" not in text):
        raise Verdict("B_DIAGNOSTIC_MISSING", "original fallback / cause / formatter failure absent")
    require_terminal(text)
    obs = {
        "original_present": bool(ORIGINAL_RE.search(text)),
        "cause_present": bool(CAUSE_RE.search(text)),
        "formatter_failure_present": "JNI-EXC-001-B formatter failure" in text,
        "checkjni_active": True,
        "identity_witnessed": True,
        "fallback_only": True,
    }
    return emit(case, run_id, "PASS", "PASS",
                "identity witnessed; bounded fallback diagnostic preserved", obs)


def main(argv):
    if len(argv) != 2:
        print("ERROR internal usage: verify_jni_exc.py <RUN_DIR>")
        return INTERNAL
    run_dir = argv[1]
    if not os.path.isdir(run_dir):
        print("ERROR internal not a directory: %s" % run_dir)
        return INTERNAL
    try:
        return analyze(run_dir)
    except Verdict as v:
        return emit("?", "", "REJECT", v.code, v.detail, {})
    except Exception as exc:  # pragma: no cover - defensive
        print("ERROR internal unexpected: %r" % (exc,))
        return INTERNAL


if __name__ == "__main__":
    sys.exit(main(sys.argv))
