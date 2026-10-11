#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""make_sdk_fixtures.py - generate the D-SDK comparator fixture matrix.

Writes fixtures/ (overwriting) with a valid family plus one deliberately broken
family per rejection mode, and fixtures/cases.json listing the intended
(exit, code) for run_sdk_selftests.sh.
"""
import copy
import json
import os

from verify_sdk_profile import canonical_release

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
BEGIN = "SDKPROBE_JSON_BEGIN"
END = "SDKPROBE_JSON_END"


def doc(side, tsdk, scen, sdk_int, selected, phone, cls, *, min_sdk=21, sdk=None,
        prop=None, release=None, codename="REL", priv=False, avail=True, value=None,
        exception=None, rsi=None, read_errors=None, sentinel=True, task="D-SDK",
        run_kind=None, scenario=None):
    if sdk is None:
        sdk = str(sdk_int)
    if prop is None:
        prop = str(sdk_int)
    if rsi is None:
        rsi = sdk_int
    if release is None:
        # RELEASE is derived from SDK_INT, per the corrected per-run rule.
        release = canonical_release(sdk_int)
    if value is None and cls == "UNKNOWN":
        value = "unknown"
    if exception is None and cls == "SECURITY_EXCEPTION":
        exception = "java.lang.SecurityException"
    read_errors = list(read_errors) if read_errors else []
    d = {
        "task_id": task,
        "probe": "sdk-profile",
        "run_id": "fixture-%s-%d-%s" % (side, tsdk, scen),
        "run_kind": run_kind if run_kind is not None else side,
        "scenario": scenario if scenario is not None else scen,
        "status": "OK",
        "exceptions": [],
        "apk": {"sha256": None, "package": "org.atl.tests.sdk", "min_sdk": min_sdk,
                "target_sdk": tsdk},
        "build_version": {"SDK_INT": sdk_int, "SDK": sdk, "RELEASE": release,
                          "SDK_CODENAME": codename, "RESOURCES_SDK_INT": rsi,
                          "PREVIEW_SDK_INT": 0},
        "system_properties": {"ro.build.version.sdk": prop,
                              "ro.build.version.codename": codename,
                              "ro.build.version.release": release,
                              "read_errors": read_errors},
        "resource_selection": {
            "selected": selected,
            "value": {"base": "sentinel-base", "v21": "sentinel-v21",
                      "v28": "sentinel-v28"}[selected],
            "candidates": {"base": "sentinel-base", "v21": "sentinel-v21",
                           "v28": "sentinel-v28"},
            "expected_for_level": {"base": "base", "v21": "v21", "v28": "v28"}[selected],
            "agrees": True},
        "configuration": {"sdkVersion": sdk_int},
        "permissions": {"available": avail, "READ_PHONE_STATE": phone,
                        "READ_PRIVILEGED_PHONE_STATE": priv},
        "get_serial": {"attempted": True, "value": value, "exception": exception,
                       "message": "fixture", "classification": cls},
    }
    return d


def write_doc(path, d, sentinel=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    body = json.dumps(d, indent=1)
    if sentinel:
        body = BEGIN + "\n" + body + "\n" + END
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body + "\n")


def read_doc(path):
    """Read a (sentinel-wrapped or bare) fixture document back into a dict."""
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    bs = [i for i, l in enumerate(lines) if l.strip() == BEGIN]
    es = [i for i, l in enumerate(lines) if l.strip() == END]
    body = "\n".join(lines[bs[0] + 1:es[0]]) if bs and es else "\n".join(lines)
    return json.loads(body)


def base_scenarios():
    """(run_name, min_sdk, tsdk, override_sdk) + side -> (sdk_int, selected, phone, cls).

    Two APKs pin the two branches of the corrected profile
    `default = max(apk minSdk, 21)`:
      * minSdk 21 / targetSdk 28 -> default 21 (RELEASE "5.0"), pre-Q getSerial;
      * minSdk 28 / targetSdk 30 -> default 28 (RELEASE "9"), >=Q getSerial.
    The override for each run is chosen ABOVE that run's derived default, so the
    override run genuinely wins over both floors (28 vs 21; 30 vs 28).
    """
    return [
        ("m21-t28", 21, 28, 28, {
            "android": (37, "v28", False, "SECURITY_EXCEPTION"),
            "atl-default": (21, "v21", True, "UNKNOWN"),
            "atl-override": (28, "v28", True, "UNKNOWN"),
        }),
        ("m28-t30", 28, 30, 30, {
            "android": (37, "v28", False, "SECURITY_EXCEPTION"),
            "atl-default": (28, "v28", True, "SECURITY_EXCEPTION"),
            "atl-override": (30, "v28", True, "SECURITY_EXCEPTION"),
        }),
    ]


def build_family(root, *, sentinel=True, mutate=None, runs_filter=None):
    """Return the family dict; write all docs under root. mutate(family, ctx)."""
    os.makedirs(root, exist_ok=True)
    runs = []
    rel = {}
    for run_name, min_sdk, tsdk, ovr_sdk, sides in base_scenarios():
        if runs_filter is not None and run_name not in runs_filter:
            continue
        scens = []
        for scen in ("perm_none", "perm_phone_state"):
            entry = {"scenario": scen}
            for side, (sdk_int, selected, phone, cls) in sides.items():
                if scen == "perm_phone_state":
                    phone = True
                    if tsdk < 29:
                        cls = "UNKNOWN"
                    else:
                        cls = "SECURITY_EXCEPTION"
                p = os.path.join(root, run_name, side, scen + ".json")
                d = doc(side, tsdk, scen, sdk_int, selected, phone, cls, min_sdk=min_sdk,
                        sentinel=sentinel)
                write_doc(p, d, sentinel=sentinel)
                entry[{"android": "android", "atl-default": "atl_default",
                       "atl-override": "atl_override"}[side]] = os.path.relpath(p, root)
            scens.append(entry)
        runs.append({"name": run_name, "min_sdk": min_sdk, "target_sdk": tsdk,
                     "override_sdk": ovr_sdk, "scenarios": scens})
    fam = {"task_id": "D-SDK", "family": "sdk-profile", "expect_override_sdk": 28,
           "runs": runs}
    if mutate:
        mutate(fam, root)
    with open(os.path.join(root, "family.json"), "w", encoding="utf-8") as fh:
        json.dump(fam, fh, indent=1)
    return os.path.join(root, "family.json")


def main():
    if os.path.isdir(FIX):
        import shutil
        shutil.rmtree(FIX)
    os.makedirs(FIX)
    cases = []

    def case(name, exit_code, code, mutate=None, sentinel=True, allow_bare=False,
             runs_filter=None):
        root = os.path.join(FIX, name)
        fam = build_family(root, sentinel=sentinel, mutate=mutate,
                           runs_filter=runs_filter)
        cases.append({"name": name, "family": os.path.relpath(fam, HERE),
                      "exit": exit_code, "code": code, "allow_bare": allow_bare})

    case("valid", 0, "OK")

    # ---- corrected profile: default = max(apk minSdk, 21) ----------------
    # Each APK's own derived default is accepted (the two branches of the rule).
    case("min21_default_accepted", 0, "OK", runs_filter=("m21-t28",))
    case("min28_default_accepted", 0, "OK", runs_filter=("m28-t30",))

    def m_below(fam, root):
        # minSdk-28 APK reporting a default BELOW max(28,21)=28 must REJECT (the
        # app's declared minimum is a hard floor). Edit consistently so only the
        # derived-default rule fires.
        p = os.path.join(root, "m28-t30", "atl-default", "perm_none.json")
        d = read_doc(p)
        d["build_version"].update({"SDK_INT": 21, "SDK": "21", "RELEASE": "5.0",
                                   "RESOURCES_SDK_INT": 21})
        d["system_properties"]["ro.build.version.sdk"] = "21"
        d["resource_selection"].update({"selected": "v21", "value": "sentinel-v21",
                                        "expected_for_level": "v21"})
        d["configuration"]["sdkVersion"] = 21
        write_doc(p, d)
    case("default_below_floor", 1, "DEFAULT_PROFILE_WRONG", m_below)

    def m_above(fam, root):
        # minSdk-21 APK inflated to 28 with no basis must REJECT (unwarranted
        # inflation; the rule caps at the app's own declared minimum).
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        d = read_doc(p)
        d["build_version"].update({"SDK_INT": 28, "SDK": "28", "RELEASE": "9",
                                   "RESOURCES_SDK_INT": 28})
        d["system_properties"]["ro.build.version.sdk"] = "28"
        d["resource_selection"].update({"selected": "v28", "value": "sentinel-v28",
                                        "expected_for_level": "v28"})
        d["configuration"]["sdkVersion"] = 28
        write_doc(p, d)
    case("default_above_floor", 1, "DEFAULT_PROFILE_WRONG", m_above)

    def m_empty(fam, root):
        open(os.path.join(root, "m21-t28", "atl-default", "perm_none.json"), "w").close()
    case("empty_doc", 1, "EMPTY_EVIDENCE", m_empty)

    def m_missing(fam, root):
        fam["runs"][0]["scenarios"][0]["atl_override"] = "m21-t28/atl-override/nope.json"
    case("missing_doc", 1, "MISSING_EVIDENCE", m_missing)

    def m_trunc(fam, root):
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        t = open(p).read()
        # Keep both sentinels but cut the JSON body in half -> JSON_PARSE.
        open(p, "w").write(t[:len(t) // 2] + "\n" + END + "\n")
    case("truncated_doc", 1, "JSON_PARSE", m_trunc)

    def m_dup(fam, root):
        fam["runs"][0]["scenarios"].append(copy.deepcopy(fam["runs"][0]["scenarios"][0]))
    case("duplicate_scenario", 1, "DUPLICATE_SCENARIO", m_dup)

    def m_wrong_scen(fam, root):
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        t = open(p).read().replace('"scenario": "perm_none"', '"scenario": "wrong"')
        open(p, "w").write(t)
    case("wrong_scenario", 1, "WRONG_SCENARIO", m_wrong_scen)

    def m_wrong_kind(fam, root):
        p = os.path.join(root, "m21-t28", "android", "perm_none.json")
        t = open(p).read().replace('"run_kind": "android"', '"run_kind": "atl-default"')
        open(p, "w").write(t)
    case("wrong_run_kind", 1, "WRONG_RUN_KIND", m_wrong_kind)

    def m_incons(fam, root):
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        t = open(p).read().replace('"SDK": "21"', '"SDK": "22"')
        open(p, "w").write(t)
    case("inconsistent_sdk", 1, "INCONSISTENT_SDK", m_incons)

    def m_res(fam, root):
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        t = open(p).read().replace('"selected": "v21"', '"selected": "v28"')
        open(p, "w").write(t)
    case("resource_mismatch", 1, "RESOURCE_MISMATCH", m_res)

    def m_ovr(fam, root):
        p = os.path.join(root, "m21-t28", "atl-override", "perm_none.json")
        t = open(p).read()
        for a, b in (('"SDK_INT": 28', '"SDK_INT": 21'), ('"SDK": "28"', '"SDK": "21"'),
                     ('"RESOURCES_SDK_INT": 28', '"RESOURCES_SDK_INT": 21'),
                     ('"ro.build.version.sdk": "28"', '"ro.build.version.sdk": "21"'),
                     ('"RELEASE": "9"', '"RELEASE": "5.0"'),
                     ('"selected": "v28"', '"selected": "v21"'),
                     ('"value": "sentinel-v28"', '"value": "sentinel-v21"'),
                     ('"expected_for_level": "v28"', '"expected_for_level": "v21"')):
            t = t.replace(a, b)
        open(p, "w").write(t)
    case("override_not_applied", 1, "OVERRIDE_NOT_APPLIED", m_ovr)

    def m_serial(fam, root):
        p = os.path.join(root, "m28-t30", "atl-default", "perm_none.json")
        t = open(p).read().replace('"classification": "SECURITY_EXCEPTION"',
                                   '"classification": "UNKNOWN"') \
            .replace('"exception": "java.lang.SecurityException"', '"exception": null') \
            .replace('"value": null', '"value": "unknown"')
        open(p, "w").write(t)
    case("serial_semantics", 1, "SERIAL_SEMANTICS", m_serial)

    def m_prop(fam, root):
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        t = open(p).read().replace('"ro.build.version.sdk": "21"',
                                   '"ro.build.version.sdk": "22"')
        open(p, "w").write(t)
    case("prop_mismatch", 1, "PROP_MISMATCH", m_prop)

    # --- defect #1: a recorded read_error makes an item NOT_PROVEN ---
    def m_rsi_readerror(fam, root):
        # Real Android representation: the hidden field is unreadable, so the
        # probe records its reflective sentinel (-1) PLUS a read_error. That is
        # NOT_PROVEN, never a value mismatch.
        err = {"where": "Build.VERSION.RESOURCES_SDK_INT",
               "exception": "java.lang.NoSuchFieldException",
               "message": "RESOURCES_SDK_INT"}
        for run_name in ("m21-t28", "m28-t30"):
            p = os.path.join(root, run_name, "android", "perm_none.json")
            d = read_doc(p)
            d["build_version"]["RESOURCES_SDK_INT"] = -1
            d["system_properties"]["read_errors"].append(err)
            write_doc(p, d)
    case("readerror_not_proven", 0, "OK", m_rsi_readerror)

    def m_rsi_bad(fam, root):
        # No read_error recorded -> a differing RESOURCES_SDK_INT IS a real
        # mismatch and must still REJECT.
        p = os.path.join(root, "m21-t28", "atl-default", "perm_none.json")
        d = read_doc(p)
        assert not [e for e in d["system_properties"]["read_errors"]
                    if "RESOURCES_SDK_INT" in e["where"]]
        d["build_version"]["RESOURCES_SDK_INT"] = 22
        write_doc(p, d)
    case("rsi_mismatch_no_readerror", 1, "INCONSISTENT_SDK", m_rsi_bad)

    # --- defect #2: RELEASE is per-run, not cross-run ---
    # The valid family already pins default RELEASE "5.0" (SDK_INT 21) vs override
    # "9" (SDK_INT 28) vs android "17" (SDK_INT 37): differing across runs, each
    # correct within its own run -> accepted. This named case asserts that rule.
    case("release_cross_run_ok", 0, "OK")

    def m_rel_bad(fam, root):
        # RELEASE must match the document's OWN SDK_INT: the override is API 28,
        # whose canonical release is "9"; "5.0" contradicts its own run.
        p = os.path.join(root, "m21-t28", "atl-override", "perm_none.json")
        d = read_doc(p)
        d["build_version"]["RELEASE"] = "5.0"
        write_doc(p, d)
    case("release_contradiction", 1, "RELEASE_MISMATCH", m_rel_bad)

    # A bare (unsentineled) family must be REJECTed by default and accepted
    # with --allow-bare.
    case("bare_no_sentinel", 1, "MISSING_SENTINEL", sentinel=False)
    case("bare_allowed", 0, "OK", sentinel=False, allow_bare=True)

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=1)
    print("wrote %d cases to %s" % (len(cases), FIX))


if __name__ == "__main__":
    main()