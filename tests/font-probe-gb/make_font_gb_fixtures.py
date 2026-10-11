#!/usr/bin/env python3
"""make_font_gb_fixtures.py - regenerate the FONT-007 comparator fixtures.

The fixtures are synthetic (no Android/ATL run) and exercise the fail-closed
comparator ``verify_font_gb.py``:

    valid_complete_pair        -> exit 0, OK (the matched pair that MUST pass)
    android_empty              -> EMPTY_EVIDENCE
    candidate_empty            -> EMPTY_EVIDENCE
    candidate_malformed        -> JSON_PARSE
    candidate_missing_field    -> MISSING_FIELD
    candidate_wrong_asset_hash -> FONT_HASH_MISMATCH
    candidate_wrong_cell_label -> WRONG_CELL_IDENTITY
    candidate_missing_cell     -> MISSING_CELL (cell present on one side only)
    candidate_duplicate_cell   -> DUPLICATE_CELL
    candidate_advance_off_one  -> INT_ADVANCE_MISMATCH (single int advance, +1)
    candidate_bounds_off_one   -> BOUNDS_MISMATCH (single bounds int, +1)
    candidate_gap_vs_value     -> CAPABILITY_GAP (exception vs value on one cell)

Corrected group-B rule fixtures (see verify_font_gb.py "Contract correction"):
    candidate_b_uniform_empty_ink    -> B_EMPTY_INK (uniform advances + zero ink)
    candidate_b_one_sided_exception  -> CAPABILITY_GAP (one-sided exception)
    candidate_b_nonscaling           -> B_NO_SCALING (aggregates do not scale)
    candidate_b_nonincreasing        -> B_NO_SCALING (aggregates scale, one glyph shrinks)
    candidate_b_android_verified     -> OK (Android's frozen DejaVu B numbers; the
                                         small `l` advance grows 3 -> 9, ratio 3.0)
    candidate_b_valid_scaled         -> OK (scaled, non-empty ink that MUST pass)
"""
import copy
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
sys.path.insert(0, HERE)
import verify_font_gb as v  # noqa: E402

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


def requested_for(cell):
    if cell["kind"] in ("V_paint", "V_builder"):
        axis = cell["label"].split("_")[2]
        return dict(v.AXES).get(axis)
    return None


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
            "vStrings": [t for _, t in v.V_STRINGS],
            "vAxisNames": [a for a, _ in v.AXES],
            "vAxisSettings": {a: s for a, s in v.AXES},
            "fText": v.F_TEXT,
            "bText": v.B_TEXT,
        },
        "assets": {
            "dejavu": {"asset": "DejaVuSans.ttf", "sourceName": "DejaVuSans.ttf",
                       "sha256": v.ASSET_SHA["dejavu"], "role": "primary"},
            "ubuntu": {"asset": "Ubuntu-Variable.ttf",
                       "sourceName": "Ubuntu[wdth,wght].ttf",
                       "sha256": v.ASSET_SHA["ubuntu"], "role": "variable"},
            "noto": {"asset": "NotoSansCJK-Regular.ttc",
                     "sourceName": "NotoSansCJK-Regular.ttc",
                     "sha256": v.ASSET_SHA["noto"], "role": "fallback"},
            "bitmap": {"asset": "8x13.pcf",
                       "sourceName": "8x13-ISO8859-1.pcf.gz (decompressed -> 8x13.pcf)",
                       "sha256": v.ASSET_SHA["bitmap"], "role": "bitmap"},
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


def b_row(cell, units):
    """Synthetic B (bitmap face) cell that satisfies the corrected structural
    invariants: non-empty ink box and advances/bounds scaling with size."""
    size = cell["size"]
    ratio = size / 16.3                       # size ratio vs the small size
    base_adv = [11.0, 8.0, 4.0, 4.0, 9.0]     # Android-like "Hello" advances @16.3
    vals = [f32(a * ratio) for a in base_adv]
    total = f32(sum(vals))
    ink_w = int(round(35.0 * ratio))
    ink_h = int(round(13.0 * ratio))
    left = 1
    right = left + ink_w
    top = -ink_h
    bottom = 0
    return {
        "kind": cell["kind"], "label": cell["label"], "name": cell["name"],
        "size": size, "text": cell["text"], "utf16": units,
        "asset": cell["asset"], "fontSha256": v.ASSET_SHA[cell["asset"]],
        "requested": None, "applied": None, "resolution": "value",
        "count": len(units), "widths": [pair(w) for w in vals],
        "sum": pair(total), "measureText": pair(total),
        "bounds": {"left": left, "top": top, "right": right, "bottom": bottom},
        "typefaceEcho": {"weight": 400, "italic": False},
        "exception": None,
    }


def base_cells():
    rows = []
    for i, c in enumerate(v.EXPECTED_CELLS):
        text = c["text"]
        units = v.u16_units(text)
        n = len(units)
        if c["kind"] == "B":
            rows.append(b_row(c, units))
            continue
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
        applied = None
        if c["kind"] == "V_paint":
            applied = None if requested_for(c) is None else True
        row = {
            "kind": c["kind"], "label": c["label"], "name": c["name"],
            "size": c["size"], "text": text, "utf16": units,
            "asset": c["asset"], "fontSha256": v.ASSET_SHA[c["asset"]],
            "requested": requested_for(c), "applied": applied, "resolution": "value",
            "count": n, "widths": widths,
            "sum": pair(total), "measureText": pair(f32(total + 0.5)),
            "bounds": {"left": -(i % 5 + 1), "top": -(int(c["size"]) + i % 3),
                       "right": i % 7 + 1 + int(c["size"]),
                       "bottom": int(c["size"] // 4) + i % 5},
            "typefaceEcho": {"weight": 400, "italic": False},
            "exception": None,
        }
        rows.append(row)
    return rows


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
         BEGIN + "\n{\"task_id\": \"FONT-007\",\n" + END + "\n", 1, "JSON_PARSE")

    bad = copy.deepcopy(candidate)
    del bad["cells"][0]["widths"]
    case("candidate_missing_field", ref_text, wrap(bad), 1, "MISSING_FIELD")

    bad = copy.deepcopy(candidate)
    bad["assets"]["ubuntu"]["sha256"] = "b" * 64
    case("candidate_wrong_asset_hash", ref_text, wrap(bad), 1, "FONT_HASH_MISMATCH")

    bad = copy.deepcopy(candidate)
    bad["cells"][0]["label"] = "V_paint_bogus_Hello"
    case("candidate_wrong_cell_label", ref_text, wrap(bad), 1, "WRONG_CELL_IDENTITY")

    bad = copy.deepcopy(candidate)
    del bad["cells"][0]
    case("candidate_missing_cell", ref_text, wrap(bad), 1, "MISSING_CELL")

    bad = copy.deepcopy(candidate)
    bad["cells"].append(copy.deepcopy(bad["cells"][0]))
    case("candidate_duplicate_cell", ref_text, wrap(bad), 1, "DUPLICATE_CELL")

    # Single int-valued glyph advance off by one (must be exact).
    bad = copy.deepcopy(candidate)
    cell = bad["cells"][0]
    assert cell["widths"][0]["dec"].is_integer(), "fixture: first advance must be int-valued"
    cell["widths"][0] = pair(cell["widths"][0]["dec"] + 1)
    recompute_sum(cell)
    case("candidate_advance_off_one", ref_text, wrap(bad), 1, "INT_ADVANCE_MISMATCH")

    # Single bounds int off by one (must be exact).
    bad = copy.deepcopy(candidate)
    bad["cells"][0]["bounds"]["right"] += 1
    case("candidate_bounds_off_one", ref_text, wrap(bad), 1, "BOUNDS_MISMATCH")

    # One side reports a value, the other an exception -> CAPABILITY_GAP.
    bad = copy.deepcopy(candidate)
    cell = bad["cells"][0]
    assert cell["resolution"] == "value"
    cell["resolution"] = "exception"
    cell["exception"] = "java.lang.RuntimeException: synthetic gap"
    cell["count"] = 0
    cell["widths"] = []
    case("candidate_gap_vs_value", ref_text, wrap(bad), 1, "CAPABILITY_GAP")

    # --- corrected group-B (bitmap face) rule fixtures -----------------------
    # Today's ATL output: uniform advances everywhere AND a zero ink box.
    bad = copy.deepcopy(candidate)
    for cell in bad["cells"]:
        if cell["kind"] == "B":
            cell["widths"] = [pair(8.0) for _ in cell["widths"]]
            recompute_sum(cell)
            cell["measureText"] = pair(40.0)
            cell["bounds"] = {"left": 0, "top": 0, "right": 0, "bottom": 0}
    case("candidate_b_uniform_empty_ink", ref_text, wrap(bad), 1, "B_EMPTY_INK")

    # One-sided exception on the bitmap face -> CAPABILITY_GAP (nonzero).
    bad = copy.deepcopy(candidate)
    cell = [c for c in bad["cells"] if c["kind"] == "B"][0]
    cell["resolution"] = "exception"
    cell["exception"] = "java.io.IOException: synthetic bitmap gap"
    cell["count"] = 0
    cell["widths"] = []
    case("candidate_b_one_sided_exception", ref_text, wrap(bad), 1, "CAPABILITY_GAP")

    # Non-scaling candidate: the large-size cell repeats the small-size advances
    # (ink box still non-empty) -> B_NO_SCALING.
    bad = copy.deepcopy(candidate)
    b = [c for c in bad["cells"] if c["kind"] == "B"]
    for j, w in enumerate(b[0]["widths"]):
        b[1]["widths"][j] = pair(w["dec"])
    recompute_sum(b[1])
    b[1]["measureText"] = pair(b[0]["measureText"]["dec"])
    case("candidate_b_nonscaling", ref_text, wrap(bad), 1, "B_NO_SCALING")

    # Aggregates scale (sum 105/50 = 2.1, ink 74/35 = 2.114, 26/13 = 2.0) but
    # one glyph shrinks (10 -> 5): B_NO_SCALING (monotonic non-decrease).
    bad = copy.deepcopy(candidate)
    b = [c for c in bad["cells"] if c["kind"] == "B"]
    b[0]["widths"] = [pair(w) for w in (10.0, 10.0, 10.0, 10.0, 10.0)]
    b[0]["bounds"] = {"left": 1, "top": -13, "right": 36, "bottom": 0}
    b[1]["widths"] = [pair(w) for w in (25.0, 25.0, 25.0, 25.0, 5.0)]
    b[1]["bounds"] = {"left": 1, "top": -26, "right": 75, "bottom": 0}
    for cell in b:
        recompute_sum(cell)
        cell["measureText"] = pair(cell["sum"]["dec"])
    case("candidate_b_nonincreasing", ref_text, wrap(bad), 1, "B_NO_SCALING")

    # Android-verified scalable-face numbers (the frozen FONT-006 DejaVu pin for
    # the same face/bytes/sizes): aggregate ratios sum 83/37 = 2.243, ink
    # 79/35 = 2.257 / 25/12 = 2.083 are all in band, while the `l` advance grows
    # 3 -> 9 (ratio 3.0). MUST pass -- a regression pin against reintroducing a
    # per-glyph ratio band.
    good = copy.deepcopy(candidate)
    b = [c for c in good["cells"] if c["kind"] == "B"]
    b[0]["widths"] = [pair(w) for w in (12.0, 9.0, 3.0, 3.0, 10.0)]
    b[0]["bounds"] = {"left": 1, "top": -12, "right": 36, "bottom": 0}
    b[1]["widths"] = [pair(w) for w in (25.0, 20.0, 9.0, 9.0, 20.0)]
    b[1]["bounds"] = {"left": 3, "top": -25, "right": 82, "bottom": 0}
    for cell in b:
        recompute_sum(cell)
        cell["measureText"] = pair(cell["sum"]["dec"])
    case("candidate_b_android_verified", ref_text, wrap(good), 0, "OK")

    # A valid matched pair (scaled advances + non-empty ink) MUST pass.
    case("candidate_b_valid_scaled", ref_text, cand_text, 0, "OK")

    # Bare (no sentinel) candidate: rejected strictly, accepted with --allow-bare.
    case("candidate_bare_no_sentinel", ref_text, json.dumps(candidate) + "\n",
         1, "MISSING_SENTINEL")

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
    print("wrote %d fixture cases to %s" % (len(cases), FIX))
    return 0


if __name__ == "__main__":
    sys.exit(main())
