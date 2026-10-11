#!/usr/bin/env python3
"""verify_font_metrics.py - fail-closed comparator for the FONT-003 evidence.

Usage:
    verify_font_metrics.py <android_json> <candidate_json> [--tolerance F]

Each input is the captured FONT-003 output for one runtime. It must be
sentinel-wrapped by the probe's completion sentinels::

    FONT003_JSON_BEGIN
    { ...single JSON object... }
    FONT003_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too. The default is the
strict captured-stdout form, i.e. a sentinel is required.)

Both documents must be complete and identified before any semantic comparison:

* non-empty and sentinel-wrapped (exactly one BEGIN/END pair);
* parse as one JSON object with the frozen task id ``FONT-003``;
* ``status`` OK with an empty ``exceptions`` list;
* ``font.sha256`` present and equal to the pinned bundled-font SHA-256
  (``7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954``);
* ``apk.sha256`` present on both sides and equal ("if present" on either);
* ``config.textSizes``/``config.styles`` equal to the frozen matrix;
* exactly one ``metrics`` entry per (size, style) matrix cell, no duplicates.

Then, for every (size, style) cell, ``getFontMetrics()`` floats are compared with
an absolute tolerance (default ``<= 0.5`` px) and ``getFontMetricsInt()`` ints are
compared for EXACT equality.

Exit status:
    0  accepted: valid, identified, complete, in-tolerance pair
    1  rejected: invalid/incomplete evidence (``REJECT <CODE> <reason>`` printed)
    2  usage / internal error (never surfaced as a semantic rejection)

Rejection lines are machine-readable: ``REJECT <CODE> <reason>``.
"""
import json
import math
import os
import sys

BEGIN = "FONT003_JSON_BEGIN"
END = "FONT003_JSON_END"
DONE_MARKER = "FONT003_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "FONT-003"
EXPECTED_FONT_SHA = "7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954"
EXPECTED_SIZES = [12.3, 15.7, 33.4, 47.9, 64.0, 100.91]
EXPECTED_STYLES = ["normal", "bold_italic"]
FLOAT_FIELDS = ["ascent", "descent", "top", "bottom", "leading"]
INT_FIELDS = ["top", "ascent", "descent", "bottom", "leading"]
DEFAULT_TOLERANCE = 0.5


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
    styles = config.get("styles")
    if not isinstance(sizes, list) or len(sizes) != len(EXPECTED_SIZES):
        reject("CONFIG_INVALID", "%s config.textSizes is not the frozen 6 sizes" % label)
    for got, want in zip(sizes, EXPECTED_SIZES):
        if not is_number(got) or abs(float(got) - want) > 1e-3:
            reject("CONFIG_MISMATCH",
                   "%s config.textSizes entry %r != %r" % (label, got, want))
    if styles != EXPECTED_STYLES:
        reject("CONFIG_MISMATCH",
               "%s config.styles is %r, expected %r" % (label, styles, EXPECTED_STYLES))

    font = require_key(doc, "font", label)
    if not isinstance(font, dict):
        reject("MISSING_FIELD", "%s font is not an object" % label)
    font_sha = font.get("sha256")
    if not isinstance(font_sha, str) or not font_sha:
        reject("MISSING_FIELD", "%s font.sha256 is not a non-empty string" % label)
    if font_sha != EXPECTED_FONT_SHA:
        reject("FONT_HASH_MISMATCH",
               "%s font.sha256 %s != pinned %s" % (label, font_sha, EXPECTED_FONT_SHA))

    metrics = require_key(doc, "metrics", label)
    if not isinstance(metrics, list) or not metrics:
        reject("MISSING_FIELD", "%s metrics is not a non-empty list" % label)

    cells = {}
    for i, entry in enumerate(metrics):
        where = "%s metrics[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        if "size" not in entry:
            reject("MISSING_FIELD", "%s has no size" % where)
        size = round(finite_number(entry["size"], where + ".size"), 3)
        if "style" not in entry:
            reject("MISSING_FIELD", "%s has no style" % where)
        style = entry["style"]
        if not isinstance(style, str) or not style:
            reject("MISSING_FIELD", "%s style is not a non-empty string" % where)
        key = (size, style)
        if key in cells:
            reject("DUPLICATE_CELL", "%s duplicates the (%s, %s) cell"
                   % (label, size, style))
        fm = entry.get("fm")
        fmi = entry.get("fmi")
        if not isinstance(fm, dict):
            reject("MISSING_FIELD", "%s is missing the 'fm' float object" % where)
        if not isinstance(fmi, dict):
            reject("MISSING_FIELD", "%s is missing the 'fmi' int object" % where)
        for field in FLOAT_FIELDS:
            if field not in fm:
                reject("MISSING_FIELD", "%s fm.%s is absent" % (where, field))
            finite_number(fm[field], "%s fm.%s" % (where, field))
        for field in INT_FIELDS:
            if field not in fmi:
                reject("MISSING_FIELD", "%s fmi.%s is absent" % (where, field))
            if not is_int(fmi[field]):
                reject("INVALID_NUMBER", "%s fmi.%s is not an int: %r"
                       % (where, field, fmi[field]))
        cells[key] = (fm, fmi)

    expected_cells = [(round(float(s), 3), st)
                      for s in EXPECTED_SIZES for st in EXPECTED_STYLES]
    for key in expected_cells:
        if key not in cells:
            reject("MISSING_CELL", "%s lacks the (%s, %s) metrics cell"
                   % (label, key[0], key[1]))
    if len(cells) != len(expected_cells):
        reject("EXTRA_CELL", "%s has %d metric cells, expected %d"
               % (label, len(cells), len(expected_cells)))

    apk_sha = None
    if "apk" in doc:
        apk = doc["apk"]
        if not isinstance(apk, dict):
            reject("MISSING_FIELD", "%s apk is not an object" % label)
        apk_sha = apk.get("sha256")
        if not isinstance(apk_sha, str) or not apk_sha:
            reject("MISSING_FIELD", "%s apk.sha256 is not a non-empty string" % label)
    return {"task_id": task_id, "run_id": run_id, "cells": cells,
            "apk_sha": apk_sha, "sizes": sizes, "styles": styles}


def main(argv):
    tolerance = DEFAULT_TOLERANCE
    allow_bare = False
    positional = []
    for arg in argv:
        if arg == "--allow-bare":
            allow_bare = True
        elif arg.startswith("--tolerance="):
            try:
                tolerance = float(arg.split("=", 1)[1])
            except ValueError:
                usage("bad --tolerance value in %r" % arg)
        elif arg.startswith("--"):
            usage("unknown option %r" % arg)
        else:
            positional.append(arg)

    if len(positional) != 2:
        usage("expected <android_json> <candidate_json>")
    if tolerance < 0:
        usage("tolerance must be >= 0")

    ref_doc, ref_sentinel = load_side(positional[0], "android", allow_bare)
    cand_docs, cand_sentinel = load_side(positional[1], "candidate", allow_bare)
    if not allow_bare and (not ref_sentinel or not cand_sentinel):
        reject("MISSING_SENTINEL", "both sides must be sentinel-wrapped evidence")

    ref = validate_doc(ref_doc, "android")
    cand = validate_doc(cand_docs, "candidate")

    if ref["task_id"] != cand["task_id"]:
        reject("WRONG_TASK", "task ids differ: %r vs %r"
               % (ref["task_id"], cand["task_id"]))
    if ref["sizes"] != cand["sizes"] or ref["styles"] != cand["styles"]:
        reject("CONFIG_MISMATCH", "config matrices differ between sides")

    if ref["apk_sha"] is not None or cand["apk_sha"] is not None:
        if ref["apk_sha"] is None or cand["apk_sha"] is None:
            reject("INCONSISTENT_IDENTITY",
                   "apk.sha256 present on only one side")
        if ref["apk_sha"] != cand["apk_sha"]:
            reject("APK_MISMATCH",
                   "apk sha differs: %s vs %s" % (ref["apk_sha"], cand["apk_sha"]))

    compared = 0
    for key in sorted(ref["cells"]):
        ref_fm, ref_fmi = ref["cells"][key]
        cand_fm, cand_fmi = cand["cells"][key]
        size, style = key
        for field in FLOAT_FIELDS:
            delta = abs(float(cand_fm[field]) - float(ref_fm[field]))
            if delta > tolerance:
                reject("TOLERANCE_EXCEEDED",
                       "(%s,%s) fm.%s |ATL-Android|=%.6f > %.6f (android=%r candidate=%r)"
                       % (size, style, field, delta, tolerance,
                          ref_fm[field], cand_fm[field]))
            compared += 1
        for field in INT_FIELDS:
            if cand_fmi[field] != ref_fmi[field]:
                reject("INT_MISMATCH",
                       "(%s,%s) fmi.%s android=%d candidate=%d (off by %d)"
                       % (size, style, field, ref_fmi[field], cand_fmi[field],
                          cand_fmi[field] - ref_fmi[field]))
            compared += 1

    print("OK font-metrics match: %d cells, %d fields compared "
          "(float tol <= %.6f, ints exact) android-run=%s candidate-run=%s"
          % (len(ref["cells"]), compared, tolerance, ref["run_id"], cand["run_id"]))
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
