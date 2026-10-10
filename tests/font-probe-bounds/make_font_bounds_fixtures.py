#!/usr/bin/env python3
"""make_font_bounds_fixtures.py - regenerate the FONT-BOUNDS comparator fixtures.

Writes one file per side under ``fixtures/`` plus ``fixtures/cases.json``
consumed by ``run_font_bounds_selftests.sh``. The expected cell matrix is taken
from the comparator itself (single source of truth), so the fixtures cannot
drift from ``verify_font_bounds.py``. The canonical Android reference and the
"valid complete matching pair" candidate are synthetic but structurally and
semantically valid; every mutation exercises one fail-closed path.

    valid_complete_pair       -> accept (exit 0)
    android_empty             -> empty evidence
    candidate_empty           -> empty evidence
    candidate_malformed       -> unparseable JSON body
    candidate_missing_field   -> a cell's `left` removed
    candidate_wrong_font_hash -> pinned font SHA mismatch
    candidate_wrong_string    -> cell identity (text) changed
    candidate_wrong_length    -> cell identity (end) changed
    candidate_wrong_range     -> cell identity (start) changed
    candidate_left_off        -> left off by one
    candidate_top_off         -> top off by one
    candidate_right_off       -> right off by one
    candidate_bottom_off      -> bottom off by one
    candidate_duplicate_cell  -> duplicate cell identity
    candidate_missing_cell    -> one expected cell absent
    candidate_invalid_utf16   -> utf16 units disagree with the text
    candidate_bare_no_sentinel-> sentinels absent
    candidate_status_error    -> status != OK
"""
import copy
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, HERE)
import verify_font_bounds as v  # noqa: E402

BEGIN = v.BEGIN
END = v.END

TASK_ID = v.TASK_ID
CASE_ID = v.CASE_ID
FONT_SHA = v.EXPECTED_FONT_SHA
APK_SHA = "a" * 64


def base_cells():
    rows = []
    for i, c in enumerate(v.EXPECTED_CELLS):
        text = c["text"]
        span = c["end"] - c["start"]
        left = -(span + i % 3)
        top = -int(c["size"]) - (i % 2)
        right = span + int(c["size"]) + (i % 4)
        bottom = int(c["size"] // 4) + (i % 5)
        rows.append({
            "size": c["size"], "align": c["align"], "overload": c["overload"],
            "name": c["name"], "text": text, "start": c["start"], "end": c["end"],
            "utf16": v.u16_units(text),
            "left": left, "top": top, "right": right, "bottom": bottom,
        })
    return rows


def make_doc(rows, apk_sha=APK_SHA, status="OK", font_sha=FONT_SHA,
             exceptions=None, run_id="fixture-run"):
    return {
        "task_id": TASK_ID,
        "case_id": CASE_ID,
        "run_id": run_id,
        "status": status,
        "exceptions": list(exceptions or []),
        "config": {
            "antiAlias": True,
            "fontAsset": "DejaVuSans.ttf",
            "expectedSha256": FONT_SHA,
            "textSizes": list(v.EXPECTED_SIZES),
            "alignments": list(v.EXPECTED_ALIGNS),
        },
        "completion": {"marker": v.DONE_MARKER,
                       "sentinelBegin": BEGIN, "sentinelEnd": END},
        "font": {"sha256": font_sha, "asset": "DejaVuSans.ttf"},
        "apk": {"sha256": apk_sha},
        "bounds": rows,
    }


def wrap(doc):
    return "%s\n%s\n%s\n%s\n" % (BEGIN, json.dumps(doc), END, v.DONE_MARKER)


def write(name, text):
    path = os.path.join(FIX, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def find_cell(doc, name, align="LEFT", size=None):
    for cell in doc["bounds"]:
        if cell["name"] == name and cell["align"] == align:
            if size is None or cell["size"] == size:
                return cell
    raise SystemExit("fixture setup: no cell %r/%r" % (name, align))


def main():
    os.makedirs(FIX, exist_ok=True)

    android = make_doc(base_cells(), run_id="android-fixture")
    candidate = make_doc(base_cells(), run_id="candidate-fixture")

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
         BEGIN + "\n{\"task_id\": \"FONT-004\",\n" + END + "\n", 1, "JSON_PARSE")

    bad = copy.deepcopy(candidate)
    del find_cell(bad, "Hello")["left"]
    case("candidate_missing_field", ref_text, wrap(bad), 1, "MISSING_FIELD")

    bad = copy.deepcopy(candidate)
    bad["font"]["sha256"] = "b" * 64
    case("candidate_wrong_font_hash", ref_text, wrap(bad), 1, "FONT_HASH_MISMATCH")

    bad = copy.deepcopy(candidate)
    cell = find_cell(bad, "Hello")
    cell["text"] = "Helo"
    cell["end"] = 4
    cell["utf16"] = v.u16_units("Helo")
    case("candidate_wrong_string", ref_text, wrap(bad), 1, "WRONG_CELL_IDENTITY")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "Hello")["end"] = 4
    case("candidate_wrong_length", ref_text, wrap(bad), 1, "WRONG_CELL_IDENTITY")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "Hello_range_1_4")["start"] = 2
    case("candidate_wrong_range", ref_text, wrap(bad), 1, "WRONG_CELL_IDENTITY")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "AV")["left"] += 1
    case("candidate_left_off", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "AV")["top"] += 1
    case("candidate_top_off", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "AV")["right"] += 1
    case("candidate_right_off", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "AV")["bottom"] += 1
    case("candidate_bottom_off", ref_text, wrap(bad), 1, "INT_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["bounds"].append(copy.deepcopy(bad["bounds"][0]))
    case("candidate_duplicate_cell", ref_text, wrap(bad), 1, "DUPLICATE_CELL")

    bad = copy.deepcopy(candidate)
    del bad["bounds"][0]
    case("candidate_missing_cell", ref_text, wrap(bad), 1, "MISSING_CELL")

    bad = copy.deepcopy(candidate)
    find_cell(bad, "Hello")["utf16"] = v.u16_units("HELLO")
    case("candidate_invalid_utf16", ref_text, wrap(bad), 1, "UTF16_MISMATCH")

    case("candidate_bare_no_sentinel", ref_text, json.dumps(candidate) + "\n",
         1, "MISSING_SENTINEL")

    bad = copy.deepcopy(candidate)
    bad["status"] = "ERROR"
    case("candidate_status_error", ref_text, wrap(bad), 1, "STATUS_NOT_OK")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
    print("wrote %d fixture cases to %s" % (len(cases), FIX))
    return 0


if __name__ == "__main__":
    sys.exit(main())
