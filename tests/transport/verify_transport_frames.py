#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
#
# TRN-014 transport battery verifier (adapted from the Firefox
# local-service-probe verify_ipc_frames.py, b51e45f: same table-driven
# reject/accept discipline, table ported to the ATLBinderConnection record
# layout and the T01-T14 probe namespaces).
#
#   verify_transport_frames.py RESULTS_JSON [RESULTS_JSON...]
#
# Exits 0 when every expectation holds, 1 otherwise, printing one line per
# failed expectation and a summary. Namespaces in results.json:
#   - bare cmds (e.g. "valid-control")      -> T08 framing cases
#   - "probeCase:<name>"                    -> T01-T07, T09-T12 probe cases
#   - "redlink:<name>"                      -> T14 red/green baseline
import json
import sys

# T08 framing table: name -> ("REJECT"|"ACCEPT"|"OK", extra field checks).
# Rejects additionally require validNext=accept (a rejected record must not
# consume state: the next record can still be decoded) and stillOpen=0.
FRAMES = {
    "truncated-header":       ("REJECT", None),
    "truncated-binder-table": ("REJECT", None),
    "binder-count-overflow":  ("REJECT", None),
    "binder-count-negative":  ("REJECT", None),
    "fd-count-negative":      ("REJECT", None),
    "fd-count-huge":          ("REJECT", None),
    "fd-count-over-max":      ("REJECT", None),
    "fd-table-past-frame":    ("REJECT", None),
    "length-past-record":     ("REJECT", None),
    "short-record":           ("REJECT", None),
    "fd-offset-negative":     ("REJECT", None),
    "fd-offset-past-body":    ("REJECT", None),
    "fd-offset-odd":          ("ACCEPT", None),
    "fd-offsets-overlap":     ("REJECT", None),
    "fd-offsets-overlap-range": ("REJECT", None),
    "fd-not-received":        ("REJECT", None),
    "fd-partially-received":  ("REJECT", None),
    "valid-control":          ("ACCEPT", None),
    "bad-magic":              ("REJECT", None),
    "trailing-bytes":         ("REJECT", None),
    "unknown-target":         ("REJECT", None),
    "fd-many-reject":         ("REJECT", None),
    "concurrent":             ("OK", None),
    "peer-end-closed":        ("ACCEPT", None),
    "teardown":               ("OK", None),
}
ACCEPT_CASES = [n for n, (v, _) in FRAMES.items() if v == "ACCEPT"]
PROBE_CASES = [
    "t01-concurrent-sync", "t02-nested-reentrancy", "t03-oneway-nonblocking",
    "t04-oneway-order", "t05-failure-distinction", "t06-peer-identity",
    "t06-forged-identity", "t07-fd-transfer", "t09-saturation-deadline",
    "t09b-close-under-load",
    "t10-death-wake", "t11-handle-churn", "t12-release-vs-death-race",
]
RED_CASES = [
    ("redlink:red-concurrent", "RED"), ("redlink:red-oneway", "RED"),
    ("redlink:red-nested", "RED"), ("redlink:green-concurrent", "GREEN"),
    ("redlink:green-oneway", "GREEN"), ("redlink:green-nested", "GREEN"),
]


def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def fields(ret):
    out = {}
    for part in str(ret).split(";"):
        if "=" in part:
            key, _, value = part.partition("=")
            out[key.strip()] = value.strip()
    return out


def verify(data):
    failures = []
    rows = {}
    for row in data if isinstance(data, list) else data.get("events", []):
        if row.get("event") != "ipcFrame":
            continue
        rows[row.get("cmd", "?")] = row.get("ret", "")
    top_pass = data.get("pass") if isinstance(data, dict) else None

    for name, (verdict, _) in FRAMES.items():
        ret = rows.get(name)
        if ret is None:
            failures.append("frames %s: no row" % name)
            continue
        got = fields(ret).get("verdict")
        if got != verdict:
            failures.append("frames %s: verdict %s want %s" % (name, got, verdict))
        if verdict == "REJECT":
            if fields(ret).get("validNext") != "accept":
                failures.append("frames %s: validNext not accept (state must be unconsumed): %s" % (name, ret))
            if fields(ret).get("stillOpen") not in ("0", None):
                failures.append("frames %s: stillOpen=%s want 0" % (name, fields(ret).get("stillOpen")))
        if verdict == "ACCEPT":
            if fields(ret).get("senderPlaceholderInBody") == "yes":
                failures.append("frames %s: accepted record must not leak the sender placeholder: %s" % (name, ret))
    for name in PROBE_CASES:
        ret = rows.get("probeCase:" + name)
        if ret is None:
            failures.append("probe %s: no row" % name)
        elif fields(ret).get("pass") != "1":
            failures.append("probe %s: pass=%s want 1 (%s)" % (name, fields(ret).get("pass"), ret))
    for name, want in RED_CASES:
        ret = rows.get(name)
        if ret is None:
            failures.append("red %s: no row" % name)
        elif fields(ret).get("verdict") != want:
            failures.append("red %s: verdict=%s want %s (%s)" % (name, fields(ret).get("verdict"), want, ret))
    if isinstance(data, dict) and top_pass is not True:
        failures.append("top-level pass=%r want true" % (top_pass,))
    return failures


def main(argv):
    if len(argv) < 2:
        print("usage: %s RESULTS_JSON [RESULTS_JSON...]" % argv[0], file=sys.stderr)
        return 2
    bad = 0
    for path in argv[1:]:
        failures = verify(load(path))
        for line in failures:
            print("FAIL %s: %s" % (path, line))
        print("%s: %d checks-file failures" % (path, len(failures)))
        bad += len(failures)
    print("VERIFY %s" % ("PASSED" if bad == 0 else "FAILED (%d)" % bad))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
