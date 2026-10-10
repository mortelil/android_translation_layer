#!/usr/bin/env python3
"""verify_font_gb.py - fail-closed comparator for the FONT-007 (G-B) evidence.

Usage:
    verify_font_gb.py <android_json> <candidate_json> [--allow-bare]

Each input is the captured FONT-007 output for one runtime. It must be
sentinel-wrapped by the probe's completion sentinels::

    FONT007_JSON_BEGIN
    { ...single JSON object... }
    FONT007_JSON_END

(The app's plain ``files/results.json`` carries the same object without the
sentinels; pass ``--allow-bare`` to accept that form too. The default is the
strict captured-stdout form, i.e. a sentinel is required.)

Both documents must be complete and identified before any semantic comparison:

* non-empty and sentinel-wrapped (exactly one BEGIN/END pair);
* parse as one JSON object with task id ``FONT-007`` / case ``FONT-GLYPH-GB``;
* ``status`` OK with an empty top-level ``exceptions`` list;
* ``assets`` dejavu/ubuntu/noto/bitmap hashes equal to the pinned bundled font
  SHA-256 values, and every cell's ``fontSha256`` equal to the pinned hash of
  its declared asset;
* ``apk.sha256`` present on both sides and equal;
* ``config`` equal to the frozen matrix (sizes/vStrings/vAxisNames/
  vAxisSettings/fText/bText);
* a ``cells`` list containing exactly one entry per expected cell, keyed by the
  frozen identity (kind, label, size, text); no duplicates, no missing cells, no
  extra cells; every cell complete (utf16 == text, count == len(utf16) ==
  len(widths), finite float values, int bounds).

Cell resolution classes (recorded per side by the probe):

* ``value``        the API produced values (or the axis-setting was applied)
* ``axis_ignored`` (V only) the API reported the setting was NOT applied
* ``exception``    the call threw; the class+message is recorded in the cell

Semantic comparison, per expected cell:

* the resolution class must be IDENTICAL on both sides. Where it differs the run
  is a CAPABILITY_GAP (a value on one side, an exception/ignored setting on the
  other) and the comparator rejects with the distinct code CAPABILITY_GAP.
* where both sides are ``value``: an int-valued Android advance must match
  EXACTLY (|diff| <= 1e-6); a fractional Android advance must match within 0.5;
  ``measureText`` within 0.5; ``sum`` within 0.5 (and internally consistent with
  the side's own widths); ``getTextBounds`` ints EXACT.
* group **B (bitmap face)** is NOT compared against Android's absolute numbers
  (see "Contract correction — group B" below); it is judged on structural
  invariants, and Android's B values are echoed as informational
  ``NOT_COMPARABLE`` context only.
* where both sides agree on ``exception`` or ``axis_ignored``, that is an
  equivalent capability outcome (e.g. for V/F both reject an API) and is
  accepted, with the classes tallied in the OK line. For B it is NOT accepted:
  both sides must resolve ``value``.

Contract correction — group B (post-frozen-RED; the original "scale the bitmap
strike" comparison was wrong). The frozen Android reference resolves a scalable
system fallback for the raw non-sfnt PCF face (minikin reads coverage via the
base font's ``cmap``, absent for PCF) while ATL uses the 8x13 strike, so the two
sides resolve DIFFERENT fonts. B's absolute values are therefore not
cross-platform comparable and are recorded as informational only. The candidate
is judged on invariants that must hold for any usable face, and that today's ATL
output violates:

* both sides record ``resolution == "value"`` and no exception (a one-sided
  exception is a ``CAPABILITY_GAP``; both non-value is ``B_NO_VALUE``);
* the candidate's ink box is non-empty (``right > left`` and ``bottom > top``),
  else ``B_EMPTY_INK``;
* scaling is judged on AGGREGATES, not per glyph: the candidate's own advance
  SUM, ink WIDTH and ink HEIGHT must each grow by the size ratio (16.3 -> 33.4,
  ~2.0491) within ``B_SCALE_REL_TOL``, else ``B_NO_SCALING``;
* per glyph, only monotonic NON-DECREASE is required (``a33 >= a16``), never a
  ratio band, else ``B_NO_SCALING``. Rationale: hinting quantizes small
  advances, so a correct scalable face legitimately grows a 3 px advance to
  9 px (ratio 3.0) for a 2.049x size ratio -- Android's own frozen FONT-006
  DejaVu pin does exactly that (``Hello`` ``l`` 3 -> 9), so a per-glyph ratio
  band would reject Android-verified-correct output.

The aggregate band rejects the uniform-advance strike output (``8 -> 8``, sum
ratio 1.0); the non-empty-ink check and the monotonic rule reject it too (zero
ink box).

Exit status:
    0  accepted: valid, identified, complete, matching pair
    1  rejected: invalid/incomplete/mismatching evidence
       (``REJECT <CODE> ...``; CAPABILITY_GAP is a distinct code)
    2  usage / internal error (never surfaced as a semantic rejection)

Rejection lines are machine-readable: ``REJECT <CODE> <reason>``.
"""
import json
import math
import os
import sys

BEGIN = "FONT007_JSON_BEGIN"
END = "FONT007_JSON_END"
DONE_MARKER = "FONT007_DONE"

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "FONT-007"
CASE_ID = "FONT-GLYPH-GB"

ASSET_SHA = {
    "dejavu": "ae7b7855e115a5966d8b1b3f80f254ccc117ec86f9965e202ee2940453837280",
    "ubuntu": "28c4c189a44803b1986fd16074187034dc6d94ad35f5e87de13dd0e786b70b73",
    "noto": "b76b0433203017ca80401b2ee0dd69350349871c4b19d504c34dbdd80541690a",
    "bitmap": "325f347db4ab1da0a461414e62e4e142401b6296e9f519823b139ceaeff82348",
}

SIZES = [16.3, 33.4]
# (name, text) in the frozen order.
V_STRINGS = [("Hello", "Hello"), ("AV", "AV"), ("m", "m")]
# (axis name, settings) in the frozen order; settings None == unset default.
AXES = [
    ("default", None),
    ("wght100", "'wght' 100"),
    ("wght700", "'wght' 700"),
    ("wdth75", "'wdth' 75"),
]
F_TEXT = "A\u4E16B"
B_TEXT = "Hello"

ASSET_KEYS = ["dejavu", "ubuntu", "noto", "bitmap"]
RESOLUTIONS = ("value", "axis_ignored", "exception")
BOUNDS_FIELDS = ["left", "top", "right", "bottom"]

EPS_EXACT = 1e-6
TOL_FRAC = 0.5
TOL_SUM = 0.5
TOL_MEASURE = 0.5
TOL_INTERNAL_SUM = 0.01

# Group B (bitmap face) structural-invariant tolerance. The candidate's own
# two sizes (16.3 -> 33.4, size ratio ~2.0491) must scale its AGGREGATES
# (advance sum, ink width, ink height) by the same ratio: 0.25 relative
# (|ratio - 2.0491| <= 0.25 * 2.0491 ~= 0.5123). Justification:
#   * the frozen Android reference itself scales its fallback aggregates
#     ~2.1x / 2.0x for a 2.049x size ratio (<= ~6% deviation), so a correct
#     scalable face sits well inside;
#   * integer/fractional advance quantization at the small size is absorbed by
#     summing the advances (the per-glyph quantization +/-1 on each of 5 glyphs
#     is a ~5% aggregate deviation, not a per-glyph failure);
#   * the strike-based uniform output (sum ratio 1.0, ~51% deviation) and any
#     size-independent output are rejected with margin.
# Per glyph only monotonic non-decrease is required: hinting quantizes small
# advances, so a correct face may grow a 3 px advance to 9 px (ratio 3.0) for
# the 2.049x size ratio -- Android's frozen FONT-006 DejaVu pin does exactly
# that, so a per-glyph ratio band is inadmissible.
B_SCALE_REL_TOL = 0.25
B_MONO_EPS = 1e-6


def u16_units(s):
    """UTF-16 code units of a Python string, as ints (Java char[] semantics)."""
    data = s.encode("utf-16-le")
    return [data[i] | (data[i + 1] << 8) for i in range(0, len(data), 2)]


def expected_cells():
    cells = []
    for size in SIZES:
        for name, text in V_STRINGS:
            for axis, _settings in AXES:
                cells.append({"kind": "V_paint", "label": "V_paint_%s_%s" % (axis, name),
                              "name": name, "size": size, "text": text, "asset": "ubuntu"})
                cells.append({"kind": "V_builder", "label": "V_builder_%s_%s" % (axis, name),
                              "name": name, "size": size, "text": text, "asset": "ubuntu"})
        cells.append({"kind": "F", "label": "F_fallback", "name": "F_fallback",
                      "size": size, "text": F_TEXT, "asset": "noto"})
        cells.append({"kind": "B", "label": "B_bitmap", "name": "B_bitmap",
                      "size": size, "text": B_TEXT, "asset": "bitmap"})
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
    """Return the parsed document. Fail closed on any structural problem."""
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
    if config.get("antiAlias") is not True:
        reject("CONFIG_MISMATCH", "%s config.antiAlias is %r" % (label, config.get("antiAlias")))
    if config.get("align") != "LEFT":
        reject("CONFIG_MISMATCH", "%s config.align is %r" % (label, config.get("align")))
    sizes = config.get("sizes")
    if not isinstance(sizes, list) or len(sizes) != len(SIZES):
        reject("CONFIG_MISMATCH", "%s config.sizes is not the frozen 2 sizes" % label)
    for got, want in zip(sizes, SIZES):
        if not is_number(got) or abs(float(got) - want) > 1e-3:
            reject("CONFIG_MISMATCH", "%s config.sizes entry %r != %r" % (label, got, want))
    if config.get("vStrings") != [t for _, t in V_STRINGS]:
        reject("CONFIG_MISMATCH", "%s config.vStrings is %r" % (label, config.get("vStrings")))
    if config.get("vAxisNames") != [a for a, _ in AXES]:
        reject("CONFIG_MISMATCH", "%s config.vAxisNames is %r" % (label, config.get("vAxisNames")))
    want_settings = {a: s for a, s in AXES}
    if config.get("vAxisSettings") != want_settings:
        reject("CONFIG_MISMATCH", "%s config.vAxisSettings is %r, expected %r"
               % (label, config.get("vAxisSettings"), want_settings))
    if config.get("fText") != F_TEXT:
        reject("CONFIG_MISMATCH", "%s config.fText is %r" % (label, config.get("fText")))
    if config.get("bText") != B_TEXT:
        reject("CONFIG_MISMATCH", "%s config.bText is %r" % (label, config.get("bText")))


def validate_assets(assets, label):
    if not isinstance(assets, dict):
        reject("MISSING_FIELD", "%s assets is not an object" % label)
    for key in ASSET_KEYS:
        entry = assets.get(key)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s assets.%s is not an object" % (label, key))
        sha = entry.get("sha256")
        if not isinstance(sha, str) or not sha:
            reject("MISSING_FIELD", "%s assets.%s.sha256 is not a non-empty string"
                   % (label, key))
        if sha != ASSET_SHA[key]:
            reject("FONT_HASH_MISMATCH", "%s assets.%s.sha256 %s != pinned %s"
                   % (label, key, sha, ASSET_SHA[key]))


def validate_cells(cells_raw, label):
    cells = {}
    for i, entry in enumerate(cells_raw):
        where = "%s cells[%d]" % (label, i)
        if not isinstance(entry, dict):
            reject("MISSING_FIELD", "%s is not an object" % where)
        for field in ("kind", "label", "size", "text", "utf16", "asset", "fontSha256",
                      "resolution", "count", "widths", "sum", "measureText", "bounds",
                      "exception"):
            if field not in entry:
                reject("MISSING_FIELD", "%s has no %r" % (where, field))
        kind = entry["kind"]
        if kind not in ("V_paint", "V_builder", "F", "B"):
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

        asset = entry["asset"]
        if asset not in ASSET_SHA:
            reject("INVALID_ASSET", "%s asset is %r" % (where, asset))
        font_sha = entry["fontSha256"]
        if not isinstance(font_sha, str) or not font_sha:
            reject("MISSING_FIELD", "%s fontSha256 is not a non-empty string" % where)
        if font_sha != ASSET_SHA[asset]:
            reject("FONT_HASH_MISMATCH", "%s fontSha256 %s != pinned %s for asset %s"
                   % (where, font_sha, ASSET_SHA[asset], asset))

        resolution = entry["resolution"]
        if resolution not in RESOLUTIONS:
            reject("INVALID_RESOLUTION", "%s resolution is %r" % (where, resolution))

        count = entry["count"]
        if not is_int(count) or count < 0:
            reject("INVALID_NUMBER", "%s count is not a non-negative int: %r" % (where, count))
        widths = entry["widths"]
        if not isinstance(widths, list):
            reject("MISSING_FIELD", "%s widths is not a list" % where)

        if resolution == "exception":
            if not isinstance(entry["exception"], str) or not entry["exception"]:
                reject("MISSING_FIELD",
                       "%s resolution is exception but exception is %r"
                       % (where, entry["exception"]))
            if count != 0 or widths:
                reject("INVALID_COUNT",
                       "%s exception cell must have count=0 and no widths (count=%d, widths=%d)"
                       % (where, count, len(widths)))
        else:
            if entry["exception"] is not None:
                reject("INVALID_RESOLUTION",
                       "%s resolution %r must have a null exception, got %r"
                       % (where, resolution, entry["exception"]))
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
                    reject("MISSING_FIELD",
                           "%s widths[%d].hex is not a non-empty string" % (where, j))
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


def compare_value_cell(a, c, where):
    internal_sum_ok(a, "android " + where)
    internal_sum_ok(c, "candidate " + where)

    aw = [float(w["dec"]) for w in a["widths"]]
    cw = [float(w["dec"]) for w in c["widths"]]
    compared = 0
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


def compare_cells(ref, cand):
    compared = 0
    tally = {"value": 0, "exception": 0, "axis_ignored": 0}
    b_cand = []
    b_android = []
    for key in sorted(EXPECTED_KEYS):
        kind, label, size, text = key
        a = ref["cells"][key]
        c = cand["cells"][key]
        where = "(%s,%s,%.1f,%r)" % (kind, label, size, text)
        ra = a["resolution"]
        rc = c["resolution"]
        if ra != rc:
            reject("CAPABILITY_GAP",
                   "%s resolution android=%s candidate=%s (android exception=%s / candidate "
                   "exception=%s)" % (where, ra, rc, a.get("exception"), c.get("exception")))
        tally[ra] += 1
        if ra != "value":
            if kind == "B":
                # Corrected B contract: an unresolved/exception bitmap cell on
                # either side is a failure, not an equivalent capability outcome.
                reject("B_NO_VALUE",
                       "%s B cell resolution android=%s candidate=%s (both sides must be "
                       "'value' with no exception)" % (where, ra, rc))
            # both "exception" (e.g. both reject the bitmap face) or both
            # "axis_ignored" are equivalent capability outcomes: accepted.
            continue
        if kind == "B":
            check_b_cell(c, where)
            b_cand.append((size, c))
            b_android.append((size, a))
        else:
            compared += compare_value_cell(a, c, where)
    if b_cand:
        compare_b_scaling(b_cand)
    return compared, tally, b_android


def b_ink(cell):
    """(width, height) of the candidate's getTextBounds ink box."""
    b = cell["bounds"]
    return (b["right"] - b["left"], b["bottom"] - b["top"])


def check_b_cell(cell, where):
    """Corrected B invariant: a usable face must produce a non-empty ink box."""
    ink_w, ink_h = b_ink(cell)
    if ink_w <= 0 or ink_h <= 0:
        reject("B_EMPTY_INK",
               "%s candidate ink box is empty: width=%d height=%d bounds=%s "
               "(uniform/zero output is rejected)" % (where, ink_w, ink_h, cell["bounds"]))


def b_adv_sum(cell):
    """Sum of the candidate's per-glyph advances (decimal)."""
    return sum(float(w["dec"]) for w in cell["widths"])


def compare_b_scaling(cells):
    """Corrected B invariant: the candidate's own AGGREGATES must scale with
    the requested size, and no per-glyph advance may decrease with size.

    ``cells`` is a list of (size, candidate_cell). Scaling is judged on three
    aggregates -- the sum of the advances, the ink width and the ink height --
    each of which must have a ratio to the small size inside the size-ratio
    band (``B_SCALE_REL_TOL``). Per glyph only monotonic non-decrease is
    required (``a33 >= a16``): hinting quantizes small advances, so a correct
    scalable face legitimately grows a 3 px advance to 9 px (ratio 3.0) for a
    2.049x size ratio (Android's own frozen FONT-006 DejaVu pin), which a
    per-glyph ratio band would wrongly reject."""
    if len(cells) != len(SIZES):
        reject("B_NO_SCALING", "candidate B evidence does not cover both frozen sizes")
    by_size = {float(size): cell for size, cell in cells}
    if not all(float(s) in by_size for s in SIZES):
        reject("B_NO_SCALING", "candidate B evidence does not cover both frozen sizes")
    cs = by_size[float(SIZES[0])]
    cl = by_size[float(SIZES[1])]
    size_ratio = float(SIZES[1]) / float(SIZES[0])
    tol = B_SCALE_REL_TOL * size_ratio

    # Per glyph: monotonic non-decrease only (NOT a ratio band).
    for j, (ws, wl) in enumerate(zip(cs["widths"], cl["widths"])):
        a16 = float(ws["dec"])
        a33 = float(wl["dec"])
        if a33 < a16 - B_MONO_EPS:
            reject("B_NO_SCALING",
                   "(B,B_bitmap) advance[%d] decreases with size: %.9g@%s -> %.9g@%s "
                   "(per-glyph advances must be monotonically non-decreasing; a ratio band "
                   "is inadmissible because hinting quantizes small advances)"
                   % (j, a16, SIZES[0], a33, SIZES[1]))

    # Aggregates: advance sum, ink width, ink height.
    s16 = b_adv_sum(cs)
    s33 = b_adv_sum(cl)
    if s16 <= 0.0:
        reject("B_NO_SCALING",
               "(B,B_bitmap) advance sum at %s is %.9g (cannot scale)" % (SIZES[0], s16))
    iw16, ih16 = b_ink(cs)
    iw33, ih33 = b_ink(cl)
    if iw16 <= 0 or ih16 <= 0:
        reject("B_NO_SCALING", "(B,B_bitmap) candidate ink box at %s is empty" % SIZES[0])
    factors = [
        ("advance sum", s33 / s16),
        ("ink width", float(iw33) / float(iw16)),
        ("ink height", float(ih33) / float(ih16)),
    ]
    for name, factor in factors:
        if abs(factor - size_ratio) > tol:
            reject("B_NO_SCALING",
                   "(B,B_bitmap) %s ratio %.4f != size ratio %.4f (|diff|>%.4f); candidate "
                   "does not scale with the requested size (uniform advances / size-"
                   "independent output is rejected)"
                   % (name, factor, size_ratio, tol))


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

    compared, tally, b_android = compare_cells(ref, cand)
    info = ""
    if b_android:
        parts = []
        for size, cell in b_android:
            advances = [float(w["dec"]) for w in cell["widths"]]
            ink_w, ink_h = b_ink(cell)
            parts.append("size=%s advances=%s ink=(%d,%d) bounds=%s"
                         % (size, advances, ink_w, ink_h, cell["bounds"]))
        info = (" | B(bitmap) informational NOT_COMPARABLE (Android resolves a different "
                "font; NOT a pass/fail threshold): " + "; ".join(parts))
    print("OK font-gb match: %d cells (%d value / %d exception / %d axis_ignored), "
          "%d advance values compared (int exact, fractional <=%s; measureText <=%s; "
          "bounds exact); B judged on structural invariants (non-empty ink, aggregate "
          "size scaling within %.0f%%, per-glyph monotonic non-decrease), "
          "android-run=%s candidate-run=%s%s"
          % (len(ref["cells"]), tally["value"], tally["exception"], tally["axis_ignored"],
             compared, TOL_FRAC, TOL_MEASURE, B_SCALE_REL_TOL * 100.0,
             ref["run_id"], cand["run_id"], info))
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
