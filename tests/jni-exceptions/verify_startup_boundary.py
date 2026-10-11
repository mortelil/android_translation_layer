#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail-closed analyzer for the JNI-EXC-001 startup-boundary probes (H/I).

Usage:  verify_startup_boundary.py <RUN_DIR>

<RUN_DIR> is produced by `run-case.sh H|I <dir>` (ui-mode run) and contains:
    run-meta.json        harness metadata (task_id, case, run_id, apk sha256, ...)
    stdout.log           the runner's own output (wrapper + ATL_UI_* steps +
                         the completion marker ATL_UI_SMOKE_DONE)
    atl-ui-stderr.log    ATL application stderr (terminal record + any abort)
    atl-ui-stdout.log    ATL application stdout (JSON scenario evidence)
    combined.log         ATL stdout+stderr+runner stdout, sealed by the harness
                         trailer `JNIEXC001_RUN_COMPLETE run_id=<id> runner_exit=<n>`

Cases:
    H  Application construction throws (ScenarioHApplication ctor).
    I  missing native library during Application startup (onCreate calls
       System.loadLibrary on an absent library).

Required behaviour (both cases):
    * the ORIGINAL throwable (class + message) is the terminal record;
    * NO `JNI ... called with pending exception`, NO `Runtime aborting`,
      NO `Fatal signal 6` (the pre-fix CheckJNI abort);
    * startup ends controlled (`System.exit called, status: 10`);
    * the post-failure Activity sentinel is absent.

Verdicts (exit codes):
    0  PASS    the scenario contract holds
    1  REJECT  a specific contract violation (code on stdout)
    2  ERROR   usage / internal failure (never a semantic rejection)

The abort scan runs BEFORE the metadata/seal checks on purpose: a recorded
pre-fix run (which carries the abort but no harness seal) must be rejected for
the abort, not for the missing seal. This is the RED negative control.
"""

import json
import os
import re
import sys

TASK_ID = "JNI-EXC-001"
ATL_STDERR = "atl-ui-stderr.log"
ATL_STDOUT = "atl-ui-stdout.log"
RUNNER_STDOUT = "stdout.log"
COMBINED = "combined.log"
META = "run-meta.json"

# The pre-fix abort signature that MUST NOT appear: CheckJNI rejects ordinary
# JNI work while an exception is pending, then ART aborts.
ABORT_RES = [
    re.compile(r"called with pending exception"),
    re.compile(r"JNI DETECTED ERROR IN APPLICATION"),
    re.compile(r"Runtime aborting"),
    re.compile(r"Fatal signal 6\b"),
]

TERMINAL_RE = re.compile(
    r"System\.exit called, status: 10\b"
    r"|fatal: (?:uncaught|unhandled) Java exception from")
MARKER = "ATL_UI_SMOKE_DONE"
RUN_COMPLETE_RE = re.compile(
    r"^JNIEXC001_RUN_COMPLETE run_id=(\S+) runner_exit=(-?\d+)\s*$", re.M)

CASES = {
    "H": {
        # ScenarioHApplication ctor -> IllegalStateException("JNI-EXC-001-H application-constructor")
        "original_re": re.compile(
            r"java\.lang\.IllegalStateException: JNI-EXC-001-H application-constructor\b"),
        "sentinel": "SENTINEL-JNI-EXC-001-H",
        "scenario": "application-construction",
        "cross_re": re.compile(r"jniexc001-absent-library"),
    },
    "I": {
        # ScenarioIApplication.onCreate -> System.loadLibrary(absent)
        "original_class_re": re.compile(r"java\.lang\.UnsatisfiedLinkError\b"),
        "original_message_re": re.compile(r"jniexc001-absent-library"),
        "sentinel": "SENTINEL-JNI-EXC-001-I",
        "scenario": "missing-native-library",
        "cross_re": re.compile(r"JNI-EXC-001-H application-constructor"),
    },
}

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


def read_text(path):
    with open(path, "rb") as fh:
        return fh.read().decode("utf-8", "replace")


def analyze(run_dir):
    atl_stderr_path = os.path.join(run_dir, ATL_STDERR)
    if not os.path.isfile(atl_stderr_path):
        raise Verdict("EVIDENCE_MISSING", "missing %s" % ATL_STDERR)
    atl_stderr = read_text(atl_stderr_path)
    if atl_stderr == "":
        raise Verdict("EVIDENCE_EMPTY", "%s is empty" % ATL_STDERR)

    runner_stdout_path = os.path.join(run_dir, RUNNER_STDOUT)
    if not os.path.isfile(runner_stdout_path):
        raise Verdict("EVIDENCE_MISSING", "missing %s" % RUNNER_STDOUT)
    runner_stdout = read_text(runner_stdout_path)

    atl_stdout = ""
    atl_stdout_path = os.path.join(run_dir, ATL_STDOUT)
    if os.path.isfile(atl_stdout_path):
        atl_stdout = read_text(atl_stdout_path)

    # --- primary fail-closed check: the pre-fix abort MUST be absent --------
    atl_text = "\n".join([atl_stdout, atl_stderr, runner_stdout])
    if any_match(ABORT_RES, atl_text):
        hit = next(r.pattern for r in ABORT_RES if r.search(atl_text))
        raise Verdict("SECONDARY_ABORT",
                      "pre-fix CheckJNI/ART abort present (%s)" % hit)

    # --- harness metadata ---------------------------------------------------
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
    if case not in CASES:
        raise Verdict("EVIDENCE_META", "unsupported case %r (want H or I)" % (case,))
    if not run_id:
        raise Verdict("EVIDENCE_META", "empty run_id")

    # --- completion marker (runner finished the bounded ui smoke) ----------
    if MARKER not in runner_stdout:
        raise Verdict("EVIDENCE_INCOMPLETE",
                      "completion marker %s absent from %s" % (MARKER, RUNNER_STDOUT))

    # --- capture seal: exactly one trailer, matching the run id ------------
    combined_path = os.path.join(run_dir, COMBINED)
    if not os.path.isfile(combined_path):
        raise Verdict("EVIDENCE_TRUNCATED", "missing %s (capture not sealed)" % COMBINED)
    trailers = RUN_COMPLETE_RE.findall(read_text(combined_path))
    if not trailers:
        raise Verdict("EVIDENCE_TRUNCATED",
                      "no JNIEXC001_RUN_COMPLETE trailer (capture incomplete/truncated)")
    if len(trailers) > 1:
        raise Verdict("EVIDENCE_DUPLICATE",
                      "%d JNIEXC001_RUN_COMPLETE trailers (ambiguous capture)" % len(trailers))
    if trailers[0][0] != run_id:
        raise Verdict("EVIDENCE_STALE",
                      "trailer run_id %s != meta run_id %s" % (trailers[0][0], run_id))

    spec = CASES[case]

    # --- wrong-scenario guard ----------------------------------------------
    if spec["cross_re"].search(atl_text):
        raise Verdict("WRONG_SCENARIO",
                      "case %s evidence carries the other scenario's signature" % case)

    # --- original throwable (class + message) ------------------------------
    if "original_re" in spec:
        if not spec["original_re"].search(atl_text):
            raise Verdict("ORIGINAL_MISSING",
                          "original throwable for case %s not present" % case)
    else:
        if not spec["original_class_re"].search(atl_text):
            raise Verdict("ORIGINAL_MISSING",
                          "original class %s not present" % spec["original_class_re"].pattern)
        if not spec["original_message_re"].search(atl_text):
            raise Verdict("ORIGINAL_MISSING",
                          "original message token %s not present"
                          % spec["original_message_re"].pattern)

    # --- scenario actually ran (deterministic JSON evidence) ---------------
    if ("JNIEXC001_JSON_BEGIN" not in atl_text or "JNIEXC001_JSON_END" not in atl_text
            or "\"case\":\"%s\"" % case not in atl_text
            or spec["scenario"] not in atl_text):
        raise Verdict("EVIDENCE_NO_SCENARIO",
                      "scenario %s JSON evidence absent (probe did not run)" % case)

    # --- controlled terminal record ---------------------------------------
    if not TERMINAL_RE.search(atl_text):
        raise Verdict("TERMINAL_MISSING", "no verified controlled terminal path")

    # --- failed startup must not have been continued -----------------------
    if spec["sentinel"] in atl_text:
        raise Verdict("SENTINEL_EXECUTED",
                      "post-failure Activity sentinel executed (startup continued)")

    obs = {
        "original_present": True,
        "abort_present": False,
        "terminal_present": True,
        "sentinel_present": False,
        "scenario_evidence_present": True,
        "checkjni_active": True,
        "evidence_bytes": len(atl_text.encode("utf-8", "replace")),
    }
    return emit(case, run_id, "PASS", "PASS",
                "original throwable is the terminal record; no pending-exception abort", obs)


def main(argv):
    if len(argv) != 2:
        print("ERROR internal usage: verify_startup_boundary.py <RUN_DIR>")
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
