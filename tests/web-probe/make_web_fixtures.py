#!/usr/bin/env python3
"""make_web_fixtures.py - generate the C-WEB comparator fixture matrix.

Writes fixtures/*.json plus fixtures/cases.json. Each case names the android and
candidate fixture files, the intended comparator exit code, the intended reject
code (empty for accept), and an optional substring the output must contain.
"""
import copy
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")

BEGIN = "WEBPROBE_JSON_BEGIN"
END = "WEBPROBE_JSON_END"
APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

IMPLEMENTED = [
    "JavaScriptEnabled", "DomStorageEnabled", "DatabaseEnabled",
    "LoadsImagesAutomatically", "MediaPlaybackRequiresUserGesture",
    "JavaScriptCanOpenWindowsAutomatically", "AllowFileAccessFromFileURLs",
    "AllowUniversalAccessFromFileURLs",
]
NOT_SUPPORTED = [
    "AllowFileAccess", "BlockNetworkLoads", "BlockNetworkImage",
    "SupportMultipleWindows", "AppCacheEnabled", "AppCachePath",
    "AppCacheMaxSize",
]


def config():
    return {
        "targetSdkVersion": 37,
        "minSdkVersion": 24,
        "baseUrl": "https://probe.local/",
        "assetPage": "probe.html",
        "implementedSettings": list(IMPLEMENTED),
        "notSupportedSettings": list(NOT_SUPPORTED),
        "scenarios": [{"id": "A", "needInitialFocus": True},
                      {"id": "B", "needInitialFocus": False}],
    }


def completion():
    return {"sentinelBegin": BEGIN, "sentinelEnd": END, "doneMarker": "WEBPROBE_DONE"}


def settings_list(observable=True):
    out = []
    for name in IMPLEMENTED:
        entry = {"name": name, "supported": True, "observable": observable}
        if observable:
            entry.update({
                "error": None,
                "defaultValue": True,
                "setTrueGet": True,
                "setFalseGet": False,
                "roundTrip": True,
            })
        else:
            entry.update({
                "error": "backend absent",
                "defaultValue": None,
                "setTrueGet": None,
                "setFalseGet": None,
                "roundTrip": None,
            })
        out.append(entry)
    return out


def not_supported_list():
    values = {
        "AllowFileAccess": (False, True),
        "BlockNetworkLoads": (True, False),
        "BlockNetworkImage": (True, False),
        "SupportMultipleWindows": (True, False),
        "AppCacheEnabled": (True, None),
        "AppCachePath": ("/data/app/appcache", None),
        "AppCacheMaxSize": (1048576, None),
    }
    out = []
    for name in NOT_SUPPORTED:
        set_v, get_v = values[name]
        out.append({"name": name, "setValue": set_v, "getValue": get_v,
                    "note": "NOT_SUPPORTED"})
    return out


def scenarios(observable=True):
    if observable:
        return [
            {"id": "A", "needInitialFocusSet": True, "pageLoaded": True,
             "jsObserved": True, "observedOnMainThread": True, "activeId": "field1",
             "activeTag": "INPUT", "documentHasFocus": True,
             "firstFieldEditable": True, "secondFieldEditable": True,
             "field1TabIndex": 0, "webViewFocused": True, "imeRequested": False,
             "observationSource": "evaluateJavascript", "error": None, "jsError": None},
            {"id": "B", "needInitialFocusSet": False, "pageLoaded": True,
             "jsObserved": True, "observedOnMainThread": True, "activeId": "",
             "activeTag": "BODY", "documentHasFocus": True,
             "firstFieldEditable": True, "secondFieldEditable": True,
             "field1TabIndex": 0, "webViewFocused": False, "imeRequested": False,
             "observationSource": "evaluateJavascript", "error": None, "jsError": None},
        ]
    return [
        {"id": "A", "needInitialFocusSet": True, "pageLoaded": False,
         "jsObserved": False, "observedOnMainThread": True, "activeId": None,
         "activeTag": None, "documentHasFocus": None, "firstFieldEditable": None,
         "secondFieldEditable": None, "field1TabIndex": None, "webViewFocused": False,
         "imeRequested": None, "observationSource": "timeout", "error": None,
         "jsError": None},
        {"id": "B", "needInitialFocusSet": False, "pageLoaded": False,
         "jsObserved": False, "observedOnMainThread": True, "activeId": None,
         "activeTag": None, "documentHasFocus": None, "firstFieldEditable": None,
         "secondFieldEditable": None, "field1TabIndex": None, "webViewFocused": False,
         "imeRequested": None, "observationSource": "timeout", "error": None,
         "jsError": None},
    ]


def android_doc():
    return {
        "task_id": "C-WEB", "run_id": "android-ref", "status": "OK",
        "exceptions": [], "config": config(), "completion": completion(),
        "apk": {"sha256": APK_SHA},
        "engine": {"vmName": "Dalvik", "gatingEnvVar": "ATL_UGLY_ENABLE_WEBVIEW",
                   "gatingEnvValue": None, "webviewBackend": "present",
                   "observable": True, "error": None,
                   "getDefaultUserAgent": "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 Chrome/120",
                   "userAgentDefault": "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 Chrome/120",
                   "userAgentAfterSet": "ATL-WEB-PROBE/1.0", "userAgentRoundTrip": True},
        "settings": settings_list(True), "notSupported": not_supported_list(),
        "scenarios": scenarios(True),
    }


def candidate_doc():
    return {
        "task_id": "C-WEB", "run_id": "candidate-ref", "status": "OK",
        "exceptions": [], "config": config(), "completion": completion(),
        "apk": {"sha256": APK_SHA},
        "engine": {"vmName": "OpenJDK 64-Bit Server VM",
                   "gatingEnvVar": "ATL_UGLY_ENABLE_WEBVIEW", "gatingEnvValue": "1",
                   "webviewBackend": "present", "observable": True, "error": None,
                   "getDefaultUserAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 Safari/605.1.15",
                   "userAgentDefault": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 Safari/605.1.15",
                   "userAgentAfterSet": "ATL-WEB-PROBE/1.0", "userAgentRoundTrip": True},
        "settings": settings_list(True), "notSupported": not_supported_list(),
        "scenarios": scenarios(True),
    }


def gated_candidate_doc():
    doc = candidate_doc()
    doc["run_id"] = "candidate-gated"
    doc["engine"]["gatingEnvValue"] = None
    doc["engine"]["webviewBackend"] = "gated"
    doc["engine"]["observable"] = False
    doc["engine"]["error"] = "no WebKitWebView"
    doc["engine"]["userAgentAfterSet"] = None
    doc["engine"]["userAgentRoundTrip"] = False
    doc["settings"] = settings_list(False)
    doc["scenarios"] = scenarios(False)
    return doc


def write_doc(path, doc, sentinel=True):
    with open(path, "w", encoding="utf-8") as fh:
        if sentinel:
            fh.write(BEGIN + "\n")
            fh.write(json.dumps(doc, indent=1, sort_keys=False))
            fh.write("\n" + END + "\n")
        else:
            fh.write(json.dumps(doc, indent=1))


def write_raw(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def main():
    os.makedirs(FIX, exist_ok=True)
    cases = []

    def p(name):
        return os.path.join(FIX, name)

    # --- valid pair (accept) ---
    write_doc(p("valid_complete_pair.android.json"), android_doc())
    write_doc(p("valid_complete_pair.candidate.json"), candidate_doc())
    cases.append(("valid_complete_pair", p("valid_complete_pair.android.json"),
                  p("valid_complete_pair.candidate.json"), 0, "", "OK C-WEB implemented subset matched"))

    # --- gated candidate (accept with explicit gaps) ---
    write_doc(p("candidate_gated.android.json"), android_doc())
    write_doc(p("candidate_gated.candidate.json"), gated_candidate_doc())
    cases.append(("candidate_gated", p("candidate_gated.android.json"),
                  p("candidate_gated.candidate.json"), 0, "", "GAP candidate WebView backend=gated"))

    # --- structure: empty ---
    write_raw(p("candidate_empty.android.json"), "")
    write_raw(p("candidate_empty.candidate.json"), "")
    cases.append(("empty_android", p("candidate_empty.android.json"),
                  p("valid_complete_pair.candidate.json"), 1, "EMPTY_EVIDENCE", ""))
    cases.append(("empty_candidate", p("valid_complete_pair.android.json"),
                  p("candidate_empty.candidate.json"), 1, "EMPTY_EVIDENCE", ""))

    # --- structure: missing file ---
    cases.append(("missing_candidate", p("valid_complete_pair.android.json"),
                  p("does_not_exist.candidate.json"), 1, "MISSING_EVIDENCE", ""))

    # --- structure: truncated ---
    body = json.dumps(candidate_doc(), indent=1)
    write_raw(p("candidate_truncated.candidate.json"),
              BEGIN + "\n" + body[:len(body) // 2] + "\n" + END + "\n")
    cases.append(("truncated_candidate", p("valid_complete_pair.android.json"),
                  p("candidate_truncated.candidate.json"), 1, "JSON_PARSE", ""))

    # --- structure: duplicate scenario ---
    doc = candidate_doc()
    doc["scenarios"] = [doc["scenarios"][0], copy.deepcopy(doc["scenarios"][0])]
    write_doc(p("candidate_duplicate_scenario.candidate.json"), doc)
    cases.append(("duplicate_scenario", p("valid_complete_pair.android.json"),
                  p("candidate_duplicate_scenario.candidate.json"), 1, "DUPLICATE_SCENARIO", ""))

    # --- structure: reordered scenario ---
    doc = candidate_doc()
    doc["scenarios"] = [doc["scenarios"][1], doc["scenarios"][0]]
    write_doc(p("candidate_reordered_scenario.candidate.json"), doc)
    cases.append(("reordered_scenario", p("valid_complete_pair.android.json"),
                  p("candidate_reordered_scenario.candidate.json"), 1, "SCENARIO_ORDER", ""))

    # --- structure: wrong owner ---
    doc = candidate_doc()
    doc["task_id"] = "FONT-003"
    write_doc(p("candidate_wrong_task.candidate.json"), doc)
    cases.append(("wrong_task", p("valid_complete_pair.android.json"),
                  p("candidate_wrong_task.candidate.json"), 1, "WRONG_TASK", ""))

    # --- structure: wrong thread ---
    doc = candidate_doc()
    doc["scenarios"][0]["observedOnMainThread"] = False
    write_doc(p("candidate_wrong_thread.candidate.json"), doc)
    cases.append(("wrong_thread", p("valid_complete_pair.android.json"),
                  p("candidate_wrong_thread.candidate.json"), 1, "WRONG_THREAD", ""))

    # --- structure: invalid number / type ---
    doc = candidate_doc()
    doc["config"]["targetSdkVersion"] = "34"
    write_doc(p("candidate_invalid_number.candidate.json"), doc)
    cases.append(("invalid_number", p("valid_complete_pair.android.json"),
                  p("candidate_invalid_number.candidate.json"), 1, "INVALID_NUMBER", ""))

    # --- structure: missing setting ---
    doc = candidate_doc()
    doc["settings"] = doc["settings"][:-1]
    write_doc(p("candidate_missing_setting.candidate.json"), doc)
    cases.append(("missing_setting", p("valid_complete_pair.android.json"),
                  p("candidate_missing_setting.candidate.json"), 1, "MISSING_SETTING", ""))

    # --- structure: marker mismatch ---
    doc = candidate_doc()
    doc["completion"]["doneMarker"] = "WRONG_DONE"
    write_doc(p("candidate_marker_mismatch.candidate.json"), doc)
    cases.append(("marker_mismatch", p("valid_complete_pair.android.json"),
                  p("candidate_marker_mismatch.candidate.json"), 1, "MARKER_MISMATCH", ""))

    # --- structure: status error ---
    doc = candidate_doc()
    doc["status"] = "ERROR"
    write_doc(p("candidate_status_error.candidate.json"), doc)
    cases.append(("status_error", p("valid_complete_pair.android.json"),
                  p("candidate_status_error.candidate.json"), 1, "STATUS_NOT_OK", ""))

    # --- structure: config mismatch ---
    doc = candidate_doc()
    doc["config"]["targetSdkVersion"] = 33
    write_doc(p("candidate_config_mismatch.candidate.json"), doc)
    cases.append(("config_mismatch", p("valid_complete_pair.android.json"),
                  p("candidate_config_mismatch.candidate.json"), 1, "CONFIG_MISMATCH", ""))

    # --- identity: apk mismatch ---
    doc = candidate_doc()
    doc["apk"]["sha256"] = "bbbb" + APK_SHA[4:]
    write_doc(p("candidate_apk_mismatch.candidate.json"), doc)
    cases.append(("apk_mismatch", p("valid_complete_pair.android.json"),
                  p("candidate_apk_mismatch.candidate.json"), 1, "APK_MISMATCH", ""))

    # --- structure: missing sentinel (bare candidate) ---
    write_doc(p("candidate_bare_no_sentinel.candidate.json"), candidate_doc(), sentinel=False)
    cases.append(("missing_sentinel", p("valid_complete_pair.android.json"),
                  p("candidate_bare_no_sentinel.candidate.json"), 1, "MISSING_SENTINEL", ""))

    # --- reference unusable ---
    doc = android_doc()
    doc["engine"]["webviewBackend"] = "gated"
    write_doc(p("reference_unusable.android.json"), doc)
    cases.append(("reference_unusable", p("reference_unusable.android.json"),
                  p("valid_complete_pair.candidate.json"), 1, "REFERENCE_UNUSABLE", ""))

    # --- semantic: fake UA (RED control) ---
    doc = candidate_doc()
    doc["engine"]["userAgentAfterSet"] = "GDPR VIOLATION"
    write_doc(p("candidate_fake_ua.candidate.json"), doc)
    cases.append(("fake_ua", p("valid_complete_pair.android.json"),
                  p("candidate_fake_ua.candidate.json"), 1, "FAKE_USER_AGENT", ""))

    # --- semantic: setting round-trip mismatch ---
    doc = candidate_doc()
    for entry in doc["settings"]:
        if entry["name"] == "DomStorageEnabled":
            entry["setFalseGet"] = True
            entry["roundTrip"] = False
    write_doc(p("candidate_setting_mismatch.candidate.json"), doc)
    cases.append(("setting_mismatch", p("valid_complete_pair.android.json"),
                  p("candidate_setting_mismatch.candidate.json"), 1, "SETTING_MISMATCH", ""))

    # --- semantic: scenario focus mismatch ---
    doc = candidate_doc()
    doc["scenarios"][0]["activeId"] = "field2"
    write_doc(p("candidate_scenario_mismatch.candidate.json"), doc)
    cases.append(("scenario_mismatch", p("valid_complete_pair.android.json"),
                  p("candidate_scenario_mismatch.candidate.json"), 1, "SCENARIO_MISMATCH", ""))

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump([{"name": n, "android": a, "candidate": c, "exit": e,
                    "code": code, "token": token}
                   for (n, a, c, e, code, token) in cases], fh, indent=1)
    print("wrote %d cases to %s" % (len(cases), FIX))
    return 0


if __name__ == "__main__":
    sys.exit(main())
