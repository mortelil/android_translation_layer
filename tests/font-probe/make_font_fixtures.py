#!/usr/bin/env python3
"""make_font_fixtures.py - regenerate the FONT-003 comparator fixture matrix.

Writes one file per side under ``fixtures/`` plus ``fixtures/cases.json``
consumed by ``run_font_selftests.sh``. The canonical Android reference and the
"valid complete matching pair" candidate are synthetic but structurally and
semantically valid, and the mutations exercise the fail-closed paths:

    valid_complete_pair        -> accept (exit 0)
    android_empty              -> empty evidence
    candidate_empty            -> empty evidence
    candidate_malformed        -> unparseable JSON body
    candidate_missing_field    -> fm.ascent removed
    candidate_bare_no_sentinel -> sentinels absent
    candidate_wrong_font_hash  -> pinned font SHA mismatch
    candidate_float_oot        -> float delta 0.6 > 0.5
    candidate_int_off_pos      -> fmi.top  +1 (exact equality violated)
    candidate_int_off_neg      -> fmi.bottom -1
    candidate_duplicate_cell   -> duplicate (size, style)
    candidate_missing_cell     -> one matrix cell absent
    candidate_config_mismatch  -> styles changed
    candidate_apk_mismatch     -> apk.sha256 differs
    candidate_status_error     -> status != OK
"""
import copy
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")

BEGIN = "FONT003_JSON_BEGIN"
END = "FONT003_JSON_END"

TASK_ID = "FONT-003"
FONT_SHA = "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954"
APK_SHA = "a" * 64
SIZES = [12.3, 15.7, 33.4, 47.9, 64.0, 100.91]
STYLES = ["normal", "bold_italic"]


def base_metrics():
    rows = []
    for size in SIZES:
        for style in STYLES:
            fm = {
                "ascent": -0.9282 * size,
                "descent": 0.2362 * size,
                "top": -1.1700 * size,
                "bottom": 0.2900 * size,
                "leading": 0.0,
            }
            fmi = {
                "top": math.floor(fm["top"]),
                "ascent": math.floor(fm["ascent"]),
                "descent": math.ceil(fm["descent"]),
                "bottom": math.ceil(fm["bottom"]),
                "leading": 0,
            }
            rows.append({"size": size, "style": style, "fm": fm, "fmi": fmi})
    return rows


def make_doc(rows, apk_sha=APK_SHA, status="OK", font_sha=FONT_SHA,
             styles=None, exceptions=None, run_id="fixture-run"):
    return {
        "task_id": TASK_ID,
        "run_id": run_id,
        "status": status,
        "exceptions": list(exceptions or []),
        "config": {
            "antiAlias": True,
            "fontAsset": "DejaVuSans.ttf",
            "expectedSha256": FONT_SHA,
            "textSizes": list(SIZES),
            "styles": list(styles if styles is not None else STYLES),
        },
        "completion": {"marker": "FONT003_DONE",
                       "sentinelBegin": BEGIN, "sentinelEnd": END},
        "font": {"sha256": font_sha, "asset": "DejaVuSans.ttf"},
        "apk": {"sha256": apk_sha},
        "metrics": rows,
    }


def wrap(doc):
    return "%s\n%s\n%s\nFONT003_DONE\n" % (BEGIN, json.dumps(doc), END)


def write(name, text):
    path = os.path.join(FIX, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def main():
    os.makedirs(FIX, exist_ok=True)

    android_rows = base_metrics()
    # A valid candidate: every float shifted by +0.13 (inside the 0.5 bound),
    # every int EXACT.
    cand_rows = copy.deepcopy(android_rows)
    for row in cand_rows:
        for field in row["fm"]:
            row["fm"][field] = row["fm"][field] + 0.13

    android = make_doc(android_rows, run_id="android-fixture")
    candidate = make_doc(cand_rows, run_id="candidate-fixture")

    cases = []

    def case(name, android_text, cand_text, exit_code, code):
        a = write(name + ".android.json", android_text)
        c = write(name + ".candidate.json", cand_text)
        cases.append({"name": name, "android": a, "candidate": c,
                      "exit": exit_code, "code": code})

    ref_text = wrap(android)
    cand_text = wrap(candidate)

    case("valid_complete_pair", ref_text, cand_text, 0, "OK")

    case("android_empty", "", cand_text, 1, "EMPTY_EVIDENCE")
    case("candidate_empty", ref_text, "", 1, "EMPTY_EVIDENCE")
    case("candidate_malformed", ref_text,
         BEGIN + "\n{\"task_id\": \"FONT-003\",\n" + END + "\n",
         1, "JSON_PARSE")

    # missing field: drop fm.ascent from the first cell
    bad = copy.deepcopy(candidate)
    del bad["metrics"][0]["fm"]["ascent"]
    case("candidate_missing_field", ref_text, wrap(bad), 1, "MISSING_FIELD")

    # bare JSON without sentinels (no --allow-bare)
    case("candidate_bare_no_sentinel", ref_text,
         json.dumps(candidate) + "\n", 1, "MISSING_SENTINEL")

    bad = copy.deepcopy(candidate)
    bad["font"]["sha256"] = "b" * 64
    case("candidate_wrong_font_hash", ref_text, wrap(bad), 1, "FONT_HASH_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["metrics"][0]["fm"]["ascent"] += 0.47  # total +0.60 vs android
    case("candidate_float_oot", ref_text, wrap(bad), 1, "TOLERANCE_EXCEEDED")

    bad = copy.deepcopy(candidate)
    bad["metrics"][0]["fmi"]["top"] += 1
    case("candidate_int_off_pos", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["metrics"][-1]["fmi"]["bottom"] -= 1
    case("candidate_int_off_neg", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["metrics"].append(copy.deepcopy(bad["metrics"][0]))
    case("candidate_duplicate_cell", ref_text, wrap(bad), 1, "DUPLICATE_CELL")

    bad = copy.deepcopy(candidate)
    del bad["metrics"][0]
    case("candidate_missing_cell", ref_text, wrap(bad), 1, "MISSING_CELL")

    bad = copy.deepcopy(candidate)
    bad["config"]["styles"] = ["normal"]
    case("candidate_config_mismatch", ref_text, wrap(bad), 1, "CONFIG_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["apk"]["sha256"] = "c" * 64
    case("candidate_apk_mismatch", ref_text, wrap(bad), 1, "APK_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["status"] = "ERROR"
    case("candidate_status_error", ref_text, wrap(bad), 1, "STATUS_NOT_OK")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
        fh.write("\n")
    print("wrote %d fixture cases to %s" % (len(cases), FIX))


if __name__ == "__main__":
    sys.exit(main())
