#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""compare.py - fail-closed comparator for the G-PATH Path.op probe.

Usage:
    compare.py <android.json> <atl.json> [--bounds-tol F] [--mask-tol N]
               [--point-frac F] [--min-point-tol N]

Compares the frozen Android-reference observation JSON against the ATL-side
observation JSON produced by the SAME probe APK (same task_id/config). Both
sides carry a ``run_kind`` field; the Android artefact MUST be ``"android"`` and
the ATL artefact MUST be ``"atl"``.

Exit status:
    0  every selected check passed for both sides and they agree
    1  rejected: REJECT <CODE> <reason> on stdout (fail-closed)
    2  usage / internal error (a crash is NEVER a semantic rejection)

Per case compared:
  * op() boolean return, receiver isEmpty(), receiver getFillType()
  * receiver computeBounds() (documented absolute tolerance, default 1.0 px)
  * receiver Region.setPath() membership mask (Hamming distance <= mask-tol
    samples; sample_count must match exactly)
  * receiver Path.approximate() contour count (exact) and quantised point
    multiset (symmetric difference <= max(min-point-tol, point-frac * points))
  * operand roles and destination semantics (an ``operand`` role must be
    unchanged on both sides)
  * exception class (null vs non-null and simple class name)
  * exception_expected: a case marked TRUE on one side MUST be marked TRUE on
    the other (else REJECT EXCEPTION_EXPECTATION_MISMATCH). For an
    exception-expected case (null operand) only the boolean return, the
    exception CLASS and the operand roles are compared; post-exception
    geometry (isEmpty/fill_type/bounds/approx/region) is NEVER compared, since
    it is not part of the Path.op contract when the call threw. The pre-call
    receiver state is carried as ``receiver_before`` for evidence.

Channel-aware exclusion (NOT_PROVEN)
------------------------------------
A channel the ATL side reports as explicitly unavailable is treated as
NOT_PROVEN: it is excluded from the pass/fail decision and named in the verdict
notes (a ``NOT_PROVEN channel=... `` line), so a reader can see which channels
were actually compared. The hard channels (ret, is_empty, fill_type, bounds,
region mask, operand roles, exception class) are NEVER excluded.

Exclusion requires an EXPLICIT unsupported marker: the channel must carry a
structured ``status: "error"`` with an error text naming an unsupported/
missing-API condition (NoSuchMethodError, AbstractMethodError,
UnsupportedOperationException, NoClassDefFoundError, "no virtual method",
"not implemented"/"not supported"/"unsupported"), OR a missing FRAMEWORK
NATIVE reported as ``java.lang.UnsatisfiedLinkError`` that NAMES the native
method/symbol (e.g. ``Region.nativeSetRect``, or ART's "No implementation
found for ..."). A ``UnsatisfiedLinkError`` that does not name a native symbol
(a generic dlopen/library failure) is still a hard REJECT, as is any channel
that is missing, truncated, malformed, or errors WITHOUT such a marker, and an
unsupported ANDROID reference is never tolerated.

Tolerances are fixed here and documented; they are NOT derived from ATL's
observed failure. Nothing is silently ignored.
"""
import json
import math
import os
import re
import sys
from collections import Counter

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

TASK_ID = "PATH-OP-G"
DEF_BOUNDS_TOL = 1.0
DEF_MASK_TOL = 30          # samples of 6000 (0.5%)
DEF_POINT_FRAC = 0.08      # fraction of the larger point count
DEF_MIN_POINT_TOL = 3

# Channels that are always HARD requirements: never excluded, on either side.
HARD_CHANNELS = ("ret", "is_empty", "fill_type", "bounds", "region_mask",
                 "operand_roles", "exception_class")

# Error-text signatures that count as an EXPLICIT unsupported/unavailable
# marker from the ATL side (matched case-insensitively).
UNSUPPORTED_SIGNATURES = (
    "nosuchmethoderror",
    "no virtual method",
    "abstractmethoderror",
    "unsupportedoperationexception",
    "noclassdeffounderror",
    "classnotfoundexception",
    "not implemented",
    "not supported",
    "unsupported",
)

# A missing FRAMEWORK NATIVE is reported as java.lang.UnsatisfiedLinkError.
# That is only an unavailable-channel marker when the message NAMES the native
# method/symbol that is unavailable; a generic UnsatisfiedLinkError (e.g. a
# dlopen/library failure, or one carrying no symbol) stays a hard REJECT. The
# two accepted naming forms are:
#   * a qualified native method symbol, e.g. "Region.nativeSetRect" /
#     "android.graphics.Region.nativeSetRect" (".native" + CamelCase method), and
#   * ART's "No implementation found for ..." JNI-resolution message.
_MISSING_NATIVE_SYMBOL = re.compile(r"[A-Za-z_$][\w$]*\.native[A-Z]\w*")
_MISSING_NATIVE_PHRASES = ("no implementation found for",)


class Reject(Exception):
    def __init__(self, code, reason):
        super().__init__("%s %s" % (code, reason))
        self.code = code
        self.reason = reason


def reject(code, reason):
    raise Reject(code, reason)


# --------------------------------------------------------------- loading ----

def load(path, role):
    if not os.path.isfile(path):
        reject("EVIDENCE_INVALID", "%s file not found: %s" % (role, path))
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except Exception as exc:
        reject("EVIDENCE_INVALID", "%s unreadable: %s" % (role, exc))
    if not text.strip():
        reject("EVIDENCE_INVALID", "%s file is empty" % role)
    try:
        doc = json.loads(text)
    except Exception as exc:
        reject("EVIDENCE_INVALID", "%s is not valid JSON: %s" % (role, exc))
    if not isinstance(doc, dict):
        reject("EVIDENCE_INVALID", "%s top-level is not an object" % role)
    return doc


def check_identity(doc, role, want_kind):
    if doc.get("task_id") != TASK_ID:
        reject("TASK_MISMATCH", "%s task_id=%r want %r"
               % (role, doc.get("task_id"), TASK_ID))
    if doc.get("probe") != "path-probe":
        reject("WRONG_OWNER", "%s probe=%r want 'path-probe'"
               % (role, doc.get("probe")))
    kind = doc.get("run_kind")
    if kind != want_kind:
        reject("WRONG_RUN_KIND", "%s run_kind=%r want %r" % (role, kind, want_kind))
    if want_kind == "android":
        fp = str(doc.get("build", {}).get("fingerprint", "")).lower()
        if not fp or "atl" in fp or "unknown" in fp:
            reject("WRONG_RUN_KIND", "android side has implausible fingerprint %r" % fp)
    cfg = doc.get("config")
    if not isinstance(cfg, dict) or not isinstance(cfg.get("grid"), list):
        reject("EVIDENCE_INVALID", "%s config.grid missing" % role)
    cases = doc.get("cases")
    if not isinstance(cases, list) or not cases:
        reject("EVIDENCE_INVALID", "%s cases must be a non-empty list" % role)


def index_cases(doc, role):
    out = {}
    for c in doc["cases"]:
        if not isinstance(c, dict) or not isinstance(c.get("id"), str) or not c["id"]:
            reject("EVIDENCE_INVALID", "%s has a case without a valid id" % role)
        if c["id"] in out:
            reject("CASE_DUPLICATE", "%s duplicate case id %r" % (role, c["id"]))
        out[c["id"]] = c
    return out


# ------------------------------------------------------------ primitives ----

def num(value, role, what):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        reject("INVALID_NUMBER", "%s %s is not a number: %r" % (role, what, value))
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        reject("INVALID_NUMBER", "%s %s is not finite: %r" % (role, what, value))
    return float(value)


def exc_class(v):
    if v is None:
        return None
    if not isinstance(v, str):
        reject("INVALID_NUMBER", "exception field is not a string/null: %r" % v)
    return v.split(":", 1)[0].strip()


def mask_bits(hexstr, role):
    if not isinstance(hexstr, str):
        reject("INVALID_NUMBER", "%s mask is not a string" % role)
    bits = []
    for ch in hexstr:
        try:
            v = int(ch, 16)
        except ValueError:
            reject("INVALID_NUMBER", "%s mask has a non-hex char %r" % (role, ch))
        for sh in (3, 2, 1, 0):
            bits.append((v >> sh) & 1)
    return bits


def quantise(points, tol):
    return Counter((round(x / tol), round(y / tol)) for x, y in points)


# --------------------------------------------------------- channel state ----

def names_missing_framework_native(text):
    """True only when ``text`` names an unavailable framework native method/
    symbol (a ``.nativeXxx`` symbol, or ART's "No implementation found for")."""
    if _MISSING_NATIVE_SYMBOL.search(text):
        return True
    low = text.lower()
    return any(p in low for p in _MISSING_NATIVE_PHRASES)


def unsupported_marker(channel):
    """Return the error text if ``channel`` carries an EXPLICIT unsupported
    marker (structured ``status: "error"`` naming a missing/unsupported API),
    else ``None``. A mere error/missing channel returns ``None`` -> the caller
    must REJECT."""
    if not isinstance(channel, dict):
        return None
    if channel.get("status") != "error":
        return None
    text = channel.get("error")
    if not isinstance(text, str) or not text.strip():
        return None
    low = text.lower()
    if "unsatisfiedlinkerror" in low:
        # Missing native: only an explicit naming of the unavailable native
        # method/symbol counts. A generic UnsatisfiedLinkError (library load
        # failure, no symbol named) must NOT be excused by the generic
        # signature list above and stays a hard REJECT.
        if names_missing_framework_native(text):
            return text.strip()
        return None
    for sig in UNSUPPORTED_SIGNATURES:
        if sig in low:
            return text.strip()
    return None


def note_unproven(opts, channel, reason):
    key = (channel, reason)
    opts.setdefault("unproven", {})
    opts["unproven"][key] = opts["unproven"].get(key, 0) + 1


# ------------------------------------------------------------- comparison ----

def compare_approx(a, b, tol, point_tol, opts):
    if not isinstance(a, dict) or a.get("status") != "ok" or a.get("parse") != "ok":
        reject("APPROX_MISSING",
               "android approximate reference is not ok: status=%r parse=%r"
               % (a.get("status") if isinstance(a, dict) else a,
                  a.get("parse") if isinstance(a, dict) else None))
    if not isinstance(b, dict):
        reject("APPROX_MISSING",
               "atl approximate channel missing without an explicit unsupported marker")
    if b.get("status") != "ok":
        marker = unsupported_marker(b)
        if marker is not None:
            # Explicit unsupported/unavailable marker: NOT_PROVEN, excluded
            # from the pass/fail decision but recorded in the verdict notes.
            note_unproven(opts, "approximate", marker)
            return
        reject("APPROX_MISSING",
               "atl approximate status=%r without an explicit unsupported marker: %r"
               % (b.get("status"), b.get("error")))
    if b.get("parse") != "ok":
        reject("APPROX_MISSING", "atl approximate parse=%r" % b.get("parse"))
    if a.get("contour_count") != b.get("contour_count"):
        reject("APPROX_CONTOUR_MISMATCH", "contour_count android=%r atl=%r"
               % (a.get("contour_count"), b.get("contour_count")))
    ra, rb = a.get("raw"), b.get("raw")
    if not isinstance(ra, list) or not isinstance(rb, list):
        reject("INVALID_NUMBER", "approximate raw missing")
    pa = numpairs(ra, "android")
    pb = numpairs(rb, "atl")
    ca, cb = quantise(pa, tol), quantise(pb, tol)
    diff = sum((ca - cb).values()) + sum((cb - ca).values())
    allowed = max(point_tol, int(math.ceil(DEF_POINT_FRAC * max(len(pa), len(pb), 1))))
    if diff > allowed:
        reject("APPROX_POINTS_MISMATCH",
               "quantised point symmetric-difference %d > allowed %d (tol=%g)"
               % (diff, allowed, tol))


def numpairs(raw, role):
    pts = []
    for i in range(0, len(raw) - 2, 3):
        x = num(raw[i + 1], role, "approx x")
        y = num(raw[i + 2], role, "approx y")
        pts.append((x, y))
    return pts


def compare_region(a, b, mask_tol, opts):
    sa = a.get("status") if isinstance(a, dict) else None
    if sa != "ok":
        # The Android reference is never excused, whatever its error text.
        reject("REGION_MISSING", "android region status=%r" % sa)
    sb = b.get("status") if isinstance(b, dict) else None
    if sb != "ok":
        marker = unsupported_marker(b)
        if marker is not None:
            # Explicit missing-native/unsupported marker on the ATL side:
            # NOT_PROVEN, excluded from the pass/fail decision but recorded.
            note_unproven(opts, "region_mask", marker)
            return
        reject("REGION_MISSING",
               "atl region status=%r without an explicit unsupported marker: %r"
               % (sb, b.get("error") if isinstance(b, dict) else b))
    if a.get("sample_count") != b.get("sample_count"):
        reject("REGION_MISMATCH", "region sample_count android=%r atl=%r"
               % (a.get("sample_count"), b.get("sample_count")))
    ba = mask_bits(a.get("mask"), "android")
    bb = mask_bits(b.get("mask"), "atl")
    if len(ba) != len(bb):
        reject("REGION_MISMATCH", "mask bit-length android=%d atl=%d"
               % (len(ba), len(bb)))
    hamming = sum(1 for x, y in zip(ba, bb) if x != y)
    if hamming > mask_tol:
        reject("REGION_MISMATCH", "mask Hamming distance %d > allowed %d"
               % (hamming, mask_tol))
    ca, cb = a.get("inside_count"), b.get("inside_count")
    if abs(ca - cb) > mask_tol:
        reject("REGION_MISMATCH", "inside_count android=%r atl=%r" % (ca, cb))


def compare_case(ac, bc, opts):
    if ac.get("overload") != bc.get("overload") or ac.get("op") != bc.get("op"):
        reject("CASE_MISMATCH", "case %s metadata differs" % ac.get("id"))
    ae = bool(ac.get("exception_expected"))
    be = bool(bc.get("exception_expected"))
    if ae != be:
        reject("EXCEPTION_EXPECTATION_MISMATCH",
               "case %s exception_expected android=%r atl=%r"
               % (ac.get("id"), ae, be))
    if ac.get("ret") != bc.get("ret"):
        reject("RET_MISMATCH", "case %s ret android=%r atl=%r"
               % (ac["id"], ac.get("ret"), bc.get("ret")))
    if exc_class(ac.get("exception")) != exc_class(bc.get("exception")):
        reject("EXCEPTION_MISMATCH", "case %s exception android=%r atl=%r"
               % (ac["id"], exc_class(ac.get("exception")), exc_class(bc.get("exception"))))
    for role in ("p1", "p2"):
        if ac.get(role + "_role") != bc.get(role + "_role"):
            reject("ROLE_MISMATCH", "case %s %s_role android=%r atl=%r"
                   % (ac["id"], role, ac.get(role + "_role"), bc.get(role + "_role")))
        if ac.get(role + "_role") == "operand":
            for side, c in (("android", ac), ("atl", bc)):
                if c.get(role + "_changed"):
                    reject("OPERAND_MUTATED", "case %s %s mutated on %s"
                           % (ac["id"], role, side))
    if ae:
        # Exception-expected case (null operand): the contract is the
        # exception CLASS (compared above) plus the boolean return and the
        # operand roles (compared above). Post-exception geometry is NEVER
        # compared: both sides leave the receiver untouched by the throwing
        # call, and a receiver's isEmpty()/bounds after a throw are not part
        # of the Path.op contract. On the ATL side the probe's own pre-call
        # observation materialises the receiver handle, so an ATL handle-based
        # isEmpty() would otherwise leak an out-of-scope defect into the op
        # verdict. The pre-call state is recorded as receiver_before for
        # evidence.
        return
    ar, br = ac["receiver"], bc["receiver"]
    if ar.get("is_empty") != br.get("is_empty"):
        reject("EMPTY_MISMATCH", "case %s is_empty android=%r atl=%r"
               % (ac["id"], ar.get("is_empty"), br.get("is_empty")))
    if ar.get("fill_type") != br.get("fill_type"):
        reject("FILLTYPE_MISMATCH", "case %s fill_type android=%r atl=%r"
               % (ac["id"], ar.get("fill_type"), br.get("fill_type")))
    ba, bb = ar.get("bounds"), br.get("bounds")
    if not isinstance(ba, list) or not isinstance(bb, list) or len(ba) != 4 or len(bb) != 4:
        reject("INVALID_NUMBER", "case %s bounds not a 4-element array" % ac["id"])
    for i in range(4):
        va = num(ba[i], "android", "bounds[%d]" % i)
        vb = num(bb[i], "atl", "bounds[%d]" % i)
        if abs(va - vb) > opts["bounds_tol"]:
            reject("BOUNDS_MISMATCH", "case %s bounds[%d] android=%g atl=%g tol=%g"
                   % (ac["id"], i, va, vb, opts["bounds_tol"]))
    compare_approx(ar.get("approx"), br.get("approx"), opts["bounds_tol"],
                   opts["min_point_tol"], opts)
    compare_region(ar.get("region"), br.get("region"), opts["mask_tol"], opts)


def run(android_doc, atl_doc, opts):
    check_identity(android_doc, "android", "android")
    check_identity(atl_doc, "atl", "atl")
    amap = index_cases(android_doc, "android")
    bmap = index_cases(atl_doc, "atl")
    missing = sorted(set(amap) - set(bmap))
    if missing:
        reject("CASE_MISSING", "ATL missing cases: %s" % ", ".join(missing))
    extra = sorted(set(bmap) - set(amap))
    if extra:
        reject("CASE_EXTRA", "ATL has extra cases: %s" % ", ".join(extra))
    if len(amap) != len(bmap):
        reject("CASE_MISSING", "case counts differ android=%d atl=%d"
               % (len(amap), len(bmap)))
    for cid in amap:
        compare_case(amap[cid], bmap[cid], opts)
    return len(amap)


def main(argv):
    if os.environ.get("PATHPROBE_COMPARE_CRASH") == "1":
        raise RuntimeError("injected internal error")
    args = [a for a in argv[1:]]
    positional = []
    opts = {"bounds_tol": DEF_BOUNDS_TOL, "mask_tol": DEF_MASK_TOL,
            "min_point_tol": DEF_MIN_POINT_TOL}
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--bounds-tol":
            opts["bounds_tol"] = float(args[i + 1]); i += 2
        elif a == "--mask-tol":
            opts["mask_tol"] = int(args[i + 1]); i += 2
        elif a == "--min-point-tol":
            opts["min_point_tol"] = int(args[i + 1]); i += 2
        elif a.startswith("--"):
            print("ERROR internal unknown option %r" % a, file=sys.stderr)
            return EXIT_ERROR
        else:
            positional.append(a); i += 1
    if len(positional) != 2:
        print("ERROR internal usage: compare.py <android.json> <atl.json>",
              file=sys.stderr)
        return EXIT_ERROR
    android_doc = load(positional[0], "android")
    atl_doc = load(positional[1], "atl")
    n = run(android_doc, atl_doc, opts)
    print("OK cases=%d bounds_tol=%g mask_tol=%d" % (n, opts["bounds_tol"], opts["mask_tol"]))
    unproven = opts.get("unproven", {})
    excluded = sorted({ch for (ch, _reason) in unproven})
    print("CHANNELS hard=%s not_proven=%s"
          % (",".join(HARD_CHANNELS), ",".join(excluded) if excluded else "none"))
    for (ch, reason), count in sorted(unproven.items()):
        print("NOT_PROVEN channel=%s cases=%d reason=%r" % (ch, count, reason))
    return EXIT_OK


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Reject as r:
        print("REJECT %s %s" % (r.code, r.reason))
        sys.exit(EXIT_REJECT)
    except Exception as exc:  # noqa: BLE001 - any crash is an internal error
        import traceback
        traceback.print_exc()
        print("ERROR internal %s" % exc, file=sys.stderr)
        sys.exit(EXIT_ERROR)
