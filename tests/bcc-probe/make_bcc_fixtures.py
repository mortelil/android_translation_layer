#!/usr/bin/env python3
"""make_bcc_fixtures.py - regenerate the B-CALLBACKS comparator fixture matrix.

Builds one valid (android, candidate) pair plus deliberately broken variants,
each tagged with the exit code and REJECT code the comparator must produce.
Writes ``fixtures/*.json`` and ``fixtures/cases.json``.
"""
import copy
import importlib.util
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")

spec = importlib.util.spec_from_file_location(
    "verify_bcc_contract", os.path.join(HERE, "verify_bcc_contract.py"))
V = importlib.util.module_from_spec(spec)
spec.loader.exec_module(V)

APK_SHA = "bcc0f1e2d3c4b5a69788796a5b4c3d2e1f0" * 2 + "abcdef"[:4]  # 64 hex
ANDROID_RUN_ID = "bcc-apk-android-fixture-1"
CANDIDATE_RUN_ID = "bcc-apk-candidate-fixture-1"

PLATFORM_OBSERVED = [
    "L4:onConfigurationChanged:orientation=1:uiMode=33",
    "L5:onConfigurationChanged:orientation=1:uiMode=33",
    "activity:onConfigurationChanged:orientation=1:uiMode=33",
]


def base_side(run_id, platform_state, platform_trace):
    return {
        "task_id": V.TASK_ID,
        "run_id": run_id,
        "thread": "main",
        "status": "OK",
        "exceptions": [],
        "config": {"trigger": V.TRIGGER, "waitMs": 45000,
                   "targetSdk": 24, "minSdk": 24},
        "completion": {"marker": V.DONE_MARKER,
                       "sentinelBegin": V.BEGIN, "sentinelEnd": V.END,
                       "readyMarker": V.READY_MARKER},
        "apk": {"sha256": APK_SHA},
        "type_contract": dict(
            V.TYPE_CONTRACT_BOOLS,
            methods=dict(V.TYPE_CONTRACT_METHODS)),
        "constants": dict(V.TRIM_CONSTANTS),
        "registration": dict(V.REGISTRATION_BOOLS,
                             registerTarget="getApplicationContext"),
        "direct_dispatch": {
            "state": "DISPATCH_OBSERVED",
            "scenario": V.DIRECT_SCENARIO,
            "threads": list(V.DIRECT_THREADS),
            "trace": list(V.DIRECT_TRACE),
        },
        "platform_trigger": {
            "state": platform_state,
            "trigger": V.TRIGGER,
            "waitedMs": 45000,
            "trace": list(platform_trace),
        },
    }


def wrap(doc):
    return "%s\n%s\n%s\n" % (V.BEGIN, json.dumps(doc), V.END)


def write(name, text):
    with open(os.path.join(FIX, name), "w", encoding="utf-8") as fh:
        fh.write(text)
    return name


def main():
    os.makedirs(FIX, exist_ok=True)
    android = base_side(ANDROID_RUN_ID, "DISPATCH_OBSERVED", PLATFORM_OBSERVED)
    candidate = base_side(CANDIDATE_RUN_ID, "DISPATCH_OBSERVED", PLATFORM_OBSERVED)

    cases = []

    def add(name, amin, cmut, exit_code, code):
        a_doc = copy.deepcopy(android)
        c_doc = copy.deepcopy(candidate)
        if amin:
            amin(a_doc)
        if cmut:
            cmut(c_doc)
        write(name + ".android.json", wrap(a_doc))
        write(name + ".candidate.json", wrap(c_doc))
        cases.append({"name": name, "android": name + ".android.json",
                      "candidate": name + ".candidate.json",
                      "exit": exit_code, "code": code})

    # 1. valid pair (both sides observed identical platform dispatch)
    add("valid_complete_pair", None, None, 0, "OK")

    # 2. valid acceptance with dispatch coverage asymmetry (candidate has no
    #    platform trigger) - must still PASS.
    def candidate_not_triggered(d):
        d["platform_trigger"]["state"] = "NOT_TRIGGERED"
        d["platform_trigger"]["trace"] = []
    add("valid_candidate_not_triggered", None, candidate_not_triggered, 0, "OK")

    # 3. android side empty
    write("android_empty.android.json", "")
    write("android_empty.candidate.json", wrap(copy.deepcopy(candidate)))
    cases.append({"name": "android_empty", "android": "android_empty.android.json",
                  "candidate": "android_empty.candidate.json", "exit": 1,
                  "code": "EMPTY_EVIDENCE"})

    # 4. candidate empty
    add("candidate_empty", None, None, 1, "EMPTY_EVIDENCE")
    write("candidate_empty.candidate.json", "")

    # 5. candidate missing file
    write("candidate_missing_file.android.json", wrap(copy.deepcopy(android)))
    cases.append({"name": "candidate_missing_file",
                  "android": "candidate_missing_file.android.json",
                  "candidate": "candidate_missing_file.candidate.json",
                  "exit": 1, "code": "MISSING_EVIDENCE"})

    # 6. candidate malformed
    write("candidate_malformed.android.json", wrap(copy.deepcopy(android)))
    write("candidate_malformed.candidate.json",
          V.BEGIN + "\n{\"task_id\":\n" + V.END + "\n")
    cases.append({"name": "candidate_malformed",
                  "android": "candidate_malformed.android.json",
                  "candidate": "candidate_malformed.candidate.json",
                  "exit": 1, "code": "JSON_PARSE"})

    # 7. candidate truncated (half the JSON body)
    full = wrap(copy.deepcopy(candidate))
    body = full.splitlines()[1]
    write("candidate_truncated.android.json", wrap(copy.deepcopy(android)))
    write("candidate_truncated.candidate.json",
          V.BEGIN + "\n" + body[:len(body) // 2] + "\n" + V.END + "\n")
    cases.append({"name": "candidate_truncated",
                  "android": "candidate_truncated.android.json",
                  "candidate": "candidate_truncated.candidate.json",
                  "exit": 1, "code": "JSON_PARSE"})

    # 8. candidate bare (no sentinel)
    write("candidate_no_sentinel.android.json", wrap(copy.deepcopy(android)))
    write("candidate_no_sentinel.candidate.json", json.dumps(candidate) + "\n")
    cases.append({"name": "candidate_no_sentinel",
                  "android": "candidate_no_sentinel.android.json",
                  "candidate": "candidate_no_sentinel.candidate.json",
                  "exit": 1, "code": "MISSING_SENTINEL"})

    # 9. missing field
    add("candidate_missing_field", None, lambda d: d.pop("type_contract"),
        1, "MISSING_FIELD")
    # 10. missing constant
    add("candidate_missing_constant", None,
        lambda d: d["constants"].pop("TRIM_MEMORY_UI_HIDDEN"), 1, "MISSING_FIELD")
    # 11. invalid number (constant typed as a string)
    add("candidate_invalid_number", None,
        lambda d: d["constants"].__setitem__("TRIM_MEMORY_COMPLETE", "80"),
        1, "INVALID_NUMBER")
    # 12. duplicate trace event
    add("candidate_duplicate_trace", None,
        lambda d: d["direct_dispatch"]["trace"].insert(
            3, d["direct_dispatch"]["trace"][2]),
        1, "DIRECT_TRACE_DUPLICATE")
    # 13. reordered trace
    def reorder(d):
        t = d["direct_dispatch"]["trace"]
        t[2], t[3] = t[3], t[2]
    add("candidate_reordered_trace", None, reorder, 1, "DIRECT_TRACE_REORDERED")
    # 14. truncated trace (drop the last event)
    add("candidate_truncated_trace", None,
        lambda d: d["direct_dispatch"]["trace"].pop(), 1, "DIRECT_TRACE_TRUNCATED")
    # 15. wrong task
    add("candidate_wrong_task", None,
        lambda d: d.__setitem__("task_id", "J-UID"), 1, "WRONG_TASK")
    # 16. status error
    add("candidate_status_error", None,
        lambda d: d.__setitem__("status", "ERROR"), 1, "STATUS_NOT_OK")
    # 17. exceptions present
    add("candidate_exceptions", None,
        lambda d: d.__setitem__("exceptions", ["boom"]), 1, "EXCEPTIONS_PRESENT")
    # 18. config / scenario mismatch
    add("candidate_wrong_scenario", None,
        lambda d: d["config"].__setitem__("trigger", "orientation"),
        1, "CONFIG_MISMATCH")
    # 19. wrong thread
    add("candidate_wrong_thread", None,
        lambda d: d.__setitem__("thread", "bcc-waiter"), 1, "WRONG_THREAD")
    # 20. wrong owner (same run_id for both roles)
    add("candidate_wrong_owner", None,
        lambda d: d.__setitem__("run_id", ANDROID_RUN_ID), 1, "WRONG_OWNER")
    # 21. apk mismatch
    add("candidate_apk_mismatch", None,
        lambda d: d["apk"].__setitem__("sha256", "deadbeef" * 8), 1, "APK_MISMATCH")
    # 22. type contract mismatch
    add("candidate_type_contract_mismatch", None,
        lambda d: d["type_contract"].__setitem__("cc2ExtendsCC", False),
        1, "TYPE_CONTRACT_MISMATCH")
    # 23. constant mismatch
    add("candidate_constant_mismatch", None,
        lambda d: d["constants"].__setitem__("TRIM_MEMORY_MODERATE", 61),
        1, "CONSTANT_MISMATCH")
    # 24. registration mismatch
    add("candidate_registration_mismatch", None,
        lambda d: d["registration"].__setitem__("multipleRegisterThrew", True),
        1, "REGISTRATION_MISMATCH")
    # 25. direct dispatch state not observed
    add("candidate_direct_state", None,
        lambda d: d["direct_dispatch"].__setitem__("state", "NOT_TRIGGERED"),
        1, "DIRECT_DISPATCH_STATE")
    # 26. direct dispatch wrong callback thread
    add("candidate_direct_thread", None,
        lambda d: d["direct_dispatch"].__setitem__("threads", ["main", "worker"]),
        1, "DIRECT_THREAD_MISMATCH")
    # 27. platform NOT_TRIGGERED but with a trace (fabricated callback)
    def platform_inconsistent(d):
        d["platform_trigger"]["state"] = "NOT_TRIGGERED"
    add("candidate_platform_inconsistent", None, platform_inconsistent,
        1, "PLATFORM_TRACE_INCONSISTENT")
    # 28. invalid platform state (still PENDING)
    add("candidate_platform_pending", None,
        lambda d: d["platform_trigger"].__setitem__("state", "PENDING"),
        1, "PLATFORM_STATE_INVALID")
    # 29. both sides observed but disagree on the dispatched callbacks
    add("candidate_platform_divergence", None,
        lambda d: d["platform_trigger"].__setitem__(
            "trace", ["activity:onConfigurationChanged:orientation=1:uiMode=33"]),
        1, "PLATFORM_TRACE_DIVERGENCE")
    # 30. both sides identically wrong -> spec matrix catches it
    def wrong_constant(d):
        d["constants"].__setitem__("TRIM_MEMORY_RUNNING_LOW", 11)
    add("both_spec_mismatch", wrong_constant, wrong_constant, 1, "CONSTANT_MISMATCH")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
    print("wrote %d fixture cases to %s" % (len(cases), FIX))


if __name__ == "__main__":
    main()