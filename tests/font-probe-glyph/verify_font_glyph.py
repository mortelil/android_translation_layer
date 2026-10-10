#!/usr/bin/env python3
"""verify_font_glyph.py - fail-closed comparator for the FONT-006 (G-A) evidence.

Usage:
    verify_font_glyph.py <android_json> <candidate_json> [--allow-bare]

Each input is the captured FONT-006 output for one runtime. It must be
sentinel-wrapped by the probe's completion sentinels::

    FONT006_JSON_BEGIN
    { ...single JSON object... }
    FONT006_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too. The default is the
strict captured-stdout form, i.e. a sentinel is required.)

Both documents must be complete and identified before any semantic comparison:

* non-empty and sentinel-wrapped (exactly one BEGIN/END pair);
* parse as one JSON object with task id ``FONT-006`` / case ``FONT-GLYPH``;
* ``status`` OK with an empty ``exceptions`` list;
* ``assets`` A/B/Bold hashes equal to the pinned bundled font SHA-256 values,
  and every cell's ``fontSha256`` equal to the pinned hash of its declared face;
* ``apk.sha256`` present on both sides and equal;
* ``config`` equal to the frozen matrix (sizes/strings/flagSize/flagText/
  flagVariants/styleVariants);
* a ``cells`` list containing exactly one entry per expected cell, keyed by the
  frozen identity (kind, label, size, text); no duplicates, no missing cells, no
  extra cells; every cell complete (utf16 == text, count == len(utf16) ==
  len(widths), finite float values, int bounds).

Then, for every expected cell:

* per-glyph advances: an int-valued Android advance must match EXACTLY
  (|diff| <= 1e-6); a fractional Android advance must match within 0.5;
* ``measureText`` within 0.5;
* ``sum`` (consistency-checked against the side's own widths) within 0.5 of the
  Android sum;
* ``getTextBounds`` ``left``/``top``/``right``/``bottom`` ints EXACT.

Exit status:
    0  accepted: valid, identified, complete, matching pair
    1  rejected: invalid/incomplete/mismatching evidence (``REJECT <CODE> ...``)
    2  usage / internal error (never surfaced as a semantic rejection)

Rejection lines are machine-readable: ``REJECT <CODE> <reason>``.
"""
import json
import math
import os
import sys

BEGIN = "FONT006_JSON_BEGIN"
END = "FONT006_JSON_END"
DONE_MARKER = "FONT006_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "FONT-006"
CASE_ID = "FONT-GLYPH"

SHA_A = "ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280"
SHA_B = "2d7b524ce7d51db78591b4e19c8185b89f3af0aa6534c5e524cf0c6f4ea673de"
SHA_BOLD = "5c1247acef7f2b8522a31742c76d6adcb5569bacc0be7ceaa4dc39dd252ce895"
FACE_SHA = {"A": SHA_A, "B": SHA_B, "Bold": SHA_BOLD}

SIZES = [12.5, 16.3, 33.4, 64.0]
# (name, text) in the frozen order.
STRINGS = [
    ("Hello", "Hello"),
    ("AV", "AV"),
    ("jgy", "jgy"),
    ("iiii", "iiii"),
    ("ellipsis", "\u2026"),
    ("m", "m"),
]
FLAG_SIZE = 33.4
FLAG_TEXT = "Hello"
FLAG_VARIANTS = [
    "default", "subpixel", "linear", "subpixel_linear",
    "hinting_FULL", "hinting_LINEAR", "hinting_SLIGHT", "hinting_NONE",
    "letterSpacing_0.5",
]
STYLE_VARIANTS = [
    "A_fakeBold", "A_skew", "A_fakeBold_skew",
    "B_default", "B_skew", "B_fakeBold",
    "A_create_BOLD", "bold_file",
]
STYLE_FACE = {
    "A_fakeBold": "A", "A_skew": "A", "A_fakeBold_skew": "A",
    "B_default": "B", "B_skew": "B", "B_fakeBold": "B",
    "A_create_BOLD": "A", "bold_file": "Bold",
}

BOUNDS_FIELDS = ["left", "top", "right", "bottom"]

EPS_EXACT = 1e-6
TOL_FRAC = 0.5
TOL_SUM = 0.5
TOL_MEASURE = 0.5
TOL_INTERNAL_SUM = 0.01


def u16_units(s):
    """UTF-16 code units of a Python string, as ints (Java char[] semantics)."""
    data = s.encode("utf-16-le")
    return [data[i] | (data[i + 1] << 8) for i in range(0, len(data), 2)]


def expected_cells():
    cells = []
    for size in SIZES:
        for name, text in STRINGS:
            cells.append({"kind": "size_string", "label": "A_default", "name": name,
                          "size": size, "text": text, "face": "A"})
    for variant in FLAG_VARIANTS:
        cells.append({"kind": "flag", "label": variant, "name": variant,
                      "size": FLAG_SIZE, "text": FLAG_TEXT, "face": "A"})
    for variant in STYLE_VARIANTS:
        cells.append({"kind": "style", "label": variant, "name": variant,
                      "size": FLAG_SIZE, "text": FLAG_TEXT,
                      "face": STYLE_FACE[variant]})
    return cells


EXPECTED_CELLS = expected_cells()


def cell_key(c):
    return (c["kind"], c["label"], round(float(c["size"]), 3), c["text"])


EXPECTED_KEYS = {cell_key(c): c for c in EXPECTED_CELLS}


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


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def finite_number(value, where):
    if not is_number(value) or math.isnan(float(value)) or math.isinf(float(value)):
        reject("INVALID_NUMBER", "%s is not a finite number: %r" % (where, value))
    return float(value)


def load_side(path, label, allow_bare):
    """Return (doc, sentinel_present). Fail closed on any structural problem."""
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


def require_key(doc, key, label):
    if key not in doc:
        reject("MISSING_FIELD", "%s evidence missing top-level key %r" % (label, key))
    return doc[key]


def validate_config(config, label):
    if not isinstance(config, dict):
        reject("MISSING_FIELD", "%s config is not an object" % label)
    if config.get("align") != "LEFT":
        reject("CONFIG_MISMATCH", "%s config.align is %r" % (label, config.get("align")))
    sizes = config.get("sizes")
    if not isinstance(sizes, list) or len(sizes) != len(SIZES):
        reject("CONFIG_MISMATCH", "%s config.sizes is not the frozen 4 sizes" % label)
    for got, want in zip(sizes, SIZES):
        if not is_number(got) or abs(float(got) - want) > 1e-3:
            reject("CONFIG_MISMATCH", "%s config.sizes entry %r != %r" % (label, got, want))
    strings = config.get("strings")
    want_strings = [t for _, t in STRINGS]
    if strings != want_strings:
        reject("CONFIG_MISMATCH", "%s config.strings is %r, expected %r"
               % (label, strings, want_strings))
    if not is_number(config.get("flagSize")) or abs(float(config["flagSize"]) - FLAG_SIZE) > 1e-3:
        reject("CONFIG_MISMATCH", "%s config.flagSize is %r" % (label, config.get("flagSize")))
    if config.get("flagText") != FLAG_TEXT:
        reject("CONFIG_MISMATCH", "%s config.flagText is %r" % (label, config.get("flagText")))
    if config.get("flagVariants") != FLAG_VARIANTS:
        reject("CONFIG_MISMATCH", "%s config.flagVariants is %r" % (label, config.get("flagVariants")))
    if config.get("styleVariants") != STYLE_VARIANTS:
        reject("CONFIG_MISMATCH", "%s config.styleVariants is %r" % (label, config.get("styleVariants")))


def validate_assets(assets, label):
    if not isinstance(assets, dict):
        reject("MISSING_FIELD", "%s assets is not an object" % label)
    for face, want in FACE_SHA.items():
        entry = assets.get(face)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s assets.%s is not an object" % (label, face))
        sha = entry.get("sha256")
        if not isinstance(sha, str) or not sha:
            reject("MISSING_FIELD", "%s assets.%s.sha256 is not a non-empty string"
                   % (label, face))
        if sha != want:
            reject("FONT_HASH_MISMATCH", "%s assets.%s.sha256 %s != pinned %s"
                   % (label, face, sha, want))


def validate_cells(cells_raw, label):
    cells = {}
    for i, entry in enumerate(cells_raw):
        where = "%s cells[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        for field in ("kind", "label", "size", "text", "utf16", "face",
                      "fontSha256", "count", "widths", "sum", "measureText", "bounds"):
            if field not in entry:
                reject("MISSING_FIELD", "%s has no %r" % (where, field))
        kind = entry["kind"]
        if kind not in ("size_string", "flag", "style"):
            reject("INVALID_KIND", "%s kind is %r" % (where, kind))
        label_value = entry["label"]
        if not isinstance(label_value, str) or not label_value:
            reject("MISSING_FIELD", "%s label is not a non-empty string" % where)
        size = round(finite_number(entry["size"], where + ".size"), 3)
        text = entry["text"]
        if not isinstance(text, str):
            reject("MISSING_FIELD", "%s text is not a string" % where)
        want_units = u16_units(text)
        utf16 = entry["utf16"]
        if not isinstance(utf16, list) or len(utf16) != len(want_units):
            reject("INVALID_UTF16",
                   "%s utf16 has %s entries, expected %d"
                   % (where, len(utf16) if isinstance(utf16, list) else "non-list",
                      len(want_units)))
        for unit in utf16:
            if not is_int(unit) or not (0 <= unit <= 0xFFFF):
                reject("INVALID_UTF16", "%s utf16 entry %r is not a UTF-16 unit" % (where, unit))
        if list(utf16) != want_units:
            reject("UTF16_MISMATCH", "%s utf16 %r != UTF-16 of text %r" % (where, utf16, text))

        face = entry["face"]
        if face not in FACE_SHA:
            reject("INVALID_FACE", "%s face is %r" % (where, face))
        font_sha = entry["fontSha256"]
        if not isinstance(font_sha, str) or not font_sha:
            reject("MISSING_FIELD", "%s fontSha256 is not a non-empty string" % where)
        if font_sha != FACE_SHA[face]:
            reject("FONT_HASH_MISMATCH", "%s fontSha256 %s != pinned %s for face %s"
                   % (where, font_sha, FACE_SHA[face], face))

        count = entry["count"]
        if not is_int(count) or count < 0:
            reject("INVALID_NUMBER", "%s count is not a non-negative int: %r" % (where, count))
        widths = entry["widths"]
        if not isinstance(widths, list):
            reject("MISSING_FIELD", "%s widths is not a list" % where)
        if count != len(want_units) or len(widths) != count:
            reject("INVALID_COUNT",
                   "%s count=%d widths=%d utf16=%d (must all be equal)"
                   % (where, count, len(widths), len(want_units)))
        for j, item in enumerate(widths):
            if not isinstance(item, dict):
                reject("MISSING_FIELD", "%s widths[%d] is not an object" % (where, j))
            if "hex" not in item:
                reject("MISSING_FIELD", "%s widths[%d] has no 'hex'" % (where, j))
            if not isinstance(item["hex"], str) or not item["hex"]:
                reject("MISSING_FIELD", "%s widths[%d].hex is not a non-empty string"
                       % (where, j))
            if "dec" not in item:
                reject("MISSING_FIELD", "%s widths[%d] has no 'dec'" % (where, j))
            finite_number(item["dec"], "%s widths[%d].dec" % (where, j))

        for field in ("sum", "measureText"):
            pair = entry[field]
            if not isinstance(pair, dict) or "dec" not in pair:
                reject("MISSING_FIELD", "%s %s is not a {hex,dec} object" % (where, field))
            finite_number(pair["dec"], "%s %s.dec" % (where, field))

        bounds = entry["bounds"]
        if not isinstance(bounds, dict):
            reject("MISSING_FIELD", "%s bounds is not an object" % where)
        for field in BOUNDS_FIELDS:
            if field not in bounds:
                reject("MISSING_FIELD", "%s bounds has no %r" % (where, field))
            if not is_int(bounds[field]):
                reject("INVALID_NUMBER", "%s bounds.%s is not an int: %r"
                       % (where, field, bounds[field]))

        key = cell_key({"kind": kind, "label": label_value, "size": size, "text": text})
        if key in cells:
            reject("DUPLICATE_CELL", "%s duplicates cell %r" % (where, key))
        if key not in EXPECTED_KEYS:
            reject("WRONG_CELL_IDENTITY",
                   "%s identity %r is not a frozen cell" % (where, key))
        cells[key] = entry

    for key in EXPECTED_KEYS:
        if key not in cells:
            reject("MISSING_CELL", "%s lacks the expected cell %r" % (label, key))
    return cells


def validate_doc(doc, label):
    task_id = require_key(doc, "task_id", label)
    if not isinstance(task_id, str) or not task_id:
        reject("MISSING_FIELD", "%s task_id is not a non-empty string" % label)
    if task_id != TASK_ID:
        reject("WRONG_TASK", "%s task_id is %r, expected %r" % (label, task_id, TASK_ID))
    case_id = doc.get("case_id")
    if case_id is not None and case_id != CASE_ID:
        reject("WRONG_CASE", "%s case_id is %r, expected %r" % (label, case_id, CASE_ID))
    run_id = require_key(doc, "run_id", label)
    if not isinstance(run_id, str) or not run_id:
        reject("MISSING_FIELD", "%s run_id is not a non-empty string" % label)
    status = require_key(doc, "status", label)
    if status != "OK":
        reject("STATUS_NOT_OK", "%s status is %r" % (label, status))
    exceptions = require_key(doc, "exceptions", label)
    if not isinstance(exceptions, list):
        reject("MISSING_FIELD", "%s exceptions is not a list" % label)
    if exceptions:
        reject("EXCEPTIONS_PRESENT", "%s reported exceptions: %r" % (label, exceptions))

    validate_config(require_key(doc, "config", label), label)
    validate_assets(require_key(doc, "assets", label), label)

    apk = require_key(doc, "apk", label)
    if not isinstance(apk, dict):
        reject("MISSING_FIELD", "%s apk is not an object" % label)
    apk_sha = apk.get("sha256")
    if not isinstance(apk_sha, str) or not apk_sha:
        reject("MISSING_FIELD", "%s apk.sha256 is not a non-empty string" % label)

    cells_raw = require_key(doc, "cells", label)
    if not isinstance(cells_raw, list) or not cells_raw:
        reject("MISSING_FIELD", "%s cells is not a non-empty list" % label)
    cells = validate_cells(cells_raw, label)

    return {"run_id": run_id, "apk_sha": apk_sha, "cells": cells}


def internal_sum_ok(entry, where):
    recomputed = sum(float(w["dec"]) for w in entry["widths"])
    recorded = float(entry["sum"]["dec"])
    if abs(recomputed - recorded) > TOL_INTERNAL_SUM:
        reject("INVALID_SUM", "%s sum %.9g != sum of widths %.9g"
               % (where, recorded, recomputed))


def compare_cells(ref, cand):
    compared = 0
    for key in sorted(EXPECTED_KEYS):
        kind, label, size, text = key
        a = ref["cells"][key]
        c = cand["cells"][key]
        where = "(%s,%s,%.1f,%r)" % (kind, label, size, text)

        internal_sum_ok(a, "android " + where)
        internal_sum_ok(c, "candidate " + where)

        aw = [float(w["dec"]) for w in a["widths"]]
        cw = [float(w["dec"]) for w in c["widths"]]
        for j, av in enumerate(aw):
            cv = cw[j]
            compared += 1
            if av.is_integer():
                if abs(cv - av) > EPS_EXACT:
                    reject("INT_ADVANCE_MISMATCH",
                           "%s glyph[%d] android=%s candidate=%s (int-valued, must be exact)"
                           % (where, j, repr(av), repr(cv)))
            else:
                if abs(cv - av) > TOL_FRAC:
                    reject("ADVANCE_MISMATCH",
                           "%s glyph[%d] android=%s candidate=%s (|diff|>%s)"
                           % (where, j, repr(av), repr(cv), TOL_FRAC))

        if abs(float(c["sum"]["dec"]) - float(a["sum"]["dec"])) > TOL_SUM:
            reject("SUM_MISMATCH", "%s sum android=%s candidate=%s"
                   % (where, a["sum"]["dec"], c["sum"]["dec"]))
        if abs(float(c["measureText"]["dec"]) - float(a["measureText"]["dec"])) > TOL_MEASURE:
            reject("MEASURE_MISMATCH", "%s measureText android=%s candidate=%s"
                   % (where, a["measureText"]["dec"], c["measureText"]["dec"]))
        for field in BOUNDS_FIELDS:
            if c["bounds"][field] != a["bounds"][field]:
                reject("BOUNDS_MISMATCH", "%s bounds.%s android=%d candidate=%d (off by %d)"
                       % (where, field, a["bounds"][field], c["bounds"][field],
                          c["bounds"][field] - a["bounds"][field]))
    return compared


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
        reject("APK_MISMATCH", "apk sha differs: %s vs %s"
               % (ref["apk_sha"], cand["apk_sha"]))

    compared = compare_cells(ref, cand)
    print("OK glyph match: %d cells, %d advance values compared "
          "(int exact, fractional <=%s; measureText <=%s; bounds exact) "
          "android-run=%s candidate-run=%s"
          % (len(ref["cells"]), compared, TOL_FRAC, TOL_MEASURE,
             ref["run_id"], cand["run_id"]))
    return EXIT_OK


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
