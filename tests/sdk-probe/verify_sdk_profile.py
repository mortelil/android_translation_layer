#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""verify_sdk_profile.py - fail-closed comparator for the D-SDK evidence family.

Usage:
    verify_sdk_profile.py <family.json> [--allow-bare]

`family.json` names, per APK (targetSdk) and per scenario, the three probe
documents to compare: the Android reference, the ATL default run and the ATL
`--sdk-int 28` override run. Paths are relative to the family.json directory.

Every probe document must be sentinel-wrapped (SDKPROBE_JSON_BEGIN / _END);
--allow-bare also accepts the app's plain files/results.json form.

The comparator is fail-closed: empty / missing / truncated / unsupported /
duplicate / wrong-scenario / wrong-run-kind / inconsistent-number evidence is
REJECTed with a nonzero exit BEFORE any semantic comparison. It then checks

* per-document self-consistency: SDK_INT == int(SDK), ro.build.version.sdk agrees,
  SDK_CODENAME / codename agree, RELEASE matches the canonical release for that
  document's OWN SDK_INT, and the selected version-qualified resource matches the
  reported level. An item whose value carries a recorded read_error (hidden-API
  reflection block) is NOT_PROVEN and is never read as a mismatch - this covers
  both RESOURCES_SDK_INT (probe sentinel) and RELEASE;
* the AOSP getSerial semantics for the run's targetSdk + observed permission;
* the ATL default profile against the corrected rule
  `expect_default_sdk = max(<APK declared minSdk>, 21)` - derived per run from
  the probe document's own `apk.min_sdk`, never a fixed constant. A default below
  that floor OR above it (unwarranted inflation) is DEFAULT_PROFILE_WRONG;
* the ATL `--sdk-int N` override profile (N where overridden, SDK exact). The
  override must win over BOTH derived defaults; RELEASE is validated per run
  against that run's SDK_INT and may therefore legitimately differ between the
  default (21 -> "5.0") and the override (30 -> "11"); RELEASE is NOT required
  to be equal across runs;
* the Android reference classification where the permission state is comparable
  (differences are documented, not silently ignored).

Comparability (honest): the Android reference device's own SDK is 37, so its
reported SDK_INT/RELEASE/SDK_CODENAME and its resource selection are NOT
comparable to ATL's - only the getSerial semantics (same APK, same targetSdk,
same observed permission) and each side's internal SDK<->resource consistency
are. The ATL-reported level itself is ATL-platform-specific and is checked only
against the derived profile, never against the reference device's level.

Exit: 0 accept (`OK ...`), 1 reject (`REJECT <CODE> <reason>`), 2 usage/internal.
"""
import json
import os
import sys

BEGIN = "SDKPROBE_JSON_BEGIN"
END = "SDKPROBE_JSON_END"
TASK_ID = "D-SDK"
# The corrected profile floors the reported API level at the platform's own real
# level (ATL_PLATFORM_SDK_INT, 21 / LOLLIPOP) and then raises it to the primary
# app's declared minimum when that is higher: expect_default_sdk =
# max(app minSdk, PLATFORM_SDK_FLOOR). This is a contract constant, not a
# per-device observation.
PLATFORM_SDK_FLOOR = 21
CLASSES = {"SECURITY_EXCEPTION", "UNKNOWN", "OTHER"}
RUN_KINDS = {"android", "atl-default", "atl-override"}


class Reject(Exception):
    def __init__(self, code, reason):
        super().__init__("%s %s" % (code, reason))
        self.code = code
        self.reason = reason


class UsageError(Exception):
    pass


def reject(code, reason):
    raise Reject(code, reason)


def usage(msg):
    raise UsageError(msg)


def expected_qualifier(level):
    if level >= 28:
        return "v28"
    if level >= 21:
        return "v21"
    return "base"


def expected_default_sdk(min_sdk):
    """Corrected profile: max(app declared minSdk, platform floor)."""
    return max(min_sdk, PLATFORM_SDK_FLOOR)


# Canonical AOSP API level -> Build.VERSION.RELEASE (android.os.Build.VERSION
# release names). Used to check RELEASE against its OWN run's SDK_INT; a profile
# that changes the API level legitimately changes RELEASE (21 -> "5.0", 28 -> "9",
# the emulator reference 37 -> "17").
RELEASES = {
    21: "5.0", 22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1", 26: "8.0",
    27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L",
    33: "13", 34: "14", 35: "15", 36: "16", 37: "17",
}


def canonical_release(level):
    return RELEASES.get(level)


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def load_doc(path, label, allow_bare):
    if not isinstance(path, str) or not path:
        reject("MISSING_EVIDENCE", "%s path is missing" % label)
    if not os.path.isfile(path):
        reject("MISSING_EVIDENCE", "%s file not found: %s" % (label, path))
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError as exc:
        reject("UNREADABLE_EVIDENCE", "%s unreadable: %s" % (label, exc))
    if not text.strip():
        reject("EMPTY_EVIDENCE", "%s evidence is empty" % label)
    lines = text.splitlines()
    bs = [i for i, l in enumerate(lines) if l.strip() == BEGIN]
    es = [i for i, l in enumerate(lines) if l.strip() == END]
    if bs or es:
        if len(bs) != 1 or len(es) != 1:
            reject("SENTINEL_INVALID", "%s has %d BEGIN/%d END" % (label, len(bs), len(es)))
        if bs[0] > es[0]:
            reject("SENTINEL_INVALID", "%s END precedes BEGIN" % label)
        body = "\n".join(lines[bs[0] + 1:es[0]])
    else:
        if not allow_bare:
            reject("MISSING_SENTINEL", "%s not sentinel-wrapped" % label)
        body = text
    body = body.strip()
    if not body:
        reject("EMPTY_EVIDENCE", "%s body is empty" % label)
    try:
        doc = json.loads(body)
    except Exception as exc:  # noqa: BLE001
        reject("JSON_PARSE", "%s body is not valid JSON: %s" % (label, exc))
    if not isinstance(doc, dict):
        reject("NOT_AN_OBJECT", "%s JSON is not an object" % label)
    return doc


def get(doc, key, label):
    if key not in doc:
        reject("MISSING_FIELD", "%s missing %r" % (label, key))
    return doc[key]


def error_mentions(doc, needle):
    errs = doc.get("system_properties", {}).get("read_errors")
    if not isinstance(errs, list):
        return False
    for e in errs:
        if isinstance(e, dict) and needle in str(e.get("where", "")):
            return True
    return False


def validate_doc(doc, label, run_kind, scenario, target_sdk):
    if get(doc, "task_id", label) != TASK_ID:
        reject("WRONG_TASK", "%s task_id %r != %r" % (label, doc.get("task_id"), TASK_ID))
    rid = get(doc, "run_id", label)
    if not isinstance(rid, str) or not rid:
        reject("MISSING_FIELD", "%s run_id is empty" % label)
    rk = get(doc, "run_kind", label)
    if rk != run_kind:
        reject("WRONG_RUN_KIND", "%s run_kind %r != expected %r" % (label, rk, run_kind))
    sc = get(doc, "scenario", label)
    if sc != scenario:
        reject("WRONG_SCENARIO", "%s scenario %r != expected %r" % (label, sc, scenario))
    if get(doc, "status", label) != "OK":
        reject("STATUS_NOT_OK", "%s status %r" % (label, doc.get("status")))
    exc = get(doc, "exceptions", label)
    if not isinstance(exc, list):
        reject("MISSING_FIELD", "%s exceptions is not a list" % label)
    if exc:
        reject("EXCEPTIONS_PRESENT", "%s reported exceptions: %r" % (label, exc))

    apk = get(doc, "apk", label)
    if not isinstance(apk, dict):
        reject("MISSING_FIELD", "%s apk is not an object" % label)
    tsdk = apk.get("target_sdk")
    if not is_int(tsdk):
        reject("INVALID_NUMBER", "%s apk.target_sdk not an int: %r" % (label, tsdk))
    if tsdk != target_sdk:
        reject("CONFIG_MISMATCH", "%s target_sdk %d != run target_sdk %d"
               % (label, tsdk, target_sdk))
    min_sdk = apk.get("min_sdk")
    if not is_int(min_sdk):
        reject("INVALID_NUMBER", "%s apk.min_sdk not an int: %r" % (label, min_sdk))

    bv = get(doc, "build_version", label)
    if not isinstance(bv, dict):
        reject("MISSING_FIELD", "%s build_version is not an object" % label)
    sdk_int = bv.get("SDK_INT")
    if not is_int(sdk_int):
        reject("INVALID_NUMBER", "%s build_version.SDK_INT not an int: %r" % (label, sdk_int))
    sdk = bv.get("SDK")
    if not isinstance(sdk, str) or not sdk:
        reject("MISSING_FIELD", "%s build_version.SDK is not a string" % label)
    if sdk != str(sdk_int):
        reject("INCONSISTENT_SDK", "%s SDK %r != str(SDK_INT %d)" % (label, sdk, sdk_int))

    # A hidden-API read error makes the item NOT_PROVEN: the probe's recorded
    # sentinel (e.g. -1) must not be read as a value mismatch.
    rsi_proven = not error_mentions(doc, "RESOURCES_SDK_INT")
    rsi = bv.get("RESOURCES_SDK_INT")
    if rsi_proven:
        if is_int(rsi):
            if rsi != sdk_int:
                reject("INCONSISTENT_SDK", "%s RESOURCES_SDK_INT %d != SDK_INT %d"
                       % (label, rsi, sdk_int))
        else:
            reject("MISSING_FIELD",
                   "%s RESOURCES_SDK_INT unreadable without a read_error" % label)

    codename = bv.get("SDK_CODENAME")
    if codename is not None and not isinstance(codename, str):
        reject("MISSING_FIELD", "%s SDK_CODENAME is not a string" % label)
    if codename is None and not error_mentions(doc, "SDK_CODENAME"):
        reject("MISSING_FIELD", "%s SDK_CODENAME unreadable without a read_error" % label)

    # RELEASE is derived from SDK_INT, so it must be consistent with THIS
    # document's SDK_INT - it is never compared for equality across runs.
    release_proven = not error_mentions(doc, "Build.VERSION.RELEASE")
    release = bv.get("RELEASE")
    if release is None:
        if release_proven:
            reject("MISSING_FIELD", "%s RELEASE missing without a read_error" % label)
    elif not isinstance(release, str) or not release:
        reject("MISSING_FIELD", "%s build_version.RELEASE is not a string" % label)
    else:
        want_rel = canonical_release(sdk_int)
        if want_rel is not None and release != want_rel:
            reject("RELEASE_MISMATCH",
                   "%s RELEASE %r != canonical release %r for its own SDK_INT %d"
                   % (label, release, want_rel, sdk_int))

    sp = get(doc, "system_properties", label)
    if not isinstance(sp, dict):
        reject("MISSING_FIELD", "%s system_properties is not an object" % label)
    prop_sdk = sp.get("ro.build.version.sdk")
    if prop_sdk is not None:
        if not isinstance(prop_sdk, str) or not prop_sdk.strip().isdigit() \
                or int(prop_sdk) != sdk_int:
            reject("PROP_MISMATCH", "%s ro.build.version.sdk %r != SDK_INT %d"
                   % (label, prop_sdk, sdk_int))
    elif not error_mentions(doc, "ro.build.version.sdk"):
        reject("MISSING_FIELD", "%s ro.build.version.sdk unreadable without a read_error" % label)
    prop_cn = sp.get("ro.build.version.codename")
    if prop_cn is not None and codename is not None and prop_cn != codename:
        reject("INCONSISTENT_SDK", "%s codename %r != SDK_CODENAME %r"
               % (label, prop_cn, codename))

    rs = get(doc, "resource_selection", label)
    if not isinstance(rs, dict):
        reject("MISSING_FIELD", "%s resource_selection is not an object" % label)
    want_q = expected_qualifier(sdk_int)
    selected = rs.get("selected")
    if selected != want_q:
        reject("RESOURCE_MISMATCH", "%s selected %r != %r for SDK_INT %d"
               % (label, selected, want_q, sdk_int))
    cands = rs.get("candidates")
    if not isinstance(cands, dict) or selected not in cands:
        reject("MISSING_FIELD", "%s resource candidates lack %r" % (label, selected))
    if rs.get("value") != cands[selected]:
        reject("RESOURCE_MISMATCH", "%s value %r != candidates[%r]=%r"
               % (label, rs.get("value"), selected, cands[selected]))
    if rs.get("agrees") is not True:
        reject("RESOURCE_MISMATCH", "%s resource_selection.agrees is not true" % label)

    perms = get(doc, "permissions", label)
    if not isinstance(perms, dict):
        reject("MISSING_FIELD", "%s permissions is not an object" % label)
    avail = perms.get("available")
    phone = perms.get("READ_PHONE_STATE")
    priv = perms.get("READ_PRIVILEGED_PHONE_STATE")
    if not isinstance(avail, bool) or not isinstance(phone, bool) or not isinstance(priv, bool):
        reject("MISSING_FIELD", "%s permissions booleans missing" % label)

    gs = get(doc, "get_serial", label)
    if not isinstance(gs, dict):
        reject("MISSING_FIELD", "%s get_serial is not an object" % label)
    if gs.get("attempted") is not True:
        reject("MISSING_FIELD", "%s get_serial.attempted is not true" % label)
    cls = gs.get("classification")
    if cls not in CLASSES:
        reject("SERIAL_SEMANTICS", "%s classification %r not in %s"
               % (label, cls, sorted(CLASSES)))
    if cls == "SECURITY_EXCEPTION" and gs.get("exception") != "java.lang.SecurityException":
        reject("SERIAL_SEMANTICS", "%s SECURITY_EXCEPTION without SecurityException" % label)
    if cls == "UNKNOWN" and not (isinstance(gs.get("value"), str)
                                 and gs["value"].strip().lower() == "unknown"):
        reject("SERIAL_SEMANTICS", "%s UNKNOWN without value 'unknown'" % label)
    if avail:
        if priv:
            allowed = {"UNKNOWN", "OTHER"}
        elif tsdk < 29 and phone:
            allowed = {"UNKNOWN"}
        else:
            allowed = {"SECURITY_EXCEPTION"}
        if cls not in allowed:
            reject("SERIAL_SEMANTICS",
                   "%s targetSdk=%d phone=%s priv=%s -> %r, expected one of %s"
                   % (label, tsdk, phone, priv, cls, sorted(allowed)))

    return {"label": label, "sdk_int": sdk_int, "sdk": sdk, "release": release,
            "release_proven": release_proven, "rsi_proven": rsi_proven,
            "codename": codename, "selected": selected, "target_sdk": tsdk,
            "min_sdk": min_sdk, "phone": phone, "priv": priv, "avail": avail,
            "classification": cls, "run_id": rid}


def main(argv):
    allow_bare = False
    positional = []
    for a in argv:
        if a == "--allow-bare":
            allow_bare = True
        elif a.startswith("--"):
            usage("unknown option %r" % a)
        else:
            positional.append(a)
    if len(positional) != 1:
        usage("expected <family.json>")
    fam_path = positional[0]
    if not os.path.isfile(fam_path):
        reject("MISSING_EVIDENCE", "family manifest not found: %s" % fam_path)
    with open(fam_path, encoding="utf-8", errors="replace") as fh:
        try:
            fam = json.load(fh)
        except Exception as exc:  # noqa: BLE001
            reject("JSON_PARSE", "family manifest is not valid JSON: %s" % exc)
    if not isinstance(fam, dict):
        reject("MANIFEST_INVALID", "family manifest is not an object")
    if fam.get("task_id") != TASK_ID:
        reject("WRONG_TASK", "family task_id %r" % fam.get("task_id"))
    base = os.path.dirname(os.path.abspath(fam_path))
    exp_ovr = fam.get("expect_override_sdk")
    if not is_int(exp_ovr):
        reject("MANIFEST_INVALID", "expect_override_sdk must be an int")
    # The default profile is DERIVED per run from the APK's declared minSdk
    # (max(minSdk, PLATFORM_SDK_FLOOR)); a fixed expect_default_sdk in the
    # manifest would defeat the two-APK family, so it is rejected if present
    # unless it agrees with the single-run derivation.
    runs = fam.get("runs")
    if not isinstance(runs, list) or not runs:
        reject("MANIFEST_INVALID", "family manifest 'runs' must be a non-empty list")

    notes = []
    derived = []
    overrides = []
    scenarios_checked = 0
    for run in runs:
        if not isinstance(run, dict):
            reject("MANIFEST_INVALID", "run entry is not an object")
        tsdk = run.get("target_sdk")
        if not is_int(tsdk):
            reject("MANIFEST_INVALID", "run target_sdk is not an int")
        min_sdk = run.get("min_sdk")
        if not is_int(min_sdk):
            reject("MANIFEST_INVALID",
                   "run %r min_sdk must be an int (APK declared minimum)"
                   % run.get("name"))
        if min_sdk < 1:
            reject("MANIFEST_INVALID", "run min_sdk %r is not a plausible API level"
                   % min_sdk)
        exp_def = expected_default_sdk(min_sdk)
        exp_ovr_run = run.get("override_sdk", exp_ovr)
        if not is_int(exp_ovr_run):
            reject("MANIFEST_INVALID", "run %r override_sdk must be an int"
                   % run.get("name"))
        # A legacy/fixed expect_default_sdk may only be present when it agrees
        # with the derivation for this run; a fixed constant that would defeat
        # the minSdk-28 APK is a manifest error, not silently ignored.
        declared_def = fam.get("expect_default_sdk")
        if declared_def is not None:
            if not is_int(declared_def):
                reject("MANIFEST_INVALID", "family expect_default_sdk must be an int when present")
            if declared_def != exp_def:
                reject("MANIFEST_INVALID",
                       "family expect_default_sdk %r != derived max(min_sdk %d, %d) = %d"
                       % (declared_def, min_sdk, PLATFORM_SDK_FLOOR, exp_def))
        scens = run.get("scenarios")
        if not isinstance(scens, list) or not scens:
            reject("MANIFEST_INVALID", "run %r has no scenarios" % run.get("name"))
        seen = set()
        for scen in scens:
            if not isinstance(scen, dict):
                reject("MANIFEST_INVALID", "scenario entry is not an object")
            name = scen.get("scenario")
            if not isinstance(name, str) or not name:
                reject("MANIFEST_INVALID", "scenario name missing")
            if name in seen:
                reject("DUPLICATE_SCENARIO", "run target_sdk=%d repeats scenario %r"
                       % (tsdk, name))
            seen.add(name)
            def resolve(rel):
                return rel if os.path.isabs(rel) else os.path.join(base, rel)
            a_doc = load_doc(resolve(scen.get("android", "")), "android/%s" % name,
                             allow_bare)
            d_doc = load_doc(resolve(scen.get("atl_default", "")), "atl-default/%s" % name,
                             allow_bare)
            o_doc = load_doc(resolve(scen.get("atl_override", "")), "atl-override/%s" % name,
                             allow_bare)
            a = validate_doc(a_doc, "android/%s" % name, "android", name, tsdk)
            d = validate_doc(d_doc, "atl-default/%s" % name, "atl-default", name, tsdk)
            o = validate_doc(o_doc, "atl-override/%s" % name, "atl-override", name, tsdk)

            # The APK's declared minSdk is the input to the derived default; all
            # three sides must actually be the same APK.
            for info, side in ((a, "android"), (d, "atl-default"), (o, "atl-override")):
                if info["min_sdk"] != min_sdk:
                    reject("CONFIG_MISMATCH",
                           "%s apk.min_sdk %d != run declared min_sdk %d"
                           % (info["label"], info["min_sdk"], min_sdk))

            # ATL default profile: exact derived max(min_sdk, PLATFORM_SDK_FLOOR).
            # A level below that floor AND a level above it (unwarranted
            # inflation) are both DEFAULT_PROFILE_WRONG.
            if d["sdk_int"] != exp_def:
                reject("DEFAULT_PROFILE_WRONG",
                       "atl-default SDK_INT %d != derived max(min_sdk %d, %d) = %d"
                       % (d["sdk_int"], min_sdk, PLATFORM_SDK_FLOOR, exp_def))
            derived.append(exp_def)
            overrides.append(exp_ovr_run)
            # ATL override profile: the --sdk-int value must win, regardless of
            # the derived default (which may equal it for the minSdk-28 APK).
            if o["sdk_int"] != exp_ovr_run:
                reject("OVERRIDE_NOT_APPLIED", "atl-override SDK_INT %d != %d"
                       % (o["sdk_int"], exp_ovr_run))
            if o["sdk"] != str(exp_ovr_run):
                reject("OVERRIDE_NOT_APPLIED", "atl-override SDK %r != %r"
                       % (o["sdk"], str(exp_ovr_run)))
            if o["selected"] != expected_qualifier(exp_ovr_run):
                reject("OVERRIDE_NOT_APPLIED", "atl-override selected %r != %r"
                       % (o["selected"], expected_qualifier(exp_ovr_run)))
            if exp_ovr_run == exp_def:
                notes.append("run min_sdk=%d: override %d equals the derived default "
                             "(override still checked, not distinguished from default)"
                             % (min_sdk, exp_ovr_run))
            # RELEASE is NOT compared across runs: it is derived from each run's
            # SDK_INT (validate_doc already checked it per run), so the default
            # (21 -> "5.0") and the override (30 -> "11") legitimately differ.
            # Fields that must be exact between default and override.
            if d["codename"] != o["codename"]:
                reject("INCONSISTENT_SDK", "SDK_CODENAME %r != %r" % (d["codename"], o["codename"]))
            if d["classification"] != o["classification"]:
                reject("SERIAL_DIVERGENCE", "getSerial %r (default) != %r (override)"
                       % (d["classification"], o["classification"]))

            # NOT_PROVEN items are documented rather than silently ignored.
            for doc_info in (a, d, o):
                if not doc_info["rsi_proven"]:
                    notes.append("%s RESOURCES_SDK_INT NOT_PROVEN (recorded read_error)"
                                 % doc_info["label"])
                if not doc_info["release_proven"]:
                    notes.append("%s RELEASE NOT_PROVEN (recorded read_error)"
                                 % doc_info["label"])

            # Android reference: comparable only when permission states match.
            if a["avail"] and d["avail"] and a["phone"] == d["phone"] and a["priv"] == d["priv"]:
                if a["classification"] != d["classification"]:
                    notes.append("android vs atl-default %s targetSdk=%d: %s vs %s"
                                 % (name, tsdk, a["classification"], d["classification"]))
            else:
                notes.append("android/%s not comparable (permission state differs)" % name)
            scenarios_checked += 1

    print("OK sdk-profile: %d scenario(s) across %d run(s); derived default "
          "SDK_INT=%s (max(apk minSdk, %d)), override SDK_INT=%s; %d documented note(s)"
          % (scenarios_checked, len(runs), sorted(set(derived)), PLATFORM_SDK_FLOOR,
             sorted(set(overrides)), len(notes)))
    for n in notes:
        print("NOTE %s" % n)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Reject as exc:
        print("REJECT %s %s" % (exc.code, exc.reason))
        sys.exit(1)
    except UsageError as exc:
        print("ERROR internal usage: %s" % exc)
        sys.exit(2)
    except SystemExit:
        raise
    except BaseException as exc:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        print("ERROR internal %s: %s" % (type(exc).__name__, exc))
        sys.exit(2)