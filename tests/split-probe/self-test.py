#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
#
# APP-COMPAT case F (split APKs / APKM) comparator self-test. Fail-closed.
#
# Builds a complete valid fixture, a complete degraded fixture and runtime
# rejection records, then mutates them. The comparator (compare.py) MUST accept
# every well-formed fixture and REJECT (nonzero) every mutated one for the
# intended reason. Exits 0 only when the whole matrix behaves.
#
#   python3 self-test.py [--refdir F-SPLIT/android-ref] [--keep DIR]
import argparse
import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COMPARE = os.path.join(HERE, "compare.py")

BASE_MEMBERS = [
    {"file": "base.apk", "path": "/x/base.apk", "has_dex": True, "is_base": True,
     "native_abis": {}, "assets": [], "scan_error": None},
    {"file": "split_config.x86_64.apk", "path": "/x/split_config.x86_64.apk",
     "has_dex": False, "is_base": False, "native_abis": {"libsplitprobe.so": "x86_64"},
     "assets": [], "scan_error": None},
    {"file": "split_config.xxhdpi.apk", "path": "/x/split_config.xxhdpi.apk",
     "has_dex": False, "is_base": False, "native_abis": {}, "assets": [],
     "scan_error": None},
    {"file": "split_config.en.apk", "path": "/x/split_config.en.apk",
     "has_dex": False, "is_base": False, "native_abis": {}, "assets": [],
     "scan_error": None},
]


def valid_evidence():
    return {
        "task_id": "APP-COMPAT-F-SPLIT",
        "run_id": "selftest-valid-0001",
        "status": "OK",
        "config": {
            "expected_splits": ["config.x86_64", "config.xxhdpi", "config.en"],
            "native_lib": "libsplitprobe.so",
            "native_value_expected": 23063,
            "density_sentinel": "xxhdpi_sentinel.txt",
            "language_sentinel": "lang_sentinel.txt",
        },
        "observations": {
            "member_source": "application_info",
            "selected_members": [m["path"] for m in BASE_MEMBERS],
            "members": copy.deepcopy(BASE_MEMBERS),
            "member_order": [m["file"] for m in BASE_MEMBERS],
            "container_unpacked": False,
            "base_first": True,
            "dex_from_non_base_splits": False,
            "class_loaded": True,
            "class_init_marker": "SPLITPROBE_LOADER_STATIC_INIT",
            "native_load_ok": True,
            "native_load_error": None,
            "native_lib_path": "/x/lib/x86_64/libsplitprobe.so",
            "native_from_split": "split_config.x86_64.apk",
            "native_abi": "x86_64",
            "native_value": 23063,
            "density_sentinel_value": "DENSITY-XXHDPI-SENTINEL",
            "density_sentinel_resolved": True,
            "language_sentinel_value": "LANG-EN-SENTINEL",
            "language_sentinel_resolved": True,
            "density_raw_value": None,
            "language_string_value": "LANG-EN-STRING-SENTINEL",
            "on_main_thread": True,
            "exceptions": [],
        },
        "exit_status": 0,
        "completed_marker": "SPLITPROBE_DONE",
    }


def degraded_evidence(case):
    # base-only: only the base member; native + sentinels unresolved.
    members = [copy.deepcopy(BASE_MEMBERS[0])]
    order = ["base.apk"]
    native_ok, nfrom, abi, nval = False, None, None, -1
    if case == "neg-arm64":
        members = [copy.deepcopy(BASE_MEMBERS[0]), {
            "file": "split_config.arm64_v8a.apk", "path": "/x/split_config.arm64_v8a.apk",
            "has_dex": False, "is_base": False,
            "native_abis": {"libsplitprobe.so": "arm64-v8a"}, "assets": [],
            "scan_error": None}]
        order = ["base.apk", "split_config.arm64_v8a.apk"]
        native_ok, nfrom, abi, nval = True, "split_config.arm64_v8a.apk", "arm64-v8a", 23063
    return {
        "task_id": "APP-COMPAT-F-SPLIT",
        "run_id": "selftest-%s-0001" % case,
        "status": "DEGRADED",
        "config": valid_evidence()["config"],
        "observations": {
            "member_source": "application_info",
            "selected_members": [m["path"] for m in members],
            "members": members,
            "member_order": order,
            "container_unpacked": False,
            "base_first": True,
            "dex_from_non_base_splits": False,
            "class_loaded": True,
            "class_init_marker": "SPLITPROBE_LOADER_STATIC_INIT",
            "native_load_ok": native_ok,
            "native_load_error": None,
            "native_lib_path": "/x/lib/x86_64/libsplitprobe.so" if native_ok else None,
            "native_from_split": nfrom,
            "native_abi": abi,
            "native_value": nval,
            "density_sentinel_value": None,
            "density_sentinel_resolved": False,
            "language_sentinel_value": None,
            "language_sentinel_resolved": False,
            "on_main_thread": True,
            "exceptions": [],
        },
        "exit_status": 104 if case == "neg-base-only" else 96,
        "completed_marker": "SPLITPROBE_DONE",
    }


def rejection_evidence(case, runtime, status, exit_status, diagnostic):
    return {
        "task_id": "APP-COMPAT-F-SPLIT",
        "run_id": "selftest-%s-%s-0001" % (case, runtime),
        "case": case,
        "runtime": runtime,
        "status": status,
        "exit_status": exit_status,
        "diagnostic": diagnostic,
        "exceptions": [],
    }


def run_compare(case, runtime, path):
    cmd = [sys.executable, COMPARE, "--case", case, "--runtime", runtime, path]
    return subprocess.run(cmd, capture_output=True, text=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refdir",
                    default="/home/morten/ATL/agent-work/APP-COMPAT/F-SPLIT/android-ref")
    ap.add_argument("--keep", default=None,
                    help="directory to keep generated fixtures in (default temp)")
    args = ap.parse_args()

    work = args.keep or tempfile.mkdtemp(prefix="fsplit-selftest-")
    os.makedirs(work, exist_ok=True)

    accepts = []   # (case, runtime, evidence)
    rejects = []   # (case, runtime, evidence, intent)

    v = valid_evidence()
    accepts.append(("valid", "android", v))
    accepts.append(("neg-base-only", "android", degraded_evidence("neg-base-only")))
    accepts.append(("neg-arm64", "android", degraded_evidence("neg-arm64")))
    accepts.append(("neg-arm64", "atl",
                    rejection_evidence("neg-arm64", "atl", "ABI_BLOCKED", 12,
                                       "ATLNativeAbi: arm64-v8a not the host ABI")))
    accepts.append(("neg-no-base", "atl",
                    rejection_evidence("neg-no-base", "atl", "SPLIT_NO_BASE", 13,
                                       "ATL SPLIT_NO_BASE: no base APK in the given set")))
    accepts.append(("neg-mismatch", "atl",
                    rejection_evidence("neg-mismatch", "atl", "SPLIT_MISMATCH", 14,
                                       "ATL SPLIT_MISMATCH: org.atl.tests.split2 != org.atl.tests.split")))
    accepts.append(("neg-no-base", "android",
                    rejection_evidence("neg-no-base", "android", "INSTALL_REJECTED", 1,
                                       "INSTALL_FAILED_INVALID_APK: Full install must include a base package")))
    accepts.append(("neg-mismatch", "android",
                    rejection_evidence("neg-mismatch", "android", "INSTALL_REJECTED", 1,
                                       "INSTALL_FAILED_INVALID_APK: Split null was defined multiple times")))

    def mut(fn):
        e = valid_evidence()
        fn(e)
        return e

    def m_sentinel(e):
        e["observations"]["density_sentinel_resolved"] = False

    def m_lang(e):
        e["observations"]["language_sentinel_resolved"] = False

    def m_wrongsplit(e):
        e["observations"]["native_from_split"] = "split_config.arm64_v8a.apk"

    def m_wrongabi(e):
        e["observations"]["native_abi"] = "arm64-v8a"

    def m_dexsplit(e):
        e["observations"]["dex_from_non_base_splits"] = True
        e["observations"]["members"][1]["has_dex"] = True
        e["observations"]["members"][1]["is_base"] = True

    def m_nonative(e):
        e["observations"]["native_load_ok"] = False

    def m_dup(e):
        e["observations"]["members"].append(copy.deepcopy(
            e["observations"]["members"][1]))

    def m_status(e):
        e["status"] = "DEGRADED"

    def m_task(e):
        e["task_id"] = "WRONG"

    def m_norunid(e):
        e["run_id"] = ""

    rejects.append(("valid", "android", mut(m_sentinel), "sentinel-unresolved"))
    rejects.append(("valid", "android", mut(m_lang), "lang-sentinel-unresolved"))
    rejects.append(("valid", "android", mut(m_wrongsplit), "native-from-wrong-split"))
    rejects.append(("valid", "android", mut(m_wrongabi), "native-wrong-abi"))
    rejects.append(("valid", "android", mut(m_dexsplit), "dex-from-resource/native-split"))
    rejects.append(("valid", "android", mut(m_nonative), "native-not-loaded"))
    rejects.append(("valid", "android", mut(m_dup), "duplicate-member"))
    rejects.append(("valid", "android", mut(m_status), "wrong-status-valid"))
    rejects.append(("valid", "android", mut(m_task), "wrong-task-id"))
    rejects.append(("valid", "android", mut(m_norunid), "empty-run-id"))
    # wrong status for each negative case
    rejects.append(("neg-mismatch", "atl",
                    rejection_evidence("neg-mismatch", "atl", "SPLIT_NO_BASE", 13, "x"),
                    "neg-mismatch-wrong-status"))
    rejects.append(("neg-no-base", "atl",
                    rejection_evidence("neg-no-base", "atl", "SPLIT_MISMATCH", 14, "x"),
                    "neg-no-base-wrong-status"))
    rejects.append(("neg-arm64", "atl",
                    rejection_evidence("neg-arm64", "atl", "OK", 0, "x"),
                    "neg-arm64-wrong-status"))
    rejects.append(("neg-base-only", "android",
                    rejection_evidence("neg-base-only", "android", "OK", 0, "x"),
                    "neg-base-only-wrong-status"))
    rejects.append(("neg-mismatch", "atl",
                    rejection_evidence("neg-mismatch", "atl", "SPLIT_MISMATCH", 14, ""),
                    "rejection-missing-diagnostic"))

    # extra accept fixtures from the real captured Android reference, if present.
    for name, case, runtime in [("results.json", "valid", "android"),
                                ("results-neg-base-only.json", "neg-base-only", "android"),
                                ("results-neg-arm64.json", "neg-arm64", "android")]:
        p = os.path.join(args.refdir, name)
        if os.path.isfile(p):
            try:
                with open(p) as fh:
                    accepts.append((case, runtime, json.load(fh)))
            except ValueError:
                pass

    fails = 0
    total = 0

    def write(ev, tag):
        p = os.path.join(work, tag + ".json")
        with open(p, "w") as fh:
            json.dump(ev, fh)
        return p

    for i, (case, runtime, ev) in enumerate(accepts):
        total += 1
        p = write(ev, "accept-%02d-%s" % (i, case))
        r = run_compare(case, runtime, p)
        if r.returncode != 0:
            fails += 1
            print("FAIL accept case=%s runtime=%s rc=%d: %s"
                  % (case, runtime, r.returncode, r.stdout.strip() or r.stderr.strip()))

    for i, (case, runtime, ev, intent) in enumerate(rejects):
        total += 1
        p = write(ev, "reject-%02d-%s" % (i, intent.replace("/", "_")))
        r = run_compare(case, runtime, p)
        if r.returncode == 0:
            fails += 1
            print("FAIL reject case=%s runtime=%s intent=%s: comparator ACCEPTED"
                  % (case, runtime, intent))

    # malformed-input rejects (raw bytes, not JSON).
    empty = os.path.join(work, "reject-empty.json")
    open(empty, "wb").close()
    trunc = os.path.join(work, "reject-truncated.json")
    with open(trunc, "wb") as fh:
        fh.write(json.dumps(valid_evidence()).encode()[: 40])
    for tag, p in [("empty", empty), ("truncated", trunc)]:
        total += 1
        r = run_compare("valid", "android", p)
        if r.returncode == 0:
            fails += 1
            print("FAIL reject-malformed intent=%s: comparator ACCEPTED" % tag)

    n_accept = len(accepts)
    n_reject = len(rejects) + 2
    print("self-test: accept=%d reject=%d total=%d failures=%d"
          % (n_accept, n_reject, total, fails))
    if args.keep:
        print("fixtures kept in %s" % work)
    elif not fails:
        shutil.rmtree(work, ignore_errors=True)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
