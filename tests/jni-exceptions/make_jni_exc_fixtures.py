#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Regenerate the JNI-EXC-001 analyzer self-test fixtures.

Each fixture is a RUN_DIR-shaped directory consumed by verify_jni_exc.py:
    run-meta.json, combined.log
and a fixtures/cases.json listing the intended verdict for each.

Deterministic: re-running rewrites the same bytes (fixed run ids).
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
APK_SHA = "f" * 64

JSON_BLOCK = "\n".join([
    "JNIEXC001_JSON_BEGIN",
    '{"task_id":"JNI-EXC-001","case":"A","instance_id":"fixture"}',
    "JNIEXC001_JSON_END",
])

FIXED_A = "\n".join([
    JSON_BLOCK,
    "FATAL EXCEPTION: main",
    "System.exit called, status: 10",
    "java.lang.IllegalStateException: JNI-EXC-001-A",
    "\tat org.atl.tests.jniexc.a.ScenarioAActivity.onCreate(ScenarioAActivity.java:45)",
    "Caused by: java.lang.UnsupportedOperationException: origin-A",
    "\tat org.atl.tests.jniexc.a.ScenarioAActivity.onCreate(ScenarioAActivity.java:46)",
])

BASELINE_ABORT = ("JNI DETECTED ERROR IN APPLICATION: JNI GetObjectClass called with "
                  "pending exception java.lang.IllegalStateException: JNI-EXC-001-A")

CASES = []


def write_fixture(name, case, body, meta_run_id=None, trailer_run_id=None,
                  trailer=True, runner_exit=1, meta=True, checkjni=True):
    d = os.path.join(FIX, name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    if meta:
        with open(os.path.join(d, "run-meta.json"), "w", encoding="utf-8") as fh:
            json.dump({
                "task_id": "JNI-EXC-001",
                "case": case,
                "run_id": meta_run_id or trailer_run_id,
                "apk": "fixture://" + case,
                "apk_sha256": APK_SHA,
                "runner_exit": runner_exit,
            }, fh, indent=2, sort_keys=True)
            fh.write("\n")
    combined = body
    if body and checkjni:
        # Passive CheckJNI confirmation for the process under test.
        combined = "JVM launched successfully\n" + combined
    if body and not body.endswith("\n"):
        combined += "\n"
    if trailer:
        combined += "JNIEXC001_RUN_COMPLETE run_id=%s runner_exit=%d\n" % (
            trailer_run_id or meta_run_id, runner_exit)
    with open(os.path.join(d, "combined.log"), "w", encoding="utf-8") as fh:
        fh.write(combined)


def add(name, case, body, verdict, code, **kw):
    write_fixture(name, case, body, **kw)
    CASES.append({"name": name, "dir": name, "exit": 0 if verdict == "PASS" else 1,
                  "verdict": verdict, "code": code})


def main():
    if os.path.isdir(FIX):
        shutil.rmtree(FIX)
    os.makedirs(FIX)

    # --- valid / acceptance fixtures -------------------------------------
    add("valid_a_candidate", "A", FIXED_A, "PASS", "PASS",
        meta_run_id="fixture-a-1", runner_exit=0)
    add("valid_c_positive", "C", "\n".join([JSON_BLOCK, "JNIEXC001_POS_DONE"]),
        "PASS", "PASS", meta_run_id="fixture-c-1", runner_exit=0)

    # --- evidence validity ------------------------------------------------
    add("empty_evidence", "A", "", "REJECT", "EVIDENCE_EMPTY",
        meta_run_id="fixture-empty-1", trailer=False)
    add("truncated_evidence", "A", FIXED_A, "REJECT", "EVIDENCE_TRUNCATED",
        meta_run_id="fixture-trunc-1", trailer=False)
    add("meta_missing", "A", FIXED_A, "REJECT", "EVIDENCE_META",
        meta_run_id="fixture-nometa-1", meta=False)
    add("stale_run_id", "A", FIXED_A, "REJECT", "EVIDENCE_STALE",
        meta_run_id="fixture-stale-META", trailer_run_id="fixture-stale-TRAILER")
    add("no_checkjni", "A", FIXED_A, "REJECT", "EVIDENCE_NO_CHECKJNI",
        meta_run_id="fixture-nockjni-1", checkjni=False)

    # --- scenario A semantic fixtures ------------------------------------
    add("missing_original", "A", "\n".join([
        JSON_BLOCK,
        "Caused by: java.lang.UnsupportedOperationException: origin-A",
        "\tat somewhere",
    ]), "REJECT", "A_ORIGINAL_MISSING", meta_run_id="fixture-missorig-1")
    add("wrong_message", "A", "\n".join([
        JSON_BLOCK,
        "java.lang.IllegalStateException: JNI-EXC-001-WRONG",
        "Caused by: java.lang.UnsupportedOperationException: origin-A",
    ]), "REJECT", "A_ORIGINAL_MISSING", meta_run_id="fixture-wrongmsg-1")
    add("missing_cause", "A", "\n".join([
        JSON_BLOCK,
        "java.lang.IllegalStateException: JNI-EXC-001-A",
    ]), "REJECT", "A_CAUSE_MISSING", meta_run_id="fixture-misscause-1")
    add("sentinel_present", "A", "\n".join([
        FIXED_A,
        "SENTINEL-JNI-EXC-001-A onPostCreate",
    ]), "REJECT", "A_SENTINEL_EXECUTED", meta_run_id="fixture-sentinel-1")
    add("secondary_abort", "A", "\n".join([
        JSON_BLOCK,
        "java.lang.IllegalStateException: JNI-EXC-001-A",
        "Caused by: java.lang.UnsupportedOperationException: origin-A",
        BASELINE_ABORT,
    ]), "REJECT", "A_SECONDARY_ABORT", meta_run_id="fixture-abort-1")

    # --- scenario C semantic fixtures ------------------------------------
    add("c_marker_missing", "C", JSON_BLOCK, "REJECT", "C_MARKER_MISSING",
        meta_run_id="fixture-c-nomark-1")
    add("c_fatal", "C", "\n".join([
        JSON_BLOCK,
        "JNIEXC001_POS_DONE",
        "JNI DETECTED ERROR IN APPLICATION: JNI GetObjectClass called with pending exception",
    ]), "REJECT", "C_FATAL", meta_run_id="fixture-c-fatal-1")

    # --- scenario B (experimental) ---------------------------------------
    add("b_secondary_abort", "B", "\n".join([JSON_BLOCK, BASELINE_ABORT]),
        "REJECT", "B_SECONDARY_ABORT", meta_run_id="fixture-b-abort-1")
    add("b_no_abort", "B", JSON_BLOCK, "REJECT", "B_DIAGNOSTIC_MISSING",
        meta_run_id="fixture-b-ok-1", runner_exit=0)

    add("a_not_terminal", "A", FIXED_A.replace("System.exit called, status: 10", ""),
        "REJECT", "TERMINAL_MISSING", meta_run_id="not-terminal")
    add("valid_b_fallback", "B", "\n".join([
        JSON_BLOCK, "JNIEXC_IDENTITY_B", "HostileThrowable: JNI-EXC-001-A",
        "java.lang.UnsupportedOperationException: origin-A",
        "JNI-EXC-001-B formatter failure",
        "fatal: unhandled Java exception from Activity startup"]),
        "PASS", "PASS", meta_run_id="valid-b")
    add("c_native_fatal", "C", JSON_BLOCK + "\nJNIEXC001_POS_DONE\nfatal: uncaught Java exception from Looper.loop",
        "REJECT", "C_FATAL", meta_run_id="c-native-fatal")

    add("valid_d_caught", "D", "JNIEXC_NATIVE_CAUGHT_D\nJNIEXC_RESUME_CAUGHT_D\nJNIEXC_D_DONE",
        "PASS", "PASS", meta_run_id="valid-d")
    add("d_catch_missing", "D", "JNIEXC_D_DONE",
        "REJECT", "D_NOT_CAUGHT", meta_run_id="invalid-d")
    add("handler_wrong_identity", "E", "WRONG_IDENTITY\nJNIEXC_IDENTITY_E",
        "REJECT", "EXTENDED_FAILURE", meta_run_id="wrong-identity")
    add("handler_nonterminal", "E", "JNIEXC_IDENTITY_E",
        "REJECT", "TERMINAL_MISSING", meta_run_id="handler-nonterminal")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(CASES, fh, indent=2, sort_keys=True)
        fh.write("\n")
    print("wrote %d fixtures to %s" % (len(CASES), FIX))


if __name__ == "__main__":
    sys.exit(main())
