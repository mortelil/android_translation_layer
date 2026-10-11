#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
#
# APP-COMPAT case F (split APKs / APKM) evidence comparator. Fail-closed.
#
# Accepts a run-evidence JSON (the probe's SPLITPROBE_JSON block, or a
# runner-synthesised runtime-rejection record) and decides whether it is a
# valid observation for the requested controlled case and runtime.
#
# Usage:
#   compare.py --case CASE [--runtime android|atl] EVIDENCE.json
#
#   CASE := valid | neg-arm64 | neg-no-base | neg-mismatch | neg-base-only
#
# Exit codes: 0 = ACCEPT, 1 = REJECT, 2 = usage / unreadable input.
#
# Any parse error, missing field, duplicate member, wrong status, wrong ABI
# split or claimed success in a negative case is a hard REJECT (never a
# silent pass). Object identity is compared within one process/JSON only.
import argparse
import json
import sys

TASK_ID = "APP-COMPAT-F-SPLIT"
DONE_MARKER = "SPLITPROBE_DONE"
NATIVE_VALUE = 23063
NATIVE_LIB = "libsplitprobe.so"
X86_SPLIT = "split_config.x86_64.apk"
ARM_SPLIT = "split_config.arm64_v8a.apk"
VALID_MEMBERS = {
    "base.apk",
    "split_config.x86_64.apk",
    "split_config.xxhdpi.apk",
    "split_config.en.apk",
}
DENSITY_SENTINEL = "xxhdpi_sentinel.txt"
LANG_SENTINEL = "lang_sentinel.txt"
DENSITY_VALUE = "DENSITY-XXHDPI-SENTINEL"
LANG_VALUE = "LANG-EN-SENTINEL"
EXPECTED_SPLITS = ["config.x86_64", "config.xxhdpi", "config.en"]

# Accepted runtime status per (case, runtime). "*" = any runtime.
# ATL rejects the invalid sets up front; Android installs some of them and the
# app then reports DEGRADED, while install-multiple rejects the others.
EXPECTED_STATUS = {
    ("valid", "*"): {"OK"},
    ("neg-base-only", "*"): {"DEGRADED"},
    ("neg-arm64", "atl"): {"ABI_BLOCKED"},
    ("neg-arm64", "android"): {"DEGRADED"},
    ("neg-no-base", "atl"): {"SPLIT_NO_BASE"},
    ("neg-no-base", "android"): {"INSTALL_REJECTED"},
    ("neg-mismatch", "atl"): {"SPLIT_MISMATCH"},
    ("neg-mismatch", "android"): {"INSTALL_REJECTED"},
}
REJECTION_STATUS = {"SPLIT_NO_BASE", "SPLIT_MISMATCH", "ABI_BLOCKED",
                    "CONTAINER_UNSUPPORTED", "INSTALL_REJECTED"}


def reject(reasons):
    print("REJECT: " + "; ".join(reasons))
    sys.exit(1)


def accept(case, runtime):
    print("ACCEPT: case=%s runtime=%s" % (case, runtime))
    sys.exit(0)


def load_evidence(path):
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as exc:
        print("REJECT: cannot read evidence %s: %s" % (path, exc))
        sys.exit(1)
    if not raw.strip():
        print("REJECT: evidence file %s is empty" % path)
        sys.exit(1)
    try:
        return json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        print("REJECT: evidence %s is not complete valid JSON: %s" % (path, exc))
        sys.exit(1)


def basename(p):
    if not isinstance(p, str):
        return None
    return p.rstrip("/").rsplit("/", 1)[-1]


def check_common(ev, reasons):
    if not isinstance(ev, dict):
        reasons.append("evidence is not a JSON object")
        return
    if ev.get("task_id") != TASK_ID:
        reasons.append("task_id %r != %r" % (ev.get("task_id"), TASK_ID))
    rid = ev.get("run_id")
    if not isinstance(rid, str) or not rid.strip():
        reasons.append("missing/empty run_id (per-run unique id required)")
    if "exceptions" in ev and ev["exceptions"]:
        reasons.append("exceptions non-empty: %r" % (ev["exceptions"],))


def check_valid(ev, reasons):
    check_common(ev, reasons)
    obs = ev.get("observations")
    if not isinstance(obs, dict):
        reasons.append("valid case requires an observations object")
        return
    if ev.get("status") != "OK":
        reasons.append("status %r != OK" % (ev.get("status"),))
    if ev.get("exit_status") != 0:
        reasons.append("exit_status %r != 0" % (ev.get("exit_status"),))
    if ev.get("completed_marker") != DONE_MARKER:
        reasons.append("completed_marker %r != %r"
                       % (ev.get("completed_marker"), DONE_MARKER))

    cfg = ev.get("config")
    if not isinstance(cfg, dict):
        reasons.append("missing config object")
    else:
        if cfg.get("expected_splits") != EXPECTED_SPLITS:
            reasons.append("config.expected_splits %r != %r"
                           % (cfg.get("expected_splits"), EXPECTED_SPLITS))
        if cfg.get("native_lib") != NATIVE_LIB:
            reasons.append("config.native_lib %r != %r" % (cfg.get("native_lib"), NATIVE_LIB))
        if cfg.get("native_value_expected") != NATIVE_VALUE:
            reasons.append("config.native_value_expected %r != %d"
                           % (cfg.get("native_value_expected"), NATIVE_VALUE))
        if cfg.get("density_sentinel") != DENSITY_SENTINEL:
            reasons.append("config.density_sentinel mismatch")
        if cfg.get("language_sentinel") != LANG_SENTINEL:
            reasons.append("config.language_sentinel mismatch")

    members = obs.get("members")
    if not isinstance(members, list) or not members:
        reasons.append("observations.members missing/empty")
        return
    files = [m.get("file") for m in members if isinstance(m, dict)]
    if len(files) != len(set(files)):
        reasons.append("duplicate member entries: %r" % (files,))
    if len(members) != len(VALID_MEMBERS):
        reasons.append("expected %d members, got %d: %r"
                       % (len(VALID_MEMBERS), len(members), files))
    if set(files) != VALID_MEMBERS:
        reasons.append("member set %r != %r" % (sorted(files), sorted(VALID_MEMBERS)))

    order = obs.get("member_order")
    if not isinstance(order, list) or len(order) != len(files):
        reasons.append("member_order length %r != member count" % (order,))
    elif len(order) != len(set(order)):
        reasons.append("duplicate entries in member_order: %r" % (order,))
    if not order or basename(order[0]) != "base.apk":
        reasons.append("base is not first in member_order: %r" % (order,))

    # base must be the only dex-bearing member.
    if obs.get("base_first") is not True:
        reasons.append("base_first is not true")
    if obs.get("dex_from_non_base_splits") is not False:
        reasons.append("dex_from_non_base_splits is not false")
    for m in members:
        if not isinstance(m, dict):
            continue
        if m.get("is_base") is not True and m.get("has_dex") is True:
            reasons.append("non-base member %r carries dex (dex-compiled split)"
                           % (m.get("file"),))
    base = [m for m in members if isinstance(m, dict) and m.get("file") == "base.apk"]
    if not base or base[0].get("has_dex") is not True:
        reasons.append("base.apk has no dex")

    # class loading only from the base dex.
    if obs.get("class_loaded") is not True:
        reasons.append("class_loaded is not true (base dex not loaded)")

    # native from the x86_64 split only.
    if obs.get("native_load_ok") is not True:
        reasons.append("native_load_ok is not true (error=%r)"
                       % (obs.get("native_load_error"),))
    if obs.get("native_value") != NATIVE_VALUE:
        reasons.append("native_value %r != %d" % (obs.get("native_value"), NATIVE_VALUE))
    nfrom = obs.get("native_from_split")
    if basename(nfrom) != X86_SPLIT:
        reasons.append("native_from_split %r != %s (native-from-wrong-split)"
                       % (nfrom, X86_SPLIT))
    if obs.get("native_abi") != "x86_64":
        reasons.append("native_abi %r != x86_64" % (obs.get("native_abi"),))
    if not obs.get("native_lib_path"):
        reasons.append("native_lib_path is null (library not extracted)")

    # merged resource/asset sentinels resolve from the resource/language splits.
    if obs.get("density_sentinel_resolved") is not True:
        reasons.append("density sentinel unresolved")
    elif obs.get("density_sentinel_value") != DENSITY_VALUE:
        reasons.append("density sentinel value %r != %r"
                       % (obs.get("density_sentinel_value"), DENSITY_VALUE))
    if obs.get("language_sentinel_resolved") is not True:
        reasons.append("language sentinel unresolved")
    elif obs.get("language_sentinel_value") != LANG_VALUE:
        reasons.append("language sentinel value %r != %r"
                       % (obs.get("language_sentinel_value"), LANG_VALUE))

    # member observation sanity: the x86_64 split must expose the x86_64 ABI.
    x86 = [m for m in members if isinstance(m, dict) and m.get("file") == X86_SPLIT]
    if x86:
        abis = x86[0].get("native_abis") or {}
        if abis.get(NATIVE_LIB) != "x86_64":
            reasons.append("x86_64 split native_abis %r lacks %s=x86_64"
                           % (abis, NATIVE_LIB))
    if any(isinstance(m, dict) and m.get("scan_error") for m in members):
        reasons.append("member scan_error present")


def check_negative(ev, case, runtime, reasons):
    check_common(ev, reasons)
    if not isinstance(ev, dict):
        return
    allowed = EXPECTED_STATUS.get((case, runtime)) or EXPECTED_STATUS.get((case, "*"))
    if not allowed:
        reasons.append("no expectation table entry for case=%s runtime=%s" % (case, runtime))
        return
    status = ev.get("status")
    if status not in allowed:
        reasons.append("status %r not in %r for case=%s runtime=%s"
                       % (status, sorted(allowed), case, runtime))
    exit_status = ev.get("exit_status")
    if status == "DEGRADED":
        if not isinstance(exit_status, int) or exit_status <= 0:
            reasons.append("DEGRADED must carry a nonzero probe exit_status, got %r"
                           % (exit_status,))
        obs = ev.get("observations")
        if not isinstance(obs, dict):
            reasons.append("DEGRADED evidence lacks observations")
        else:
            # degraded sets must not claim the valid target outcome.
            if obs.get("dex_from_non_base_splits") is True:
                reasons.append("degraded set claims dex from a non-base split")
            if obs.get("native_load_ok") is True and \
                    basename(obs.get("native_from_split")) == X86_SPLIT and \
                    obs.get("native_value") == NATIVE_VALUE and \
                    obs.get("density_sentinel_resolved") is True and \
                    obs.get("language_sentinel_resolved") is True:
                reasons.append("degraded set claims a fully successful valid run")
    elif status in REJECTION_STATUS:
        if not isinstance(exit_status, int) or exit_status == 0:
            reasons.append("rejection status %s must carry a nonzero exit_status" % status)
        diag = ev.get("diagnostic")
        if not isinstance(diag, str) or not diag.strip():
            reasons.append("rejection status %s requires a non-empty diagnostic" % status)
        if not isinstance(ev.get("run_id"), str) or not ev["run_id"].strip():
            reasons.append("rejection requires a per-run unique run_id")


def main():
    ap = argparse.ArgumentParser(description="APP-COMPAT F-SPLIT comparator")
    ap.add_argument("--case", required=True,
                    choices=["valid", "neg-arm64", "neg-no-base",
                             "neg-mismatch", "neg-base-only"])
    ap.add_argument("--runtime", default="atl", choices=["android", "atl"])
    ap.add_argument("evidence")
    args = ap.parse_args()

    ev = load_evidence(args.evidence)
    reasons = []
    if args.case == "valid":
        check_valid(ev, reasons)
    else:
        check_negative(ev, args.case, args.runtime, reasons)

    if reasons:
        reject(reasons)
    accept(args.case, args.runtime)


if __name__ == "__main__":
    main()
