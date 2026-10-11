#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Regenerate the JNI-EXC-001 startup-boundary (H/I) analyzer self-test fixtures.

Each fixture is a RUN_DIR-shaped directory consumed by verify_startup_boundary.py:
    run-meta.json, stdout.log, atl-ui-stderr.log, atl-ui-stdout.log, combined.log
plus a fixtures-startup/cases.json listing the intended verdict for each.

Deterministic: re-running rewrites the same bytes (fixed run ids).
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures-startup")
APK_SHA = "e" * 64

JSON_H = ("JNIEXC001_JSON_BEGIN\n"
          '{"task_id":"JNI-EXC-001","case":"H","instance_id":"jniexc001-H-apk-fixture",'
          '"config":{"scenario":"application-construction",'
          '"throwable":"java.lang.IllegalStateException",'
          '"message":"JNI-EXC-001-H application-constructor"}}\n'
          "JNIEXC001_JSON_END")
JSON_I = ("JNIEXC001_JSON_BEGIN\n"
          '{"task_id":"JNI-EXC-001","case":"I","instance_id":"jniexc001-I-apk-fixture",'
          '"config":{"scenario":"missing-native-library",'
          '"library":"jniexc001-absent-library"}}\n'
          "JNIEXC001_JSON_END")

BODY_H = "\n".join([
    "java.lang.RuntimeException: java.lang.reflect.InvocationTargetException",
    "\tat android.atl.ATLLoadedApp.getApplication(ATLLoadedApp.java:204)",
    "Caused by: java.lang.reflect.InvocationTargetException",
    "Caused by: java.lang.IllegalStateException: JNI-EXC-001-H application-constructor",
    "\tat org.atl.tests.jniexc.h.ScenarioHApplication.<init>(ScenarioHApplication.java:32)",
    "I/android-translation-layer(523313): System.exit called, status: 10",
])

BODY_I = "\n".join([
    'java.lang.UnsatisfiedLinkError: dalvik.system.PathClassLoader[DexPathList[[zip file',
    ' "/tmp/jniexc-001-i.apk"],nativeLibraryDirectories=[.]]] couldn\'t find '
    '"libjniexc001-absent-library.so"',
    "\tat java.lang.Runtime.loadLibrary0(Runtime.java:1016)",
    "\tat org.atl.tests.jniexc.i.ScenarioIApplication.onCreate(ScenarioIApplication.java:38)",
    "I/android-translation-layer(523809): System.exit called, status: 10",
])

PENDING_ABORT = ("A/android-translation-layer: java_vm_ext.cc:563] JNI DETECTED ERROR IN "
                 "APPLICATION: JNI FindClass called with pending exception "
                 "java.lang.IllegalStateException: JNI-EXC-001-H application-constructor")

RUNNER_STDOUT = "\n".join([
    "ATL_UI_CLOSE_HELPER built",
    "ATL_UI_SCRIPT=wait:30;shot:startup;close:",
    "ATL_UI_STEP wait:30 rc=0",
    "ATL_UI_STEP shot:startup rc=0",
    "ATL_UI_STEP close rc=2",
    "ATL_UI_CLOSE atl_exited=yes waited=0s",
    "ATL_UI_STOP_BEGIN at=1791707325.457087331",
])

CASES = []


def write_fixture(name, case, atl_stderr, atl_stdout="", *, meta=True,
                  meta_run_id=None, trailer_run_id=None, trailer=True,
                  trailer_count=1, marker=True):
    d = os.path.join(FIX, name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    meta_run_id = meta_run_id or "fixture-run-1"
    if meta:
        with open(os.path.join(d, "run-meta.json"), "w", encoding="utf-8") as fh:
            json.dump({
                "task_id": "JNI-EXC-001",
                "case": case,
                "run_id": meta_run_id,
                "apk": "fixture://" + case,
                "apk_sha256": APK_SHA,
                "runner_exit": 0,
            }, fh, indent=2, sort_keys=True)
            fh.write("\n")

    runner = RUNNER_STDOUT + ("\nATL_UI_SMOKE_DONE" if marker else "") + "\n"
    with open(os.path.join(d, "stdout.log"), "w", encoding="utf-8") as fh:
        fh.write(runner)
    with open(os.path.join(d, "atl-ui-stdout.log"), "w", encoding="utf-8") as fh:
        fh.write(atl_stdout + ("\n" if atl_stdout else ""))
    if atl_stderr is not None:
        with open(os.path.join(d, "atl-ui-stderr.log"), "w", encoding="utf-8") as fh:
            fh.write(atl_stderr + ("\n" if atl_stderr else ""))

    combined = runner + "\n" + atl_stdout + "\n" + (atl_stderr or "") + "\n"
    if trailer:
        for _ in range(trailer_count):
            combined += "JNIEXC001_RUN_COMPLETE run_id=%s runner_exit=%d\n" % (
                trailer_run_id or meta_run_id, 0)
    with open(os.path.join(d, "combined.log"), "w", encoding="utf-8") as fh:
        fh.write(combined)


def add(name, case, atl_stderr, atl_stdout, verdict, code, **kw):
    write_fixture(name, case, atl_stderr, atl_stdout, **kw)
    CASES.append({"name": name, "dir": name, "exit": 0 if verdict == "PASS" else 1,
                  "verdict": verdict, "code": code})


def main():
    if os.path.isdir(FIX):
        shutil.rmtree(FIX)
    os.makedirs(FIX)

    # --- valid / acceptance fixtures --------------------------------------
    add("valid_h_candidate", "H", BODY_H, JSON_H, "PASS", "PASS", meta_run_id="fixture-h-1")
    add("valid_i_candidate", "I", BODY_I, JSON_I, "PASS", "PASS", meta_run_id="fixture-i-1")

    # --- evidence validity ------------------------------------------------
    add("empty_evidence", "H", "", JSON_H, "REJECT", "EVIDENCE_EMPTY",
        meta_run_id="fixture-empty-1", trailer=False)
    add("missing_evidence", "H", None, JSON_H, "REJECT", "EVIDENCE_MISSING",
        meta_run_id="fixture-missing-1")
    add("truncated_evidence", "H", BODY_H, JSON_H, "REJECT", "EVIDENCE_TRUNCATED",
        meta_run_id="fixture-trunc-1", trailer=False)
    add("duplicate_trailer", "H", BODY_H, JSON_H, "REJECT", "EVIDENCE_DUPLICATE",
        meta_run_id="fixture-dup-1", trailer_count=2)
    add("stale_run_id", "H", BODY_H, JSON_H, "REJECT", "EVIDENCE_STALE",
        meta_run_id="fixture-stale-META", trailer_run_id="fixture-stale-TRAILER")
    add("meta_missing", "H", BODY_H, JSON_H, "REJECT", "EVIDENCE_META",
        meta=False, trailer=True, meta_run_id="fixture-nometa-1")
    add("no_completion_marker", "H", BODY_H, JSON_H, "REJECT", "EVIDENCE_INCOMPLETE",
        meta_run_id="fixture-nomark-1", marker=False)
    add("no_scenario_evidence", "H", BODY_H, "", "REJECT", "EVIDENCE_NO_SCENARIO",
        meta_run_id="fixture-noscen-1")

    # --- scenario semantics ----------------------------------------------
    add("wrong_scenario", "H", BODY_I, JSON_I, "REJECT", "WRONG_SCENARIO",
        meta_run_id="fixture-wrong-1")
    add("original_missing", "H", "\n".join([
        "I/android-translation-layer(1): System.exit called, status: 10"]),
        JSON_H, "REJECT", "ORIGINAL_MISSING", meta_run_id="fixture-orig-1")
    add("sentinel_present", "H", BODY_H + "\nSENTINEL-JNI-EXC-001-H onCreate",
        JSON_H, "REJECT", "SENTINEL_EXECUTED", meta_run_id="fixture-sentinel-1")

    # --- real pre-fix abort signature (RED) -------------------------------
    add("abort_pending_exception", "H", BODY_H + "\n" + PENDING_ABORT, JSON_H,
        "REJECT", "SECONDARY_ABORT", meta_run_id="fixture-abort1-1")
    add("abort_runtime_aborting", "H", BODY_H + "\nRuntime aborting...", JSON_H,
        "REJECT", "SECONDARY_ABORT", meta_run_id="fixture-abort2-1")
    add("abort_fatal_signal", "H", BODY_H
        + "\nFatal signal 6 (SIGABRT), code -6 (SI_TKILL)", JSON_H,
        "REJECT", "SECONDARY_ABORT", meta_run_id="fixture-abort3-1")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(CASES, fh, indent=2, sort_keys=True)
        fh.write("\n")
    print("wrote %d fixtures to %s" % (len(CASES), FIX))


if __name__ == "__main__":
    sys.exit(main())