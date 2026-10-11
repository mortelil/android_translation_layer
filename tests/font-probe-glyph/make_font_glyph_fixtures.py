#!/usr/bin/env python3
"""make_font_glyph_fixtures.py - regenerate the FONT-006 comparator fixtures.

The fixtures are synthetic (no Android/ATL run) and exercise the fail-closed
comparator ``verify_font_glyph.py``:

    valid_complete_pair        -> exit 0, OK
    android_empty              -> EMPTY_EVIDENCE
    candidate_empty            -> EMPTY_EVIDENCE
    candidate_malformed        -> JSON_PARSE
    candidate_missing_field    -> MISSING_FIELD
    candidate_wrong_font_hash  -> FONT_HASH_MISMATCH
    candidate_wrong_cell_id    -> WRONG_CELL_IDENTITY
    candidate_advance_off_one  -> INT_ADVANCE_MISMATCH (single int advance, +1)
    candidate_advance_frac_off -> ADVANCE_MISMATCH (single fractional advance, +1)
    candidate_bounds_off_one   -> BOUNDS_MISMATCH (single bounds int, +1)
    candidate_measure_off      -> MEASURE_MISMATCH
    candidate_invalid_number   -> INVALID_NUMBER
    candidate_duplicate_cell   -> DUPLICATE_CELL
    candidate_missing_cell     -> MISSING_CELL
    candidate_apk_mismatch     -> APK_MISMATCH
    candidate_status_error     -> STATUS_NOT_OK
    candidate_bare_no_sentinel -> MISSING_SENTINEL
"""
import copy
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, HERE)
import verify_font_glyph as v  # noqa: E402

BEGIN = v.BEGIN
END = v.END
APK_SHA = "a" * 64


def f32(x):
    return struct.unpack("<f", struct.pack("<f", x))[0]


def jhex(x):
    """Java Float.toHexString-style hex for a float32 value."""
    x = f32(x)
    s = float(x).hex()
    mant, exp = s.split("p")
    exp = int(exp)
    if "." in mant:
        mant = mant.rstrip("0").rstrip(".")
    if "." not in mant:
        mant += ".0"
    return "%sp%d" % (mant, exp)


def pair(x):
    x = f32(x)
    return {"hex": jhex(x), "dec": x}


def base_cells():
    rows = []
    for i, c in enumerate(v.EXPECTED_CELLS):
        text = c["text"]
        units = v.u16_units(text)
        n = len(units)
        widths = []
        vals = []
        for j in range(n):
            if (i + j) % 2 == 0:
                w = float((i + j) % 12)          # int-valued advance
            else:
                w = float((i + j) % 12) + 0.375  # fractional advance
            vals.append(w)
            widths.append(pair(w))
        total = f32(sum(vals))
        row = {
            "kind": c["kind"], "label": c["label"], "name": c["name"],
            "size": c["size"], "text": text, "utf16": units,
            "face": c["face"], "fontSha256": v.FACE_SHA[c["face"]],
            "count": n, "widths": widths,
            "sum": pair(total), "measureText": pair(f32(total + 0.5)),
            "bounds": {"left": -(i % 5 + 1), "top": -(int(c["size"]) + i % 3),
                       "right": i % 7 + 1 + int(c["size"]),
                       "bottom": int(c["size"] // 4) + i % 5},
        }
        if c["kind"] == "flag":
            row["observed"] = {"requestedMode": c["label"], "requestedValue": 0,
                               "applied": True, "flags": 1, "subpixelText": False,
                               "linearText": False, "hinting": 1,
                               "letterSpacing": 0.0, "exception": None}
        elif c["kind"] == "style":
            row["observed"] = {"baseFace": c["face"], "derived": "none",
                               "fakeBold": False, "skewX": 0.0, "letterSpacing": 0.0}
        rows.append(row)
    return rows


def make_doc(rows, apk_sha=APK_SHA, status="OK", run_id="fixture-run"):
    return {
        "task_id": v.TASK_ID,
        "case_id": v.CASE_ID,
        "run_id": run_id,
        "status": status,
        "exceptions": [],
        "completion": {"marker": v.DONE_MARKER,
                       "sentinelBegin": BEGIN, "sentinelEnd": END},
        "config": {
            "antiAlias": True, "align": "LEFT",
            "sizes": list(v.SIZES),
            "strings": [t for _, t in v.STRINGS],
            "flagSize": v.FLAG_SIZE,
            "flagText": v.FLAG_TEXT,
            "flagVariants": list(v.FLAG_VARIANTS),
            "styleVariants": list(v.STYLE_VARIANTS),
        },
        "assets": {
            "A": {"asset": "DejaVuSans.ttf", "sha256": v.SHA_A},
            "B": {"asset": "DejaVuSans-Oblique.ttf", "sha256": v.SHA_B},
            "Bold": {"asset": "DejaVuSans-Bold.ttf", "sha256": v.SHA_BOLD},
        },
        "apk": {"sha256": apk_sha},
        "cells": rows,
    }


def wrap(doc):
    return "%s\n%s\n%s\n%s\n" % (BEGIN, json.dumps(doc), END, v.DONE_MARKER)


def write(name, text):
    path = os.path.join(FIX, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def recompute_sum(row):
    row["sum"] = pair(sum(w["dec"] for w in row["widths"]))


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
         BEGIN + "\n{\"task_id\": \"FONT-006\",\n" + END + "\n", 1, "JSON_PARSE")

    bad = copy.deepcopy(candidate)
    del bad["cells"][0]["widths"]
    case("candidate_missing_field", ref_text, wrap(bad), 1, "MISSING_FIELD")

    bad = copy.deepcopy(candidate)
    bad["assets"]["A"]["sha256"] = "b" * 64
    case("candidate_wrong_font_hash", ref_text, wrap(bad), 1, "FONT_HASH_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["cells"][0]["label"] = "A_bogus"
    case("candidate_wrong_cell_id", ref_text, wrap(bad), 1, "WRONG_CELL_IDENTITY")

    # Single int-valued glyph advance off by one (must be exact).
    bad = copy.deepcopy(candidate)
    cell = bad["cells"][0]
    assert cell["widths"][0]["dec"].is_integer(), "fixture: first advance must be int-valued"
    cell["widths"][0] = pair(cell["widths"][0]["dec"] + 1)
    recompute_sum(cell)
    case("candidate_advance_off_one", ref_text, wrap(bad), 1, "INT_ADVANCE_MISMATCH")

    # Single fractional glyph advance off by more than the 0.5 tolerance.
    bad = copy.deepcopy(candidate)
    cell = bad["cells"][1]
    assert not cell["widths"][0]["dec"].is_integer(), "fixture: advance must be fractional"
    cell["widths"][0] = pair(cell["widths"][0]["dec"] + 1)
    recompute_sum(cell)
    case("candidate_advance_frac_off", ref_text, wrap(bad), 1, "ADVANCE_MISMATCH")

    # Single bounds int off by one (must be exact).
    bad = copy.deepcopy(candidate)
    bad["cells"][0]["bounds"]["right"] += 1
    case("candidate_bounds_off_one", ref_text, wrap(bad), 1, "BOUNDS_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["cells"][0]["measureText"] = pair(bad["cells"][0]["measureText"]["dec"] + 1)
    case("candidate_measure_off", ref_text, wrap(bad), 1, "MEASURE_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["cells"][0]["widths"][0]["dec"] = "abc"
    case("candidate_invalid_number", ref_text, wrap(bad), 1, "INVALID_NUMBER")

    bad = copy.deepcopy(candidate)
    bad["cells"].append(copy.deepcopy(bad["cells"][0]))
    case("candidate_duplicate_cell", ref_text, wrap(bad), 1, "DUPLICATE_CELL")

    bad = copy.deepcopy(candidate)
    del bad["cells"][0]
    case("candidate_missing_cell", ref_text, wrap(bad), 1, "MISSING_CELL")

    bad = copy.deepcopy(candidate)
    bad["apk"]["sha256"] = "c" * 64
    case("candidate_apk_mismatch", ref_text, wrap(bad), 1, "APK_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["status"] = "ERROR"
    case("candidate_status_error", ref_text, wrap(bad), 1, "STATUS_NOT_OK")

    case("candidate_bare_no_sentinel", ref_text, json.dumps(candidate) + "\n",
         1, "MISSING_SENTINEL")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
    print("wrote %d fixture cases to %s" % (len(cases), FIX))
    return 0


if __name__ == "__main__":
    sys.exit(main())
