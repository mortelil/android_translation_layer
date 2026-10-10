#!/usr/bin/env python3
"""verify_lifecycle.py - independent evidence verifier for the ACTIVITY-LIFECYCLE probe.

Usage:
    verify_lifecycle.py <android_trace> <candidate_trace> [manifest.json]

Exit status:
    0  every selected mandatory check passed for BOTH sides and they match
    1  rejected: invalid/incomplete evidence (REJECT <code> <reason> on stdout)
    2  usage / internal error (a crash is NOT a semantic rejection)

The verifier never treats a parsed-but-wrong trace as "OK". It validates the
reference and the candidate independently against a frozen, contract-derived
manifest (AOSP android-17.0.0_r1, frameworks-base commit
94b4c163b7dfe5ce3607f7bb8456f9573f7de57d: forward registration-order dispatch,
additive duplicates, one-occurrence removal, callback delivered between the
app's pre/post super markers, main thread, null Bundle on fresh create).

Machine-readable rejections use the form:  REJECT <CODE> <reason>
"""
import json
import os
import re
import sys
from collections import Counter, OrderedDict

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2


class Reject(Exception):
    def __init__(self, code, reason):
        super().__init__("%s %s" % (code, reason))
        self.code = code
        self.reason = reason


def reject(code, reason):
    raise Reject(code, reason)


# ---------------------------------------------------------------- manifest ---
def load_manifest(path):
    if not os.path.isfile(path):
        reject("MANIFEST_INVALID", "manifest file not found: %s" % path)
    try:
        with open(path, encoding="utf-8") as fh:
            man = json.load(fh)
    except Exception as exc:  # noqa: BLE001 - any parse failure is a manifest fault
        reject("MANIFEST_INVALID", "manifest is not valid JSON: %s" % exc)
    for key in ("identity", "scenarios", "required_event_fields", "callback_map",
                "bundle_map", "default_bundle", "completion_marker", "preamble"):
        if key not in man:
            reject("MANIFEST_INVALID", "manifest missing key %r" % key)
    if not isinstance(man["scenarios"], list) or not man["scenarios"]:
        reject("MANIFEST_INVALID", "manifest.scenarios must be a non-empty list")
    ids = [s.get("id") for s in man["scenarios"]]
    if any(not isinstance(i, str) or not i for i in ids) or len(set(ids)) != len(ids):
        reject("MANIFEST_INVALID", "manifest scenarios must have unique non-empty ids")
    for s in [man["preamble"]] + man["scenarios"]:
        if not isinstance(s.get("events"), list):
            reject("MANIFEST_INVALID", "scenario %r must have an events list" % s.get("id"))
        if not isinstance(s.get("observers", []), list):
            reject("MANIFEST_INVALID", "scenario %r observers must be a list" % s.get("id"))
        if "begin" not in s or "end" not in s:
            reject("MANIFEST_INVALID", "scenario %r missing begin/end counts" % s.get("id"))
        for ev in s["events"]:
            if ev.get("kind") not in ("app", "lifecycle"):
                reject("MANIFEST_INVALID", "scenario %r has an event with kind %r"
                       % (s.get("id"), ev.get("kind")))
    return man


# ------------------------------------------------------------- line parsing ---
TRACE_BEGIN = re.compile(r"^LIFE_TRACE_BEGIN (.*)$")
SCEN_BEGIN = re.compile(r"^LIFE_SCEN_BEGIN (\S+)$")
SCEN_END = re.compile(r"^LIFE_SCEN_END (\S+)$")
EVT_LINE = re.compile(r"^LIFE_EVT (\{.*\})$")
EXC_LINE = re.compile(r"^LIFE_EXCEPTION (.*)$")


class Trace(object):
    def __init__(self, path, side):
        self.path = path
        self.side = side
        self.raw = ""
        self.header = OrderedDict()
        self.markers = []          # (kind, value, lineno)
        self.events = []           # dicts (raw, with _line)
        self.json_block = None     # parsed aggregate
        self.exceptions = []       # text of LIFE_EXCEPTION lines
        self.all_done = 0
        self.end_of_trace = None

    # -- reasons are prefixed with the side so a reject is attributable ----
    def r(self, code, reason):
        reject(code, "%s: %s" % (self.side, reason))


def read_text(tr):
    if not os.path.exists(tr.path):
        tr.r("MISSING_EVIDENCE", "file not found: %s" % tr.path)
    if os.path.isdir(tr.path):
        tr.r("MISSING_EVIDENCE", "path is a directory: %s" % tr.path)
    try:
        with open(tr.path, "rb") as fh:
            data = fh.read()
    except OSError as exc:
        tr.r("MISSING_EVIDENCE", "cannot read: %s" % exc)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        tr.r("MALFORMED_LINE", "not UTF-8 text: %s" % exc)
    if text.strip() == "":
        tr.r("EMPTY_EVIDENCE", "trace has no content (%d bytes)" % len(data))
    if not text.endswith("\n"):
        tr.r("TRUNCATED", "trace does not end with a newline (incomplete write)")
    tr.raw = text


def parse_trace(tr):
    read_text(tr)
    lines = tr.raw.split("\n")
    # split() on a trailing newline leaves a final empty element; drop it.
    if lines and lines[-1] == "":
        lines.pop()

    # header
    m = TRACE_BEGIN.match(lines[0])
    if not m:
        tr.r("NO_HEADER", "first line is not LIFE_TRACE_BEGIN: %r" % lines[0][:80])
    for tok in m.group(1).split():
        if "=" not in tok:
            tr.r("MALFORMED_LINE", "header token without '=': %r" % tok)
        k, v = tok.split("=", 1)
        tr.header[k] = v
    for k in ("task_id", "run_id", "package", "probe"):
        if k not in tr.header:
            tr.r("NO_HEADER", "header missing %s=" % k)

    in_json = False
    json_lines = []
    for idx in range(1, len(lines)):
        line = lines[idx]
        lineno = idx + 1
        if in_json:
            if line == "LIFE_JSON_END":
                in_json = False
                try:
                    tr.json_block = json.loads("\n".join(json_lines))
                except Exception as exc:  # noqa: BLE001
                    tr.r("MALFORMED_JSON", "aggregate JSON does not parse: %s" % exc)
                continue
            json_lines.append(line)
            continue
        if line == "":
            tr.r("MALFORMED_LINE", "unexpected blank line at %d" % lineno)
        if line == "LIFE_ALL_DONE":
            tr.all_done += 1
            tr.markers.append(("ALL_DONE", None, lineno))
            tr.end_of_trace = lineno
            continue
        if line == "LIFE_JSON_BEGIN":
            if tr.json_block is not None:
                tr.r("MALFORMED_LINE", "second LIFE_JSON_BEGIN at %d" % lineno)
            in_json = True
            tr.markers.append(("JSON_BEGIN", None, lineno))
            continue
        if line == "LIFE_JSON_END":
            tr.r("MALFORMED_LINE", "LIFE_JSON_END without LIFE_JSON_BEGIN at %d" % lineno)
        m = SCEN_BEGIN.match(line)
        if m:
            tr.markers.append(("SCEN_BEGIN", m.group(1), lineno))
            continue
        m = SCEN_END.match(line)
        if m:
            tr.markers.append(("SCEN_END", m.group(1), lineno))
            continue
        m = EVT_LINE.match(line)
        if m:
            try:
                obj = json.loads(m.group(1))
            except Exception as exc:  # noqa: BLE001
                tr.r("MALFORMED_JSON", "LIFE_EVT payload does not parse at %d: %s" % (lineno, exc))
            if not isinstance(obj, dict):
                tr.r("MALFORMED_JSON", "LIFE_EVT payload is not an object at %d" % lineno)
            obj["_line"] = lineno
            tr.events.append(obj)
            continue
        m = EXC_LINE.match(line)
        if m:
            tr.exceptions.append(m.group(1))
            tr.markers.append(("EXCEPTION", m.group(1), lineno))
            continue
        tr.r("MALFORMED_LINE", "unrecognized line %d: %r" % (lineno, line[:100]))
    if in_json:
        tr.r("TRUNCATED", "LIFE_JSON_BEGIN without LIFE_JSON_END (interrupted write)")
    return tr


# ------------------------------------------------------------ normalisation ---
def event_tuple(e, fields):
    return tuple(e.get(f) for f in fields)


# --------------------------------------------------------------- validation ---
def validate(tr, man):
    ident = man["identity"]
    req = man["required_event_fields"]
    types = man.get("event_field_types", {})
    finite = man.get("numerics_must_be_finite", [])
    cb = man["callback_map"]

    # -- identity: header vs manifest (APK identity = package/probe/task) -----
    for k in ("task_id", "probe", "package"):
        if tr.header.get(k) != ident[k]:
            tr.r("APK_IDENTITY", "header %s=%r expected %r" % (k, tr.header.get(k), ident[k]))

    # -- aggregate JSON identity + run identity ------------------------------
    if tr.json_block is None:
        tr.r("INTERRUPTED", "no aggregate LIFE_JSON_BEGIN/END block")
    jb = tr.json_block
    for k in ("task_id", "probe", "run_id"):
        if k not in jb:
            tr.r("MISSING_FIELD", "aggregate JSON missing %s" % k)
    if jb.get("task_id") != ident["task_id"] or jb.get("probe") != ident["probe"]:
        tr.r("APK_IDENTITY", "aggregate JSON task_id/probe do not match manifest")
    cfg = jb.get("config", {})
    if not isinstance(cfg, dict) or cfg.get("package") != ident["package"]:
        tr.r("APK_IDENTITY", "aggregate JSON config.package != %r" % ident["package"])
    if jb.get("run_id") != tr.header.get("run_id"):
        tr.r("RUN_IDENTITY", "aggregate run_id %r != header run_id %r"
             % (jb.get("run_id"), tr.header.get("run_id")))
    cfg = jb.get("config", {})
    if not isinstance(cfg, dict) or cfg.get("package") != ident["package"]:
        tr.r("APK_IDENTITY", "aggregate JSON config.package != %r" % ident["package"])
    if cfg.get("scenarios") != [s["id"] for s in man["scenarios"]]:
        tr.r("SCENARIO_IDENTITY", "aggregate config.scenarios %r != manifest order"
             % (cfg.get("scenarios"),))
    obs = jb.get("observations", {})
    done = obs.get("scenarios_completed") if isinstance(obs, dict) else None
    required_done = [s["id"] for s in man["scenarios"] if not s.get("externally_driven")]
    if not isinstance(done, list) or [x for x in required_done if x not in done]:
        tr.r("INTERRUPTED", "aggregate observations.scenarios_completed=%r lacks a "
                            "completed mandatory scenario (need %r)" % (done, required_done))
    if jb.get("exceptions"):
        tr.r("INTERRUPTED", "aggregate JSON reports exceptions: %r" % jb["exceptions"])
    if tr.exceptions:
        tr.r("INTERRUPTED", "LIFE_EXCEPTION present: %r" % tr.exceptions[0])

    # -- completion marker ---------------------------------------------------
    if tr.all_done != 1:
        tr.r("NO_COMPLETION", "expected exactly one LIFE_ALL_DONE, found %d" % tr.all_done)

    # -- scenario markers vs manifest ----------------------------------------
    preamble = man["preamble"]
    man_by_id = {preamble["id"]: preamble}
    for s in man["scenarios"]:
        man_by_id[s["id"]] = s
    began = [v for (k, v, _) in tr.markers if k == "SCEN_BEGIN"]
    ended = [v for (k, v, _) in tr.markers if k == "SCEN_END"]
    for sid in began + ended:
        if sid not in man_by_id:
            tr.r("SCENARIO_IDENTITY", "unknown scenario id %r in markers" % sid)
    required_begins = [s["id"] for s in man["scenarios"] if s.get("begin")]
    for sid in required_begins:
        if sid not in began:
            tr.r("MISSING_SCENARIO", "mandatory scenario %s never began" % sid)
    # exact BEGIN multiplicity per the manifest (counts, not just presence)
    began_ct, ended_ct = Counter(began), Counter(ended)
    for s in [preamble] + man["scenarios"]:
        sid = s["id"]
        if began_ct.get(sid, 0) != s["begin"]:
            tr.r("SCENARIO_MULTIPLICITY",
                 "scenario %s has %d LIFE_SCEN_BEGIN, manifest declares begin=%d"
                 % (sid, began_ct.get(sid, 0), s["begin"]))
    # order of begins must follow the manifest order (subsequence)
    order = [sid for sid in began if sid in required_begins]
    expect_order = [sid for sid in required_begins if sid in order]
    if order != expect_order:
        tr.r("SCENARIO_ORDER", "scenario BEGIN order %r != manifest order %r"
             % (order, expect_order))
    # completion of non-external scenarios
    for s in man["scenarios"]:
        if s.get("end"):
            if s["id"] not in ended:
                tr.r("INTERRUPTED", "scenario %s has no LIFE_SCEN_END" % s["id"])
    # exact END multiplicity per the manifest; an externally driven scenario may
    # end at most one extra time (its optional external close), never more.
    for s in [preamble] + man["scenarios"]:
        sid = s["id"]
        got = ended_ct.get(sid, 0)
        if s.get("externally_driven"):
            if got > s["end"] + 1:
                tr.r("INTERRUPTED", "externally driven scenario %s ended %d times "
                                    "(at most %d)" % (sid, got, s["end"] + 1))
        elif got != s["end"]:
            tr.r("SCENARIO_MULTIPLICITY",
                 "scenario %s has %d LIFE_SCEN_END, manifest declares end=%d"
                 % (sid, got, s["end"]))
    # ALL_DONE must be after every mandatory BEGIN
    all_done_line = [ln for (k, v, ln) in tr.markers if k == "ALL_DONE"][0]
    last_begin = max(ln for (k, v, ln) in tr.markers if k == "SCEN_BEGIN")
    if all_done_line < last_begin:
        tr.r("NO_COMPLETION", "LIFE_ALL_DONE precedes the last scenario begin")

    # -- event field presence / types / numeric sanity -----------------------
    prev_seq = 0
    for e in tr.events:
        for f in req:
            if f not in e:
                tr.r("MISSING_FIELD", "event at line %s missing field %r"
                     % (e.get("_line"), f))
        if e.get("k") != "evt":
            tr.r("BAD_TYPE", "event k != 'evt' at line %s" % e.get("_line"))
        for f in finite:
            v = e.get(f)
            if isinstance(v, float):
                tr.r("NONFINITE", "field %s is non-integer/nonfinite (%r) at line %s"
                     % (f, v, e.get("_line")))
        for f, kind in types.items():
            v = e.get(f)
            if kind == "int":
                if isinstance(v, bool) or not isinstance(v, int):
                    tr.r("BAD_TYPE", "field %s must be int at line %s (got %r)"
                         % (f, e.get("_line"), v))
            elif kind == "bool":
                if not isinstance(v, bool):
                    tr.r("BAD_TYPE", "field %s must be bool at line %s (got %r)"
                         % (f, e.get("_line"), v))
        seq = e.get("seq")
        if seq != prev_seq + 1:
            tr.r("SEQ", "seq not the contiguous 1..N sequence at line %s (got %r, expected %d)"
                 % (e.get("_line"), seq, prev_seq + 1))
        prev_seq = seq
        if e.get("owner") != man["owner"]:
            tr.r("OWNER", "owner %r != %r at line %s"
                 % (e.get("owner"), man["owner"], e.get("_line")))
        if e.get("main") is not True or e.get("threadRole") != man["main_thread_role"]:
            tr.r("THREAD", "event not on main thread at line %s (main=%r role=%r)"
                 % (e.get("_line"), e.get("main"), e.get("threadRole")))
    if not tr.events:
        tr.r("EMPTY_EVIDENCE", "no LIFE_EVT records at all")

    # -- event scenario attribution ------------------------------------------
    for e in tr.events:
        if e.get("scenario") not in man_by_id:
            tr.r("EVENT_SCENARIO", "event %r has unknown scenario %r"
                 % (e.get("event"), e.get("scenario")))

    # -- event placement: each event must lie inside its scenario's span -----
    spans = {}
    for sid in began:
        b = [ln for (k, v, ln) in tr.markers if k == "SCEN_BEGIN" and v == sid][0]
        ends = [ln for (k, v, ln) in tr.markers if k == "SCEN_END" and v == sid]
        spans[sid] = (b, ends[0] if ends else None)
    first_begin = min(ln for (k, v, ln) in tr.markers if k == "SCEN_BEGIN")
    for e in tr.events:
        sid = e["scenario"]
        ln = e["_line"]
        if sid == preamble["id"]:
            if ln >= first_begin:
                tr.r("MISPLACED_EVENT", "preamble event %r at line %d appears after a "
                                        "scenario begin" % (e.get("event"), ln))
            continue
        if sid not in spans:
            tr.r("MISPLACED_EVENT", "event %r at line %d has no open scenario %r"
                 % (e.get("event"), ln, sid))
        b, en = spans[sid]
        if ln < b or (en is not None and ln > en):
            tr.r("MISPLACED_EVENT", "event %r attributed to %s at line %d is outside "
                                    "its scenario span [%d,%s]"
                 % (e.get("event"), sid, ln, b, en))

    # -- per-scenario exact ordered event sequences --------------------------
    by_scen = {}
    for e in tr.events:
        by_scen.setdefault(e["scenario"], []).append(e)

    for s in [preamble] + man["scenarios"]:
        sid = s["id"]
        actual = by_scen.get(sid, [])
        expected = expand_expected(man, s)
        if s.get("externally_driven"):
            continue  # events optional; attribution already validated
        compare_sequence(tr, sid, expected, actual, cb)

    # -- aggregate JSON must agree with the LIFE_EVT line stream -------------
    # The aggregate summary is written at the completion point, so it must be
    # exactly the prefix of the line stream it summarises; only events the
    # probe appends afterwards for an externally driven close may follow it.
    jbe = jb.get("events")
    if not isinstance(jbe, list):
        tr.r("JSON_MISMATCH", "aggregate JSON 'events' is not a list (%s)"
             % type(jbe).__name__)
    if len(jbe) > len(tr.events):
        tr.r("JSON_MISMATCH", "aggregate JSON lists %d events but only %d LIFE_EVT "
                              "lines exist" % (len(jbe), len(tr.events)))
    for i in range(len(jbe)):
        line_evt = dict(tr.events[i])
        line_evt.pop("_line", None)
        if not isinstance(jbe[i], dict) or jbe[i] != line_evt:
            tr.r("JSON_MISMATCH", "aggregate JSON event %d disagrees with LIFE_EVT "
                                  "line %s" % (i, tr.events[i].get("_line")))
    allowed_extra = set(s["id"] for s in man["scenarios"] if s.get("externally_driven"))
    for e in tr.events[len(jbe):]:
        if e.get("scenario") not in allowed_extra:
            tr.r("JSON_MISMATCH", "LIFE_EVT line %s (scenario %r) is missing from the "
                                  "aggregate JSON" % (e.get("_line"), e.get("scenario")))
    return True


def expand_expected(man, scen):
    """Expand the contract into the exact ordered event tuples for a scenario."""
    cb = man["callback_map"]
    dflt = man["default_bundle"]
    bmap = man["bundle_map"]
    out = []
    for ev in scen["events"]:
        if ev["kind"] == "app":
            out.append((scen["id"], ev["actor"], ev["event"], "-", "-", "-",
                        man["owner"], man["main_thread_role"], True))
        elif ev["kind"] == "lifecycle":
            act = ev["activity"]
            base = ev["base"]
            bundle = bmap.get(base, dflt)
            out.append((scen["id"], act, base + ".pre", act, bundle, "pre",
                        man["owner"], man["main_thread_role"], True))
            for obs in scen["observers"]:
                out.append((scen["id"], obs, cb[base], act, bundle, "-",
                            man["owner"], man["main_thread_role"], True))
            out.append((scen["id"], act, base + ".post", act, bundle, "post",
                        man["owner"], man["main_thread_role"], True))
        else:
            raise Reject("MANIFEST_INVALID", "unknown event kind %r" % ev["kind"])
    return out


FIELDS = ("scenario", "actor", "event", "activity", "bundle", "phase",
          "owner", "threadRole", "main")


def compare_sequence(tr, sid, expected, actual, cb):
    exp = [tuple(t) for t in expected]
    act = [event_tuple(e, FIELDS) for e in actual]
    if act == exp:
        return
    ec, ac = Counter(exp), Counter(act)
    if ac == ec:
        tr.r("ORDER", "scenario %s events are the right multiset but out of order" % sid)
    if ac < ec:
        tr.r("MISSING_EVENT", "scenario %s is missing %d event(s); first missing %r"
             % (sid, sum((ec - ac).values()), (ec - ac).most_common(1)[0][0]))
    if ac > ec:
        tr.r("DUPLICATE_EVENT", "scenario %s has %d extra/duplicate event(s); first extra %r"
             % (sid, sum((ac - ec).values()), (ac - ec).most_common(1)[0][0]))
    # multiset diverges -> pinpoint the first differing field
    n = min(len(exp), len(act))
    for i in range(n):
        if exp[i] != act[i]:
            for j, f in enumerate(FIELDS):
                if exp[i][j] != act[i][j]:
                    code = {
                        "scenario": "EVENT_SCENARIO",
                        "actor": "ACTOR",
                        "event": "WRONG_EVENT",
                        "activity": "MISASSOC",
                        "bundle": "BUNDLE",
                        "phase": "PHASE",
                        "owner": "OWNER",
                        "threadRole": "THREAD",
                        "main": "THREAD",
                    }[f]
                    tr.r(code, "scenario %s event %d: %s=%r expected %r"
                         % (sid, i, f, act[i][j], exp[i][j]))
    # same prefix but different length covered above; fall back
    tr.r("SEMANTIC_MISMATCH", "scenario %s event list differs (len %d vs %d)"
         % (sid, len(act), len(exp)))


# ------------------------------------------------------------------ compare ---
def compare_cross(ra, rc, man):
    # configuration must be identical (same frozen APK/settings)
    ca = ra.json_block.get("config", {})
    cc = rc.json_block.get("config", {})
    if ca != cc:
        reject("STALE", "candidate config differs from reference config (mismatched evidence)")
    # same run reused as both sides is stale evidence, not an independent pair
    if ra.header.get("run_id") == rc.header.get("run_id"):
        reject("STALE", "reference and candidate share run_id %r (not independent runs)"
               % ra.header.get("run_id"))
    # per-scenario normalised sequences must match exactly
    for s in [man["preamble"]] + man["scenarios"]:
        if s.get("externally_driven"):
            continue  # timing of an external close is not a comparable expectation
        sid = s["id"]
        a = [event_tuple(e, FIELDS) for e in ra.events if e["scenario"] == sid]
        c = [event_tuple(e, FIELDS) for e in rc.events if e["scenario"] == sid]
        if a != c:
            detail = "len %d vs %d" % (len(a), len(c))
            for i in range(min(len(a), len(c))):
                if a[i] != c[i]:
                    detail = "first diff at %d: %r vs %r" % (i, a[i], c[i])
                    break
            reject("SEMANTIC_MISMATCH", "scenario %s differs: %s" % (sid, detail))
    return True


# --------------------------------------------------------------------- main ---
def main(argv):
    if len(argv) < 3 or len(argv) > 4:
        sys.stderr.write(__doc__)
        return EXIT_ERROR
    here = os.path.dirname(os.path.abspath(__file__))
    man_path = argv[3] if len(argv) == 4 else os.path.join(here, "manifest.json")
    try:
        if os.environ.get("VERIFY_SELFTEST_CRASH"):
            # Injected only by run_analyzer_selftests.sh to prove that an internal
            # crash is surfaced as exit 2 / ERROR internal and is NEVER counted as
            # a semantic REJECT.
            raise RuntimeError("injected self-test crash")
        man = load_manifest(man_path)
        ra = parse_trace(Trace(os.path.abspath(argv[1]), "reference"))
        rc = parse_trace(Trace(os.path.abspath(argv[2]), "candidate"))
        validate(ra, man)
        validate(rc, man)
        compare_cross(ra, rc, man)
    except Reject as rj:
        print("REJECT %s %s" % (rj.code, rj.reason))
        return EXIT_REJECT
    except Exception as exc:  # noqa: BLE001 - never let a crash look like a reject
        import traceback
        traceback.print_exc()
        print("ERROR internal %s" % exc)
        return EXIT_ERROR
    print("OK all mandatory checks passed (reference and candidate validated, matched)")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv))
