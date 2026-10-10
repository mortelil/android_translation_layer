#!/usr/bin/env python3
"""verify_font_bounds.py - fail-closed comparator for the FONT-BOUNDS evidence.

Usage:
    verify_font_bounds.py <android_json> <candidate_json> [--allow-bare]

Each input is the captured FONT-BOUNDS output for one runtime. It must be
sentinel-wrapped by the probe's completion sentinels::

    FONT004_JSON_BEGIN
    { ...single JSON object... }
    FONT004_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too. The default is the
strict captured-stdout form, i.e. a sentinel is required.)

Both documents must be complete and identified before any semantic comparison:

* non-empty and sentinel-wrapped (exactly one BEGIN/END pair);
* parse as one JSON object with the frozen task id ``FONT-004``/case ``FONT-BOUNDS``;
* ``status`` OK with an empty ``exceptions`` list;
* ``font.sha256`` present and equal to the pinned bundled-font SHA-256
  (``7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954``);
* ``apk.sha256`` present on both sides and equal ("if present" on either);
* ``config.textSizes``/``config.alignments`` equal to the frozen matrix;
* a ``bounds`` list containing exactly one entry per expected cell, keyed by the
  full cell identity (size, align, overload, text, start, end); no duplicates,
  no missing cells, no extra cells;
* every cell carries ``utf16`` code units that equal the frozen source string's
  UTF-16 code units, and a valid (start,end) range.

Then, for every expected cell the four signed ink-box ints
``left``/``top``/``right``/``bottom`` are compared for EXACT equality.

Exit status:
    0  accepted: valid, identified, complete, exactly-equal pair
    1  rejected: invalid/incomplete evidence (``REJECT <CODE> <reason>`` printed)
    2  usage / internal error (never surfaced as a semantic rejection)

Rejection lines are machine-readable: ``REJECT <CODE> <reason>``.
"""
import json
import math
import os
import sys

BEGIN = "FONT004_JSON_BEGIN"
END = "FONT004_JSON_END"
DONE_MARKER = "FONT004_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "FONT-004"
CASE_ID = "FONT-BOUNDS"
EXPECTED_FONT_SHA = "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954"
EXPECTED_SIZES = [16.3, 33.4, 64.0]
EXPECTED_ALIGNS = ["LEFT", "CENTER", "RIGHT"]
INT_FIELDS = ["left", "top", "right", "bottom"]

# The frozen matrix: (name, text) pairs, plus the derived cells below.
STRINGS = [
    ("empty", ""),
    ("space", " "),
    ("Hello", "Hello"),
    ("AV", "AV"),
    ("jgy", "jgy"),
    ("ellipsis", "\u2026"),
    ("negative_bearing_j", "j"),
    ("combining_e_acute", "e\u0301"),
    ("nonbmp_u1d54f", "\U0001D54F"),
]


def u16_units(s):
    """UTF-16 code units of a Python string, as ints (Java char[] semantics)."""
    data = s.encode("utf-16-le")
    return [data[i] | (data[i + 1] << 8) for i in range(0, len(data), 2)]


def expected_cells():
    cells = []
    for size in EXPECTED_SIZES:
        for name, text in STRINGS:
            cells.append({"size": size, "align": "LEFT", "overload": "string",
                          "name": name, "text": text, "start": 0, "end": len(u16_units(text))})
    for size in EXPECTED_SIZES:
        for align in ("CENTER", "RIGHT"):
            cells.append({"size": size, "align": align, "overload": "string",
                          "name": "Hello", "text": "Hello", "start": 0, "end": 5})
            cells.append({"size": size, "align": align, "overload": "string",
                          "name": "jgy", "text": "jgy", "start": 0, "end": 3})
    for size in EXPECTED_SIZES:
        cells.append({"size": size, "align": "LEFT", "overload": "string",
                      "name": "Hello_range_1_4", "text": "Hello", "start": 1, "end": 4})
    cells.append({"size": 33.4, "align": "LEFT", "overload": "chararray",
                  "name": "Hello_chararray", "text": "Hello", "start": 0, "end": 5})
    return cells


EXPECTED_CELLS = expected_cells()


def cell_key(c):
    return (round(float(c["size"]), 3), c["align"], c["overload"],
            c["text"], int(c["start"]), int(c["end"]))


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


def finite_number(value, where):
    if not is_number(value) or math.isnan(value) or math.isinf(value):
        reject("INVALID_NUMBER", "%s is not a finite number: %r" % (where, value))
    return float(value)


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


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
        reject("EXCEPTIONS_PRESENT",
               "%s reported exceptions: %r" % (label, exceptions))

    config = require_key(doc, "config", label)
    if not isinstance(config, dict):
        reject("MISSING_FIELD", "%s config is not an object" % label)
    sizes = config.get("textSizes")
    aligns = config.get("alignments")
    if not isinstance(sizes, list) or len(sizes) != len(EXPECTED_SIZES):
        reject("CONFIG_INVALID", "%s config.textSizes is not the frozen 3 sizes" % label)
    for got, want in zip(sizes, EXPECTED_SIZES):
        if not is_number(got) or abs(float(got) - want) > 1e-3:
            reject("CONFIG_MISMATCH",
                   "%s config.textSizes entry %r != %r" % (label, got, want))
    if aligns != EXPECTED_ALIGNS:
        reject("CONFIG_MISMATCH",
               "%s config.alignments is %r, expected %r" % (label, aligns, EXPECTED_ALIGNS))

    font = require_key(doc, "font", label)
    if not isinstance(font, dict):
        reject("MISSING_FIELD", "%s font is not an object" % label)
    font_sha = font.get("sha256")
    if not isinstance(font_sha, str) or not font_sha:
        reject("MISSING_FIELD", "%s font.sha256 is not a non-empty string" % label)
    if font_sha != EXPECTED_FONT_SHA:
        reject("FONT_HASH_MISMATCH",
               "%s font.sha256 %s != pinned %s" % (label, font_sha, EXPECTED_FONT_SHA))

    bounds = require_key(doc, "bounds", label)
    if not isinstance(bounds, list) or not bounds:
        reject("MISSING_FIELD", "%s bounds is not a non-empty list" % label)

    cells = {}
    for i, entry in enumerate(bounds):
        where = "%s bounds[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        for field in ("size", "align", "overload", "text", "start", "end", "utf16"):
            if field not in entry:
                reject("MISSING_FIELD", "%s has no %r" % (where, field))
        size = round(finite_number(entry["size"], where + ".size"), 3)
        align = entry["align"]
        if align not in EXPECTED_ALIGNS:
            reject("INVALID_ALIGN", "%s align is %r" % (where, align))
        overload = entry["overload"]
        if overload not in ("string", "chararray"):
            reject("INVALID_OVERLOAD", "%s overload is %r" % (where, overload))
        text = entry["text"]
        if not isinstance(text, str):
            reject("MISSING_FIELD", "%s text is not a string" % where)
        if not is_int(entry["start"]) or not is_int(entry["end"]):
            reject("INVALID_NUMBER", "%s start/end are not ints" % where)
        start, end = entry["start"], entry["end"]
        if start < 0 or end < start or end > len(u16_units(text)):
            reject("INVALID_RANGE",
                   "%s range (%d,%d) invalid for text of UTF-16 length %d"
                   % (where, start, end, len(u16_units(text))))
        utf16 = entry["utf16"]
        if not isinstance(utf16, list) or len(utf16) != len(u16_units(text)):
            reject("INVALID_UTF16",
                   "%s utf16 has %s entries, expected %d (UTF-16 length of text)"
                   % (where, len(utf16) if isinstance(utf16, list) else "non-list",
                      len(u16_units(text))))
        want_units = u16_units(text)
        for unit in utf16:
            if not is_int(unit) or not (0 <= unit <= 0xFFFF):
                reject("INVALID_UTF16", "%s utf16 entry %r is not a UTF-16 unit" % (where, unit))
        if list(utf16) != want_units:
            reject("UTF16_MISMATCH",
                   "%s utf16 %r != UTF-16 of text %r" % (where, utf16, text))
        key = (size, align, overload, text, start, end)
        if key in cells:
            reject("DUPLICATE_CELL", "%s duplicates cell %r" % (where, key))
        if key not in EXPECTED_KEYS:
            nearest = sorted(
                k for k in EXPECTED_KEYS
                if k[2] == key[2] and k[3] == key[3] and k[1] == key[1]
                and abs(k[0] - key[0]) < 1e-3)
            reject("WRONG_CELL_IDENTITY",
                   "%s identity %r is not a frozen cell (nearest by name/text/align: %r)"
                   % (where, key, nearest[:3]))
        for field in INT_FIELDS:
            if field not in entry:
                reject("MISSING_FIELD", "%s has no %r" % (where, field))
            if not is_int(entry[field]):
                reject("INVALID_NUMBER", "%s %s is not an int: %r"
                       % (where, field, entry[field]))
        cells[key] = entry

    for key in EXPECTED_KEYS:
        if key not in cells:
            reject("MISSING_CELL", "%s lacks the expected cell %r" % (label, key))

    apk_sha = None
    if "apk" in doc:
        apk = doc["apk"]
        if not isinstance(apk, dict):
            reject("MISSING_FIELD", "%s apk is not an object" % label)
        apk_sha = apk.get("sha256")
        if not isinstance(apk_sha, str) or not apk_sha:
            reject("MISSING_FIELD", "%s apk.sha256 is not a non-empty string" % label)
    return {"task_id": task_id, "run_id": run_id, "cells": cells,
            "apk_sha": apk_sha, "sizes": sizes, "aligns": aligns}


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

    if ref["task_id"] != cand["task_id"]:
        reject("WRONG_TASK", "task ids differ: %r vs %r"
               % (ref["task_id"], cand["task_id"]))
    if ref["sizes"] != cand["sizes"] or ref["aligns"] != cand["aligns"]:
        reject("CONFIG_MISMATCH", "config matrices differ between sides")

    if ref["apk_sha"] is not None or cand["apk_sha"] is not None:
        if ref["apk_sha"] is None or cand["apk_sha"] is None:
            reject("INCONSISTENT_IDENTITY",
                   "apk.sha256 present on only one side")
        if ref["apk_sha"] != cand["apk_sha"]:
            reject("APK_MISMATCH",
                   "apk sha differs: %s vs %s" % (ref["apk_sha"], cand["apk_sha"]))

    compared = 0
    for key in sorted(EXPECTED_KEYS):
        ref_cell = ref["cells"][key]
        cand_cell = cand["cells"][key]
        size, align, overload, text, start, end = key
        for field in INT_FIELDS:
            compared += 1
            if cand_cell[field] != ref_cell[field]:
                reject("INT_MISMATCH",
                       "(%s,%s,%s,%r,%d,%d) %s android=%d candidate=%d (off by %d)"
                       % (size, align, overload, text, start, end, field,
                          ref_cell[field], cand_cell[field],
                          cand_cell[field] - ref_cell[field]))

    print("OK font-bounds match: %d cells, %d int fields compared (exact) "
          "android-run=%s candidate-run=%s"
          % (len(ref["cells"]), compared, ref["run_id"], cand["run_id"]))
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
