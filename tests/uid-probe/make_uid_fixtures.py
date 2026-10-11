#!/usr/bin/env python3
"""make_uid_fixtures.py - regenerate the J-UID comparator fixture matrix.

Builds one valid (android, candidate) pair and a set of deliberately broken
variants, each tagged with the exit code and REJECT code the comparator must
produce. Writes ``fixtures/*.json`` and ``fixtures/cases.json``.

The valid pair is synthetic but AOSP-consistent: the android side reports a real
per-app sandbox UID (10123) and the candidate the pinned ATL logical UID (10000).
The predicate rows come from the comparator's AOSP-derived expected matrix.
"""
import copy
import importlib.util
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")

spec = importlib.util.spec_from_file_location(
    "verify_uid_semantics", os.path.join(HERE, "verify_uid_semantics.py"))
V = importlib.util.module_from_spec(spec)
spec.loader.exec_module(V)

APK_SHA = "0f1e2d3c4b5a69788796a5b4c3d2e1f0" * 2  # 64 hex chars
ANDROID_RUN_ID = "j-uid-apk-android-fixture-1"
CANDIDATE_RUN_ID = "j-uid-apk-candidate-fixture-1"


def base_side(run_id, my_uid):
    rows = []
    for uid in V.VALUE_SET:
        want = V.EXPECTED_MATRIX[uid]
        rows.append({"uid": uid,
                     "isApplicationUid": want["isApplicationUid"],
                     "isApp": want["isApp"],
                     "isIsolated": want["isIsolated"]})
    app_id = my_uid % V.PER_USER_RANGE
    return {
        "task_id": V.TASK_ID,
        "run_id": run_id,
        "thread": "main",
        "status": "OK",
        "exceptions": [],
        "config": {"valueSet": list(V.VALUE_SET), "targetSdk": 24, "minSdk": 24},
        "completion": {"marker": V.DONE_MARKER,
                       "sentinelBegin": V.BEGIN, "sentinelEnd": V.END},
        "apk": {"sha256": APK_SHA},
        "reachability": {
            "Process.isApplicationUid(int)": "direct",
            "Process.isIsolatedUid(int)": "direct",
            "Process.isIsolated()": "direct",
            "Process.isIsolated(int)": "reflection",
            "UserHandle.isApp": "reflection",
            "UserHandle.isIsolated": "reflection",
            "UserHandle.isCore": "reflection",
            "UserHandle.getAppId": "reflection",
            "UserHandle.getUserId": "reflection",
        },
        "constants": {
            "firstApplicationUid": V.FIRST_APPLICATION_UID,
            "lastApplicationUid": V.LAST_APPLICATION_UID,
            "firstIsolatedUid": V.FIRST_ISOLATED_UID,
            "lastIsolatedUid": V.LAST_ISOLATED_UID,
            "firstAppZygoteIsolatedUid": V.FIRST_APP_ZYGOTE_ISOLATED_UID,
            "lastAppZygoteIsolatedUid": V.LAST_APP_ZYGOTE_ISOLATED_UID,
            "perUserRange": V.PER_USER_RANGE,
        },
        "predicates": rows,
        "identity": {
            "myUid": my_uid,
            "userId": 0,
            "appId": app_id,
            "isApplicationUid_myUid": True,
            "isIsolatedUid_myUid": False,
            "isIsolated_noarg": False,
            "isIsolated_int_myUid": False,
            "isApp_myUid": True,
            "isIsolated_uh_myUid": False,
            "isCore_myUid": False,
            "firstApplicationUid": V.FIRST_APPLICATION_UID,
            "lastApplicationUid": V.LAST_APPLICATION_UID,
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
    android = base_side(ANDROID_RUN_ID, 10123)
    candidate = base_side(CANDIDATE_RUN_ID, 10000)

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

    # 1. valid pair
    add("valid_complete_pair", None, None, 0, "OK")

    # android side empty
    write("android_empty.android.json", "")
    write("android_empty.candidate.json", wrap(copy.deepcopy(candidate)))
    cases.append({"name": "android_empty", "android": "android_empty.android.json",
                  "candidate": "android_empty.candidate.json", "exit": 1,
                  "code": "EMPTY_EVIDENCE"})

    # candidate empty
    add("candidate_empty", None, None, 1, "EMPTY_EVIDENCE")
    write("candidate_empty.candidate.json", "")

    # candidate missing file
    write("candidate_missing_file.android.json", wrap(copy.deepcopy(android)))
    cases.append({"name": "candidate_missing_file",
                  "android": "candidate_missing_file.android.json",
                  "candidate": "candidate_missing_file.candidate.json",
                  "exit": 1, "code": "MISSING_EVIDENCE"})

    # candidate malformed
    write("candidate_malformed.android.json", wrap(copy.deepcopy(android)))
    write("candidate_malformed.candidate.json", V.BEGIN + "\n{\"task_id\":\n" + V.END + "\n")
    cases.append({"name": "candidate_malformed",
                  "android": "candidate_malformed.android.json",
                  "candidate": "candidate_malformed.candidate.json",
                  "exit": 1, "code": "JSON_PARSE"})

    # candidate truncated
    full = wrap(copy.deepcopy(candidate))
    lines = full.splitlines()
    body = lines[1]
    write("candidate_truncated.android.json", wrap(copy.deepcopy(android)))
    write("candidate_truncated.candidate.json",
          V.BEGIN + "\n" + body[:len(body) // 2] + "\n" + V.END + "\n")
    cases.append({"name": "candidate_truncated",
                  "android": "candidate_truncated.android.json",
                  "candidate": "candidate_truncated.candidate.json",
                  "exit": 1, "code": "JSON_PARSE"})

    # candidate bare (no sentinel)
    write("candidate_no_sentinel.android.json", wrap(copy.deepcopy(android)))
    write("candidate_no_sentinel.candidate.json", json.dumps(candidate) + "\n")
    cases.append({"name": "candidate_no_sentinel",
                  "android": "candidate_no_sentinel.android.json",
                  "candidate": "candidate_no_sentinel.candidate.json",
                  "exit": 1, "code": "MISSING_SENTINEL"})

    # missing field
    add("candidate_missing_field", None, lambda d: d.pop("identity"), 1, "MISSING_FIELD")
    # missing input
    add("candidate_missing_input", None, lambda d: d["predicates"].pop(3), 1, "MISSING_INPUT")
    # duplicate input
    add("candidate_duplicate_input", None,
        lambda d: d["predicates"].insert(2, copy.deepcopy(d["predicates"][2])),
        1, "DUPLICATE_INPUT")
    # reordered input
    def reorder(d):
        d["predicates"][0], d["predicates"][1] = d["predicates"][1], d["predicates"][0]
    add("candidate_reordered", None, reorder, 1, "REORDERED_INPUT")
    # invalid number
    add("candidate_invalid_number", None,
        lambda d: d["predicates"][5].__setitem__("isApp", "yes"), 1, "INVALID_NUMBER")
    # unreachable surface
    add("candidate_unreachable", None,
        lambda d: d["predicates"][5].__setitem__("isApp", None), 1, "UNREACHABLE_SURFACE")
    # wrong task
    add("candidate_wrong_task", None,
        lambda d: d.__setitem__("task_id", "FONT-003"), 1, "WRONG_TASK")
    # status error
    add("candidate_status_error", None,
        lambda d: d.__setitem__("status", "ERROR"), 1, "STATUS_NOT_OK")
    # exceptions present
    add("candidate_exceptions", None,
        lambda d: d.__setitem__("exceptions", ["boom"]), 1, "EXCEPTIONS_PRESENT")
    # config mismatch
    add("candidate_config_mismatch", None,
        lambda d: d["config"]["valueSet"].pop(), 1, "CONFIG_MISMATCH")
    # wrong thread
    add("candidate_wrong_thread", None,
        lambda d: d.__setitem__("thread", "probe-worker-9"), 1, "WRONG_THREAD")
    # wrong owner (same run_id for both roles)
    add("candidate_wrong_owner", None,
        lambda d: d.__setitem__("run_id", ANDROID_RUN_ID), 1, "WRONG_OWNER")
    # apk mismatch
    add("candidate_apk_mismatch", None,
        lambda d: d["apk"].__setitem__("sha256", "deadbeef" * 8), 1, "APK_MISMATCH")
    # constant mismatch
    add("candidate_constant_mismatch", None,
        lambda d: d["constants"].__setitem__("lastApplicationUid", 20000),
        1, "CONSTANT_MISMATCH")
    # predicate mismatch (candidate only)
    add("candidate_predicate_mismatch", None,
        lambda d: d["predicates"][5].__setitem__("isApp", not d["predicates"][5]["isApp"]),
        1, "PREDICATE_MISMATCH")
    # identity candidate pin
    add("candidate_identity_pin", None,
        lambda d: (d["identity"].__setitem__("myUid", 10001),
                   d["identity"].__setitem__("appId", 10001)),
        1, "IDENTITY_CANDIDATE_PIN")
    # identity structural violation (appId inconsistent with myUid)
    add("candidate_identity_structure", None,
        lambda d: d["identity"].__setitem__("appId", 20000),
        1, "IDENTITY_STRUCTURE")
    # both sides identically wrong -> spec matrix catches it
    def flip_both(d):
        d["predicates"][5].__setitem__("isApp", not d["predicates"][5]["isApp"])
    add("both_spec_mismatch", flip_both, flip_both, 1, "SPEC_MISMATCH")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
    print("wrote %d fixture cases to %s" % (len(cases), FIX))


if __name__ == "__main__":
    main()
