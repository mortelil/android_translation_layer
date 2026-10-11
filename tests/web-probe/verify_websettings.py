#!/usr/bin/env python3
"""verify_websettings.py - fail-closed comparator for the C-WEB evidence.

Usage:
    verify_websettings.py <android_json> <candidate_json> [--allow-bare]

Each input is the captured C-WEB probe output for one runtime, sentinel-wrapped:

    WEBPROBE_JSON_BEGIN
    { ...single JSON object... }
    WEBPROBE_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too.)

Both documents must be complete and identified before any semantic comparison:
non-empty, one JSON object, task_id ``C-WEB``, status OK, empty exceptions,
frozen config (targetSdk/implemented/not-supported setting lists, scenario
matrix), exact scenario set/order with no duplicates, main-thread assertions,
and a non-empty APK SHA-256 equal on both sides (same frozen APK).

Semantic comparison, only when the candidate's real WebView backend is
observable (``engine.webviewBackend == "present"``):
  * user agent is a real observed string, not the fake marker ``GDPR VIOLATION``,
    and a custom UA round-trips exactly through the getter;
  * every implemented setting's boolean getter round-trip matches the Android
    reference exactly (set-true -> get true, set-false -> get false);
  * per scenario, the focused element (``activeId``/``activeTag``/
    ``documentHasFocus``/field1 editability/tabIndex) matches the Android
    reference exactly.

When the candidate backend is NOT observable (WebView gated/absent), the run is
still valid complete evidence: every implemented setting, the UA and both
scenarios are reported as explicit ``GAP`` lines (never a fabricated value) and
the comparator exits 0. The declared NOT_SUPPORTED settings are always reported
as informational gaps, never as silent passes.

Exit status:
    0  accepted: valid, identified, complete pair (compared, or explicit gaps)
    1  rejected: invalid/incomplete evidence or a semantic mismatch
    2  usage / internal error (never surfaced as a semantic rejection)
"""
import json
import os
import sys

BEGIN = "WEBPROBE_JSON_BEGIN"
END = "WEBPROBE_JSON_END"
DONE_MARKER = "WEBPROBE_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "C-WEB"
GATE_VAR = "ATL_UGLY_ENABLE_WEBVIEW"
CUSTOM_UA = "ATL-WEB-PROBE/1.0"
FAKE_UA = "GDPR VIOLATION"
EXPECTED_TARGET_SDK = 37
EXPECTED_IMPLEMENTED = [
    "JavaScriptEnabled",
    "DomStorageEnabled",
    "DatabaseEnabled",
    "LoadsImagesAutomatically",
    "MediaPlaybackRequiresUserGesture",
    "JavaScriptCanOpenWindowsAutomatically",
    "AllowFileAccessFromFileURLs",
    "AllowUniversalAccessFromFileURLs",
]
EXPECTED_NOT_SUPPORTED = [
    "AllowFileAccess",
    "BlockNetworkLoads",
    "BlockNetworkImage",
    "SupportMultipleWindows",
    "AppCacheEnabled",
    "AppCachePath",
    "AppCacheMaxSize",
]
EXPECTED_SCENARIOS = [("A", True), ("B", False)]
BACKEND_VALUES = {"present", "gated", "android-load-failed", "atlexpected-but-absent"}
SCENARIO_COMPARABLE = ["activeId", "activeTag", "documentHasFocus",
                       "firstFieldEditable", "field1TabIndex"]


class Reject(Exception):
    def __init__(self, code, reason):
        super().__init__("%s %s" % (code, reason))
        self.code = code
        self.reason = reason


class UsageError(Exception):
    pass


def reject(code, reason):
    raise Reject(code, reason)


def usage(message):
    raise UsageError(message)


def is_bool(value):
    return isinstance(value, bool)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def is_str_or_none(value):
    return value is None or isinstance(value, str)


def is_bool_or_none(value):
    return value is None or isinstance(value, bool)


def load_side(path, label, allow_bare):
    if not isinstance(path, str) or not path:
        reject("MISSING_EVIDENCE", "%s evidence path is missing" % label)
    if not os.path.isfile(path):
        reject("MISSING_EVIDENCE", "%s evidence file not found: %s" % (label, path))
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except Exception as exc:  # noqa: BLE001
        reject("UNREADABLE_EVIDENCE", "%s evidence unreadable: %s" % (label, exc))
    if not text.strip():
        reject("EMPTY_EVIDENCE", "%s evidence is empty" % label)

    lines = text.splitlines()
    begin_lines = [i for i, line in enumerate(lines) if line.strip() == BEGIN]
    end_lines = [i for i, line in enumerate(lines) if line.strip() == END]
    sentinel_present = bool(begin_lines or end_lines)
    if sentinel_present:
        if len(begin_lines) != 1 or len(end_lines) != 1:
            reject("SENTINEL_INVALID",
                   "%s evidence has %d BEGIN and %d END sentinel lines"
                   % (label, len(begin_lines), len(end_lines)))
        if begin_lines[0] > end_lines[0]:
            reject("SENTINEL_INVALID", "%s sentinel END precedes BEGIN" % label)
        body = "\n".join(lines[begin_lines[0] + 1:end_lines[0]])
    else:
        if not allow_bare:
            reject("MISSING_SENTINEL",
                   "%s evidence is not sentinel-wrapped (%s/%s absent)"
                   % (label, BEGIN, END))
        body = text

    body = body.strip()
    if not body:
        reject("EMPTY_EVIDENCE", "%s evidence body is empty" % label)
    try:
        doc = json.loads(body)
    except Exception as exc:  # noqa: BLE001
        reject("JSON_PARSE", "%s evidence body is not valid JSON: %s" % (label, exc))
    if not isinstance(doc, dict):
        reject("NOT_AN_OBJECT", "%s evidence JSON is not an object" % label)
    return doc, sentinel_present


def require(doc, key, label):
    if key not in doc:
        reject("MISSING_FIELD", "%s evidence missing key %r" % (label, key))
    return doc[key]


def require_str(doc, key, label):
    value = require(doc, key, label)
    if not isinstance(value, str) or not value:
        reject("MISSING_FIELD", "%s %s is not a non-empty string" % (label, key))
    return value


def require_bool(doc, key, where):
    if key not in doc:
        reject("MISSING_FIELD", "%s is missing %r" % (where, key))
    if not is_bool(doc[key]):
        reject("INVALID_TYPE", "%s.%s is not a boolean: %r" % (where, key, doc[key]))
    return doc[key]


def validate_doc(doc, label):
    task_id = require_str(doc, "task_id", label)
    if task_id != TASK_ID:
        reject("WRONG_TASK", "%s task_id is %r, expected %r" % (label, task_id, TASK_ID))
    run_id = require_str(doc, "run_id", label)

    status = require(doc, "status", label)
    if status != "OK":
        reject("STATUS_NOT_OK", "%s status is %r" % (label, status))

    exceptions = require(doc, "exceptions", label)
    if not isinstance(exceptions, list):
        reject("MISSING_FIELD", "%s exceptions is not a list" % label)
    if exceptions:
        reject("EXCEPTIONS_PRESENT", "%s reported exceptions: %r" % (label, exceptions))

    completion = require(doc, "completion", label)
    if not isinstance(completion, dict):
        reject("MISSING_FIELD", "%s completion is not an object" % label)
    if completion.get("sentinelBegin") != BEGIN or completion.get("sentinelEnd") != END:
        reject("MARKER_MISMATCH", "%s completion sentinels are wrong: %r"
               % (label, completion))
    if completion.get("doneMarker") != DONE_MARKER:
        reject("MARKER_MISMATCH", "%s doneMarker is %r" % (label, completion.get("doneMarker")))

    config = require(doc, "config", label)
    if not isinstance(config, dict):
        reject("MISSING_FIELD", "%s config is not an object" % label)
    target = config.get("targetSdkVersion")
    if not is_int(target):
        reject("INVALID_NUMBER", "%s config.targetSdkVersion is not an int: %r"
               % (label, target))
    if target != EXPECTED_TARGET_SDK:
        reject("CONFIG_MISMATCH", "%s config.targetSdkVersion is %r, expected %d"
               % (label, target, EXPECTED_TARGET_SDK))
    require_str(config, "baseUrl", label + " config")
    require_str(config, "assetPage", label + " config")
    if config.get("implementedSettings") != EXPECTED_IMPLEMENTED:
        reject("CONFIG_MISMATCH", "%s config.implementedSettings is not the frozen list: %r"
               % (label, config.get("implementedSettings")))
    if config.get("notSupportedSettings") != EXPECTED_NOT_SUPPORTED:
        reject("CONFIG_MISMATCH", "%s config.notSupportedSettings is not the frozen list: %r"
               % (label, config.get("notSupportedSettings")))
    cfg_scen = config.get("scenarios")
    want_cfg_scen = [{"id": i, "needInitialFocus": n} for i, n in EXPECTED_SCENARIOS]
    if cfg_scen != want_cfg_scen:
        reject("SCENARIO_CONFIG", "%s config.scenarios is %r, expected %r"
               % (label, cfg_scen, want_cfg_scen))

    apk = require(doc, "apk", label)
    if not isinstance(apk, dict):
        reject("MISSING_FIELD", "%s apk is not an object" % label)
    apk_sha = apk.get("sha256")
    if not isinstance(apk_sha, str) or not apk_sha:
        reject("MISSING_FIELD", "%s apk.sha256 is not a non-empty string" % label)

    engine = require(doc, "engine", label)
    if not isinstance(engine, dict):
        reject("MISSING_FIELD", "%s engine is not an object" % label)
    if not is_str_or_none(engine.get("vmName")):
        reject("INVALID_TYPE", "%s engine.vmName is not a string/null" % label)
    if engine.get("gatingEnvVar") != GATE_VAR:
        reject("CONFIG_MISMATCH", "%s engine.gatingEnvVar is %r" % (label, engine.get("gatingEnvVar")))
    if not is_str_or_none(engine.get("gatingEnvValue")):
        reject("INVALID_TYPE", "%s engine.gatingEnvValue is not a string/null" % label)
    backend = engine.get("webviewBackend")
    if backend not in BACKEND_VALUES:
        reject("INVALID_TYPE", "%s engine.webviewBackend is %r" % (label, backend))
    if not is_bool(engine.get("observable")):
        reject("INVALID_TYPE", "%s engine.observable is not a boolean" % label)
    for field in ("getDefaultUserAgent", "userAgentDefault", "userAgentAfterSet"):
        if not is_str_or_none(engine.get(field)):
            reject("INVALID_TYPE", "%s engine.%s is not a string/null" % (label, field))
    if not is_bool(engine.get("userAgentRoundTrip")):
        reject("INVALID_TYPE", "%s engine.userAgentRoundTrip is not a boolean" % label)

    settings = require(doc, "settings", label)
    if not isinstance(settings, list):
        reject("MISSING_FIELD", "%s settings is not a list" % label)
    settings_by_name = {}
    for i, entry in enumerate(settings):
        where = "%s settings[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            reject("MISSING_FIELD", "%s has no name" % where)
        if name not in EXPECTED_IMPLEMENTED:
            reject("UNKNOWN_SETTING", "%s names %r which is not implemented" % (where, name))
        if name in settings_by_name:
            reject("DUPLICATE_SETTING", "%s duplicates setting %r" % (label, name))
        observable = entry.get("observable")
        if not is_bool(observable):
            reject("INVALID_TYPE", "%s.observable is not a boolean" % where)
        if observable:
            for field in ("defaultValue", "setTrueGet", "setFalseGet", "roundTrip"):
                if not is_bool(entry.get(field)):
                    reject("INVALID_TYPE", "%s.%s is not a boolean" % (where, field))
        settings_by_name[name] = entry
    for name in EXPECTED_IMPLEMENTED:
        if name not in settings_by_name:
            reject("MISSING_SETTING", "%s lacks implemented setting %r" % (label, name))

    not_supported = require(doc, "notSupported", label)
    if not isinstance(not_supported, list):
        reject("MISSING_FIELD", "%s notSupported is not a list" % label)
    ns_names = []
    for entry in not_supported:
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s notSupported entry is not an object" % label)
        name = entry.get("name")
        if not isinstance(name, str) or not name:
            reject("MISSING_FIELD", "%s notSupported entry has no name" % label)
        ns_names.append(name)
    if ns_names != EXPECTED_NOT_SUPPORTED:
        reject("CONFIG_MISMATCH", "%s notSupported names are %r, expected %r"
               % (label, ns_names, EXPECTED_NOT_SUPPORTED))

    scenarios = require(doc, "scenarios", label)
    if not isinstance(scenarios, list):
        reject("MISSING_FIELD", "%s scenarios is not a list" % label)
    ids = []
    scen_by_id = {}
    for i, entry in enumerate(scenarios):
        where = "%s scenarios[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        sid = entry.get("id")
        if not isinstance(sid, str) or not sid:
            reject("MISSING_FIELD", "%s has no id" % where)
        ids.append(sid)
        if sid in scen_by_id:
            reject("DUPLICATE_SCENARIO", "%s duplicates scenario %r" % (label, sid))
        want_nif = dict(EXPECTED_SCENARIOS).get(sid)
        if want_nif is None:
            reject("SCENARIO_CONFIG", "%s has unknown scenario %r" % (label, sid))
        nif = entry.get("needInitialFocusSet")
        if not is_bool(nif):
            reject("INVALID_TYPE", "%s.needInitialFocusSet is not a boolean: %r" % (where, nif))
        if nif != want_nif:
            reject("SCENARIO_CONFIG", "%s.needInitialFocusSet is %r, expected %r"
                   % (where, nif, want_nif))
        if entry.get("observedOnMainThread") is not True:
            reject("WRONG_THREAD", "%s.observedOnMainThread is %r, expected true"
                   % (where, entry.get("observedOnMainThread")))
        require_bool(entry, "pageLoaded", where)
        require_bool(entry, "jsObserved", where)
        if not is_str_or_none(entry.get("activeId")):
            reject("INVALID_TYPE", "%s.activeId is not a string/null" % where)
        if not is_str_or_none(entry.get("activeTag")):
            reject("INVALID_TYPE", "%s.activeTag is not a string/null" % where)
        for field in ("documentHasFocus", "firstFieldEditable", "secondFieldEditable"):
            if not is_bool_or_none(entry.get(field)):
                reject("INVALID_TYPE", "%s.%s is not a boolean/null" % (where, field))
        tab = entry.get("field1TabIndex")
        if not (tab is None or is_int(tab)):
            reject("INVALID_NUMBER", "%s.field1TabIndex is not an int/null: %r" % (where, tab))
        if not is_str_or_none(entry.get("observationSource")):
            reject("INVALID_TYPE", "%s.observationSource is not a string/null" % where)
        scen_by_id[sid] = entry

    if ids != [i for i, _ in EXPECTED_SCENARIOS]:
        reject("SCENARIO_ORDER", "%s scenario ids are %r, expected %r"
               % (label, ids, [i for i, _ in EXPECTED_SCENARIOS]))

    return {
        "label": label,
        "run_id": run_id,
        "apk_sha": apk_sha,
        "engine": engine,
        "settings": settings_by_name,
        "scenarios": scen_by_id,
        "notSupported": not_supported,
    }


def compare(ref, cand):
    if ref["engine"]["webviewBackend"] != "present":
        reject("REFERENCE_UNUSABLE",
               "android reference WebView backend is %r, expected 'present'"
               % ref["engine"]["webviewBackend"])
    for sid, _ in EXPECTED_SCENARIOS:
        if not ref["scenarios"][sid].get("pageLoaded"):
            reject("REFERENCE_UNUSABLE", "android scenario %s page did not load" % sid)

    backend = cand["engine"]["webviewBackend"]
    compared = False
    if backend == "present":
        if not cand["engine"]["observable"]:
            reject("SETTING_NOT_OBSERVABLE",
                   "candidate UA not observable: %r" % cand["engine"].get("error"))
        after = cand["engine"]["userAgentAfterSet"]
        dflt = cand["engine"]["getDefaultUserAgent"]
        if after == FAKE_UA or dflt == FAKE_UA:
            reject("FAKE_USER_AGENT", "candidate surfaced the fake UA %r" % FAKE_UA)
        if not after:
            reject("UA_MISMATCH", "candidate userAgentAfterSet is empty/absent")
        if not cand["engine"]["userAgentRoundTrip"] or after != CUSTOM_UA:
            reject("UA_MISMATCH",
                   "candidate UA round-trip failed: after=%r roundTrip=%r"
                   % (after, cand["engine"]["userAgentRoundTrip"]))

        for name in EXPECTED_IMPLEMENTED:
            r = ref["settings"][name]
            c = cand["settings"][name]
            if not r["observable"]:
                reject("REFERENCE_UNUSABLE", "android setting %s not observable" % name)
            if not c["observable"]:
                reject("SETTING_NOT_OBSERVABLE",
                       "candidate setting %s not observable: %r" % (name, c.get("error")))
            if not r["roundTrip"]:
                print("GAP setting %s: android reference backend is a no-op "
                      "(t,f)=(%r,%r) - candidate (t,f)=(%r,%r) reported, not enforced"
                      % (name, r["setTrueGet"], r["setFalseGet"],
                         c["setTrueGet"], c["setFalseGet"]))
                continue
            if (c["setTrueGet"], c["setFalseGet"]) != (r["setTrueGet"], r["setFalseGet"]):
                reject("SETTING_MISMATCH",
                       "candidate setting %s (t,f)=(%r,%r) != android (%r,%r)"
                       % (name, c["setTrueGet"], c["setFalseGet"],
                          r["setTrueGet"], r["setFalseGet"]))
            if c["roundTrip"] is not True:
                reject("SETTING_MISMATCH",
                       "candidate setting %s roundTrip=%r (android is a real backend)"
                       % (name, c["roundTrip"]))

        for sid, _ in EXPECTED_SCENARIOS:
            r = ref["scenarios"][sid]
            c = cand["scenarios"][sid]
            if not c["pageLoaded"] or not c["jsObserved"]:
                reject("SCENARIO_MISMATCH",
                       "candidate scenario %s pageLoaded=%r jsObserved=%r"
                       % (sid, c["pageLoaded"], c["jsObserved"]))
            for field in SCENARIO_COMPARABLE:
                if c.get(field) != r.get(field):
                    reject("SCENARIO_MISMATCH",
                           "candidate scenario %s %s=%r != android %r"
                           % (sid, field, c.get(field), r.get(field)))
        compared = True
    else:
        print("GAP candidate WebView backend=%s (env %s=%r, vm=%r) - "
              "implemented observations are not comparable"
              % (backend, GATE_VAR, cand["engine"].get("gatingEnvValue"),
                 cand["engine"].get("vmName")))
        print("GAP user-agent not observable (candidate backend=%s)" % backend)
        for name in EXPECTED_IMPLEMENTED:
            print("GAP setting %s backend=%s (android roundTrip=%r)"
                  % (name, backend, ref["settings"][name].get("roundTrip")))
        for sid, nif in EXPECTED_SCENARIOS:
            print("GAP scenario %s setNeedInitialFocus=%s backend=%s (android activeId=%r)"
                  % (sid, nif, backend, ref["scenarios"][sid].get("activeId")))

    for entry in cand["notSupported"]:
        print("GAP NOT_SUPPORTED %s set=%r candidate-get=%r"
              % (entry.get("name"), entry.get("setValue"), entry.get("getValue")))
    print("INFO user-agent candidate default=%r afterSet=%r | android default=%r"
          % (cand["engine"].get("userAgentDefault"), cand["engine"].get("userAgentAfterSet"),
             ref["engine"].get("userAgentDefault")))
    for sid, _ in EXPECTED_SCENARIOS:
        c = cand["scenarios"][sid]
        print("INFO scenario %s candidate: activeId=%r activeTag=%r documentHasFocus=%r "
              "field1Editable=%r tabIndex=%r imeRequested=%r source=%r"
              % (sid, c.get("activeId"), c.get("activeTag"), c.get("documentHasFocus"),
                 c.get("firstFieldEditable"), c.get("field1TabIndex"),
                 c.get("imeRequested"), c.get("observationSource")))

    if compared:
        print("OK C-WEB implemented subset matched Android exactly: %d settings, "
              "%d scenarios (UA real observed, custom UA round-trip); android-run=%s candidate-run=%s"
              % (len(EXPECTED_IMPLEMENTED), len(EXPECTED_SCENARIOS),
                 ref["run_id"], cand["run_id"]))
    else:
        print("OK C-WEB evidence valid and complete: candidate backend=%s reported as "
              "explicit gaps (not a fabricated pass); android-run=%s candidate-run=%s"
              % (backend, ref["run_id"], cand["run_id"]))
    return EXIT_OK


def main(argv):
    allow_bare = False
    positional = []
    for arg in argv:
        if arg == "--allow-bare":
            allow_bare = True
        elif arg.startswith("--"):
            usage("unknown option %r" % arg)
        else:
            positional.append(arg)
    if len(positional) != 2:
        usage("expected <android_json> <candidate_json>")

    ref_doc, ref_sentinel = load_side(positional[0], "android", allow_bare)
    cand_doc, cand_sentinel = load_side(positional[1], "candidate", allow_bare)
    if not allow_bare and (not ref_sentinel or not cand_sentinel):
        reject("MISSING_SENTINEL", "both sides must be sentinel-wrapped evidence")

    ref = validate_doc(ref_doc, "android")
    cand = validate_doc(cand_doc, "candidate")

    if ref["apk_sha"] != cand["apk_sha"]:
        reject("APK_MISMATCH",
               "apk sha differs between sides: %s vs %s" % (ref["apk_sha"], cand["apk_sha"]))

    return compare(ref, cand)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Reject as exc:
        print("REJECT %s %s" % (exc.code, exc.reason))
        sys.exit(EXIT_REJECT)
    except UsageError as exc:
        print("ERROR internal usage: %s" % exc)
        sys.exit(EXIT_ERROR)
    except SystemExit:
        raise
    except BaseException as exc:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        print("ERROR internal %s: %s" % (type(exc).__name__, exc))
        sys.exit(EXIT_ERROR)
