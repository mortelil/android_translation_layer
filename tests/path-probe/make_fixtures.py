#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""make_fixtures.py - build the comparator self-test fixture matrix.

Usage:
    make_fixtures.py <reference.json> <out_dir>

``reference.json`` is the frozen Android reference observation
(agent-work/APP-COMPAT/G-PATH/attempt-1/android/reference.json). The generator
derives one "valid" ATL-shaped fixture (identical observations, run_kind=atl)
plus deliberately mutated fixtures; cases.json records the intended exit and
REJECT code for each.
"""
import copy
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare  # noqa: E402  (shared tolerance constants)


def relabel(doc):
    d = copy.deepcopy(doc)
    d["run_kind"] = "atl"
    d["run_id"] = "path-probe-selftest-atl"
    d["build"] = {"fingerprint": "", "manufacturer": "", "brand": "",
                  "model": "", "sdk_int": 0}
    return d


def first(doc):
    return doc["cases"][0]


def flip_mask_bits(mask, n):
    chars = list(mask)
    flipped = 0
    for i in range(len(chars)):
        if flipped >= n:
            break
        v = int(chars[i], 16) ^ 1
        chars[i] = format(v, "x")
        flipped += 4
    return "".join(chars)


def main():
    ref_path, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    with open(ref_path, encoding="utf-8") as fh:
        ref = json.load(fh)
    if ref.get("run_kind") != "android":
        print("reference is not an android-run document", file=sys.stderr)
        return 2

    fixtures = {}   # name -> (content_or_None, source_path_or_None)
    cases = []

    def add(name, doc, exit_code, code, expect, env=None, android="reference.json",
            note=None):
        if doc is not None:
            fixtures[name + ".json"] = json.dumps(doc, indent=1, sort_keys=True)
        cases.append({"name": name, "android": android,
                      "atl": name + ".json", "exit": exit_code,
                      "code": code, "expect": expect, "env": env, "note": note})

    valid = relabel(ref)
    add("valid", valid, 0, None, "ok")

    reordered = relabel(ref)
    reordered["cases"] = list(reversed(reordered["cases"]))
    add("reordered", reordered, 0, None, "ok")

    within = relabel(ref)
    within["cases"][0]["receiver"]["bounds"][0] = round(
        within["cases"][0]["receiver"]["bounds"][0] + 0.5, 3)
    within["cases"][0]["receiver"]["region"]["mask"] = flip_mask_bits(
        within["cases"][0]["receiver"]["region"]["mask"], 4)
    add("within-tolerance", within, 0, None, "ok")

    # empty / truncated
    fixtures["empty.json"] = ""
    cases.append({"name": "empty", "android": "reference.json", "atl": "empty.json",
                  "exit": 1, "code": "EVIDENCE_INVALID", "expect": "reject", "env": None})
    fixtures["truncated.json"] = json.dumps(valid, sort_keys=True)[:400]
    cases.append({"name": "truncated", "android": "reference.json", "atl": "truncated.json",
                  "exit": 1, "code": "EVIDENCE_INVALID", "expect": "reject", "env": None})

    # wrong run kind: the android document is handed in as the atl side
    cases.append({"name": "wrong-run-kind", "android": "reference.json",
                  "atl": "reference.json", "exit": 1, "code": "WRONG_RUN_KIND",
                  "expect": "reject", "env": None})

    d = relabel(ref); d["task_id"] = "PATH-OP-OTHER"
    add("task-mismatch", d, 1, "TASK_MISMATCH", "reject")

    d = relabel(ref); d["probe"] = "other-probe"
    add("wrong-owner", d, 1, "WRONG_OWNER", "reject")

    d = relabel(ref); d["cases"] = d["cases"][1:]
    add("missing-case", d, 1, "CASE_MISSING", "reject")

    d = relabel(ref); d["cases"].append(copy.deepcopy(d["cases"][0]))
    add("duplicate-case", d, 1, "CASE_DUPLICATE", "reject")

    d = relabel(ref)
    extra = copy.deepcopy(d["cases"][0]); extra["id"] = "Z-extra"
    d["cases"].append(extra)
    add("extra-case", d, 1, "CASE_EXTRA", "reject")

    d = relabel(ref); first(d)["ret"] = not first(d)["ret"]
    add("ret-mismatch", d, 1, "RET_MISMATCH", "reject")

    d = relabel(ref); first(d)["receiver"]["is_empty"] = not first(d)["receiver"]["is_empty"]
    add("empty-mismatch", d, 1, "EMPTY_MISMATCH", "reject")

    d = relabel(ref); first(d)["receiver"]["fill_type"] = "WINDING"
    add("filltype-mismatch", d, 1, "FILLTYPE_MISMATCH", "reject")

    d = relabel(ref); first(d)["receiver"]["bounds"][0] = round(
        first(d)["receiver"]["bounds"][0] + 1.5, 3)
    add("bounds-over-tolerance", d, 1, "BOUNDS_MISMATCH", "reject")

    d = relabel(ref)
    r = first(d)["receiver"]["region"]
    r["mask"] = flip_mask_bits(r["mask"], 400)
    add("mask-mismatch", d, 1, "REGION_MISMATCH", "reject")

    d = relabel(ref); first(d)["receiver"]["region"] = {"status": "error", "error": "stub"}
    add("region-missing", d, 1, "REGION_MISSING", "reject")

    # --- channel-aware REGION exclusion (missing framework native) ----------
    # ATL reports Region.setPath's JNI native as missing:
    #   UnsatisfiedLinkError naming android.graphics.Region.nativeSetRect /
    #   "No implementation found for ...". That is an explicitly-named missing
    # framework native, so the region membership channel is NOT_PROVEN; every
    # other hard channel is still compared.
    native_marker = ("java.lang.UnsatisfiedLinkError: No implementation found "
                     "for boolean android.graphics.Region.nativeSetRect(int, int, "
                     "int, int, int) (tried Java_android_graphics_Region_"
                     "nativeSetRect and Java_android_graphics_Region_"
                     "nativeSetRect__IIIII)")
    d = relabel(ref)
    for c in d["cases"]:
        c["receiver"]["region"] = {"status": "error", "error": native_marker}
    add("region-missing-native-marker", d, 0, None, "ok",
        note="NOT_PROVEN channel=region_mask")

    # A generic UnsatisfiedLinkError that does NOT name a framework native
    # symbol (a dlopen/library failure) stays a hard REJECT.
    d = relabel(ref)
    first(d)["receiver"]["region"] = {
        "status": "error",
        "error": "java.lang.UnsatisfiedLinkError: dlopen failed: library "
                 "\"libfoo.so\" not found"}
    add("region-generic-unsatisfiedlink", d, 1, "REGION_MISSING", "reject")

    # ...as does an UnsatisfiedLinkError whose text merely reads like a generic
    # unsupported note without naming any native method/symbol.
    d = relabel(ref)
    first(d)["receiver"]["region"] = {
        "status": "error",
        "error": "java.lang.UnsatisfiedLinkError: Region not implemented"}
    add("region-unsatisfiedlink-no-symbol", d, 1, "REGION_MISSING", "reject")

    # An Android-side missing-native is never excused (exclusion is ATL-only).
    dref = copy.deepcopy(ref)
    dref["cases"][0]["receiver"]["region"] = {"status": "error",
                                              "error": native_marker}
    fixtures["reference-region-native-error.json"] = json.dumps(
        dref, indent=1, sort_keys=True)
    add("region-android-native-error", relabel(ref), 1, "REGION_MISSING",
        "reject", android="reference-region-native-error.json")

    d = relabel(ref)
    a = first(d)["receiver"]["approx"]
    a["raw"] = a["raw"][: max(3, len(a["raw"]) // 2)]
    add("approx-points-mismatch", d, 1, "APPROX_POINTS_MISMATCH", "reject")

    d = relabel(ref); first(d)["receiver"]["approx"]["contour_count"] += 1
    add("contour-mismatch", d, 1, "APPROX_CONTOUR_MISMATCH", "reject")

    d = relabel(ref); first(d)["exception"] = "java.lang.RuntimeException: boom"
    add("exception-mismatch", d, 1, "EXCEPTION_MISMATCH", "reject")

    # --- exception-expected schema (null operands) --------------------------
    # An exception-expected case compares ONLY the boolean return, the exception
    # class and the operand roles: post-exception geometry is skipped, so a
    # deliberately different post-call isEmpty/bounds on the ATL side still
    # PASSES (the receiver is untouched by the throwing call, and a receiver's
    # post-throw geometry is not part of the Path.op contract).
    def hcase(doc, cid):
        return next(c for c in doc["cases"] if c["id"] == cid)

    d = relabel(ref)
    hc = hcase(d, "H-null-p1-union")
    hc["receiver"]["is_empty"] = not hc["receiver"]["is_empty"]
    hc["receiver"]["bounds"] = [99.0, 99.0, 99.0, 99.0]
    hc["receiver"]["fill_type"] = "EVEN_ODD"
    add("exception-geometry-skipped", d, 0, None, "ok")

    # The exception_expected flag is part of the schema: a mismatch is a REJECT.
    d = relabel(ref)
    hcase(d, "H-null-p1-union")["exception_expected"] = False
    add("exception-flag-mismatch", d, 1, "EXCEPTION_EXPECTATION_MISMATCH", "reject")

    # The exception CLASS is still required to match exactly...
    d = relabel(ref)
    hcase(d, "H-null-p1-union")["exception"] = "java.lang.IllegalArgumentException: nope"
    add("exception-class-mismatch", d, 1, "EXCEPTION_MISMATCH", "reject")

    # ...so an ATL side that does NOT throw (null exception) is a REJECT, even
    # though its post-call geometry is ignored.
    d = relabel(ref)
    hcase(d, "H-null-p2-union")["exception"] = None
    add("exception-not-thrown", d, 1, "EXCEPTION_MISMATCH", "reject")

    d = relabel(ref)
    c = first(d)
    c["p1_role"], c["p2_role"] = c["p2_role"], c["p1_role"]
    add("role-mismatch", d, 1, "ROLE_MISMATCH", "reject")

    d = relabel(ref); first(d)["p2_changed"] = True
    add("operand-mutated", d, 1, "OPERAND_MUTATED", "reject")

    d = relabel(ref); first(d)["receiver"]["bounds"][0] = "abc"
    add("invalid-number", d, 1, "INVALID_NUMBER", "reject")

    d = relabel(ref); first(d)["receiver"]["bounds"][1] = "NaN"
    add("nan-number", d, 1, "INVALID_NUMBER", "reject")

    # --- channel-aware approximate exclusion (NOT_PROVEN) -------------------
    # ATL reports approximate() as explicitly unsupported (NoSuchMethodError):
    # the channel is excluded from the pass/fail decision but the verdict notes
    # name it. The hard channels remain intact, so the run is accepted.
    marker = ("java.lang.NoSuchMethodError: No virtual method approximate(F)[F "
              "in class Landroid/graphics/Path; or its super classes")
    d = relabel(ref)
    for c in d["cases"]:
        c["receiver"]["approx"] = {"status": "error", "error": marker}
    add("approx-unsupported-marker", d, 0, None, "ok",
        note="NOT_PROVEN channel=approximate")

    # A channel that is missing WITHOUT an explicit unsupported marker is a
    # hard REJECT (fail-closed): the key is simply absent.
    d = relabel(ref)
    del first(d)["receiver"]["approx"]
    add("approx-missing-no-marker", d, 1, "APPROX_MISSING", "reject")

    # ...as is a channel that errored without an unsupported signature.
    d = relabel(ref)
    first(d)["receiver"]["approx"] = {"status": "error",
                                      "error": "java.lang.RuntimeException: probe bug"}
    add("approx-error-no-marker", d, 1, "APPROX_MISSING", "reject")

    # The Android reference itself must never be excused: an error on the
    # android side is a hard REJECT even with an unsupported-looking text.
    dref = copy.deepcopy(ref)
    dref["cases"][0]["receiver"]["approx"] = {"status": "error",
                                              "error": "java.lang.NoSuchMethodError: bogus"}
    fixtures["reference-android-error.json"] = json.dumps(dref, indent=1, sort_keys=True)
    add("approx-android-error", relabel(ref), 1, "APPROX_MISSING", "reject",
        android="reference-android-error.json")

    # internal-error propagation (env hook in compare.py)
    cases.append({"name": "internal-error", "android": "reference.json",
                  "atl": "valid.json", "exit": 2, "code": None,
                  "expect": "internal", "env": "PATHPROBE_COMPARE_CRASH=1"})

    for name, content in fixtures.items():
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as fh:
            fh.write(content)
    with open(os.path.join(out_dir, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=1)
    print("wrote %d fixtures / %d cases" % (len(fixtures), len(cases)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
