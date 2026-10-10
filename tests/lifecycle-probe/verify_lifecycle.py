#!/usr/bin/env python3
"""verify_lifecycle.py - independent evidence verifier for the ACTIVITY-LIFECYCLE probe.

Usage:
    verify_lifecycle.py <android_trace> <candidate_trace> [manifest.json] [baseline_trace]
    verify_lifecycle.py <android_trace> <candidate_trace> --baseline=<atl_baseline_trace>

The manifest may be given as a positional ``*.json`` path or omitted (the
sibling manifest.json is then used). Any other trailing positional (or
``--baseline=``) is the optional ATL baseline trace.

Two candidate-acceptance modes:

* STRICT (no baseline). The candidate must reproduce the manifest's exact
  ordered per-scenario event stream, exactly as before.
* PINNED (baseline supplied). The candidate is validated with the
  PER-ACTIVITY-EVENT lifecycle-callback contract: mandatory event (group)
  presence, and exact order/multiplicity/identity/Bundle/phase WITHIN each
  ``(activity,event)`` callback list. The cross-Activity transition ordering
  (and any other pre-existing ATL feature divergence) is not compared to
  Android directly but PINNED to the baseline: the candidate's non-callback
  event stream must equal the baseline's per scenario (so the patch introduces
  no new change). An explicit ``DIVERGENCE`` line is emitted for every
  scenario that differs from the Android reference. A missing baseline when a
  comparison is required is a rejection (``MISSING_BASELINE``), never a pass.

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
        if line.startswith("LIFE_PROBE_MARK "):
            # Probe control line (e.g. the terminal-scenario completion signal
            # LIFE_PROBE_MARK TERMINAL_THROW_DONE). Carries no scenario
            # semantics; recorded so it can be located but otherwise ignored.
            tr.markers.append(("PROBE_MARK", line[len("LIFE_PROBE_MARK "):], lineno))
            continue
        tr.r("MALFORMED_LINE", "unrecognized line %d: %r" % (lineno, line[:100]))
    if in_json:
        tr.r("TRUNCATED", "LIFE_JSON_BEGIN without LIFE_JSON_END (interrupted write)")
    return tr


# ------------------------------------------------------------ normalisation ---
def event_tuple(e, fields):
    return tuple(e.get(f) for f in fields)


# --------------------------------------------------------------- validation ---
def validate(tr, man, role="full"):
    """Validate a parsed trace against the manifest.

    role:
      "full"    reference / strict candidate: exact ordered stream per scenario.
      "relaxed" candidate: per-(activity,event) callback contract + presence;
                cross-Activity ordering is pinned to the baseline (not checked
                here).
      "baseline" an ATL baseline trace: structural checks only (identity,
                markers, completion, fields); its known divergence from Android
                is exactly what it is used to pin, so it is not re-contracted.
    """
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
    required_done = [s["id"] for s in man["scenarios"]
                     if not s.get("externally_driven") and not s.get("terminal")]
    if not isinstance(done, list) or [x for x in required_done if x not in done]:
        tr.r("INTERRUPTED", "aggregate observations.scenarios_completed=%r lacks a "
                            "completed mandatory scenario (need %r)" % (done, required_done))
    if jb.get("exceptions"):
        tr.r("INTERRUPTED", "aggregate JSON reports exceptions: %r" % jb["exceptions"])
    terminal_ids = set(s["id"] for s in man["scenarios"] if s.get("terminal"))
    terminal_begins = [ln for (k, v, ln) in tr.markers
                       if k == "SCEN_BEGIN" and v in terminal_ids]
    if tr.exceptions:
        # A terminal scenario is expected to crash the framework on Android
        # (mCalled check); that LIFE_EXCEPTION is only acceptable after the
        # terminal scenario has begun. Anything earlier is real interruption.
        exc_lines = [ln for (k, v, ln) in tr.markers if k == "EXCEPTION"]
        if not terminal_begins or any(ln < min(terminal_begins) for ln in exc_lines):
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
    required_begins = [s["id"] for s in man["scenarios"]
                       if s.get("begin") and not s.get("terminal")]
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
        if s.get("externally_driven") or s.get("terminal"):
            if got > s["end"] + 1:
                tr.r("INTERRUPTED", "scenario %s ended %d times (at most %d allowed)"
                     % (sid, got, s["end"] + 1))
        elif got != s["end"]:
            tr.r("SCENARIO_MULTIPLICITY",
                 "scenario %s has %d LIFE_SCEN_END, manifest declares end=%d"
                 % (sid, got, s["end"]))
    # ALL_DONE must be after every mandatory BEGIN
    all_done_line = [ln for (k, v, ln) in tr.markers if k == "ALL_DONE"][0]
    last_begin = max(ln for (k, v, ln) in tr.markers
                     if k == "SCEN_BEGIN" and v not in terminal_ids)
    if all_done_line < last_begin:
        tr.r("NO_COMPLETION", "LIFE_ALL_DONE precedes the last scenario begin")

    # -- terminal scenario(s): per-activity-event contract after the aggregate -
    # The terminal scenario is validated with the same per-(activity,event)
    # callback contract everywhere except on a baseline trace (whose whole
    # point is that its divergence is being pinned).
    if role != "baseline":
        for s in man["scenarios"]:
            if s.get("terminal"):
                validate_terminal(tr, man, s, all_done_line)

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

    # -- per-scenario event validation ---------------------------------------
    by_scen = {}
    for e in tr.events:
        by_scen.setdefault(e["scenario"], []).append(e)

    if role in ("full", "relaxed"):
        preamble = man["preamble"]
        for s in [preamble] + man["scenarios"]:
            sid = s["id"]
            actual = by_scen.get(sid, [])
            if s.get("externally_driven") or s.get("terminal"):
                # externally driven: events optional, attribution validated.
                # terminal: validated by validate_terminal() above.
                continue
            expected = expand_expected(man, s)
            if role == "relaxed":
                if sid == preamble["id"]:
                    # preamble carries only loose probe events (no lifecycle
                    # framing); it is ordering-independent of Activities.
                    sequence_diff(tr, "scenario %s loose events" % sid,
                                  expected, actual)
                    continue
                if not s.get("observers"):
                    # Non-lifecycle (FragmentManager) scenario: not part of the
                    # lifecycle callback contract; its divergence is pinned to
                    # the baseline in compare_baseline().
                    continue
                exp_loose, exp_groups = group_events(expected)
                act_loose, act_groups = group_events(actual)
                compare_groups(tr, man, "scenario %s" % sid, exp_groups, act_groups)
                sequence_diff(tr, "scenario %s loose events" % sid,
                              exp_loose, act_loose)
            else:
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
    allowed_extra = set(s["id"] for s in man["scenarios"]
                        if s.get("externally_driven") or s.get("terminal"))
    for e in tr.events[len(jbe):]:
        if e.get("scenario") not in allowed_extra:
            tr.r("JSON_MISMATCH", "LIFE_EVT line %s (scenario %r) is missing from the "
                                  "aggregate JSON" % (e.get("_line"), e.get("scenario")))
    return True


def expand_expected(man, scen):
    """Expand the contract into the exact ordered event tuples for a scenario.

    Backward compatible with v1. Per-lifecycle-event overrides:
      "observers": [ids]      replace the scenario observer list for this event
      "callbacks": [entry]    replace the callback list entirely; an entry is
                              an observer id (mapped via callback_map) or a dict
                              {actor,event,activity?,bundle?,phase?}
      "between":   [dict]     extra events emitted after the callbacks and
                              before the ".post" framing line
    """
    cb = man["callback_map"]
    dflt = man["default_bundle"]
    bmap = man["bundle_map"]
    owner = man["owner"]
    role = man["main_thread_role"]
    out = []
    for ev in scen["events"]:
        if ev["kind"] == "app":
            out.append((scen["id"], ev["actor"], ev["event"], "-", "-", "-",
                        owner, role, True))
        elif ev["kind"] == "lifecycle":
            act = ev["activity"]
            base = ev["base"]
            bundle = bmap.get(base, dflt)
            out.append((scen["id"], act, base + ".pre", act, bundle, "pre",
                        owner, role, True))
            callbacks = ev.get("callbacks")
            if callbacks is None:
                callbacks = list(ev.get("observers", scen["observers"]))
            for item in callbacks:
                if isinstance(item, str):
                    out.append((scen["id"], item, cb[base], act, bundle, "-",
                                owner, role, True))
                elif isinstance(item, dict):
                    out.append((scen["id"], item["actor"], item["event"],
                                item.get("activity", act),
                                item.get("bundle", bundle),
                                item.get("phase", "-"), owner, role, True))
                else:
                    reject("MANIFEST_INVALID", "scenario %s bad callback entry %r"
                           % (scen.get("id"), item))
            for item in ev.get("between", []):
                out.append((scen["id"], item["actor"], item["event"],
                            item.get("activity", act),
                            item.get("bundle", bundle),
                            item.get("phase", "-"), owner, role, True))
            out.append((scen["id"], act, base + ".post", act, bundle, "post",
                        owner, role, True))
        else:
            raise Reject("MANIFEST_INVALID", "unknown event kind %r" % ev["kind"])
    return out


def expand_terminal_prefix(man, scen):
    """Expand a terminal scenario's required ordered prefix into event tuples.

    Each entry in scen["terminal_prefix"] names actor/event and may pin
    activity/bundle/phase; the remaining tuple fields (owner, threadRole, main)
    come from the manifest defaults, exactly as for a normal event.
    """
    dflt = man["default_bundle"]
    owner = man["owner"]
    role = man["main_thread_role"]
    out = []
    for item in scen.get("terminal_prefix", []):
        out.append((scen["id"], item["actor"], item["event"],
                    item.get("activity", "-"), item.get("bundle", dflt),
                    item.get("phase", "-"), owner, role, True))
    return out


_FIELD_REJECT = {
    "scenario": "EVENT_SCENARIO",
    "actor": "ACTOR",
    "event": "WRONG_EVENT",
    "activity": "MISASSOC",
    "bundle": "BUNDLE",
    "phase": "PHASE",
    "owner": "OWNER",
    "threadRole": "THREAD",
    "main": "THREAD",
}


def validate_terminal(tr, man, scen, all_done_line):
    """Validate a terminal scenario with the per-(activity,event) contract.

    A terminal scenario begins AFTER the aggregate/completion markers and runs
    last. Its event stream need not be complete: on Android the framework
    crashes the process (SuperNotCalledException) once the observer throw
    escapes onCreate, so the trace may legitimately end inside it.

    The contract is applied per (activity,event) callback list, NOT as one
    global total order across Activities: the registration events must be
    present, the ActA pause callback list and the ActB#11 create callback list
    must appear with exactly the required order/multiplicity/fields, and the
    observer after the throwing one must NOT have run.
    """
    sid = scen["id"]
    begins = [ln for (k, v, ln) in tr.markers if k == "SCEN_BEGIN" and v == sid]
    if not begins:
        tr.r("MISSING_SCENARIO", "terminal scenario %s never began" % sid)
    if begins[0] < all_done_line:
        tr.r("TERMINAL_ORDER", "terminal scenario %s began before the aggregate/"
                               "completion markers" % sid)

    # The forbidden callback (everything after the throwing observer) is
    # checked first so the L-THROW invariant is reported precisely.
    for item in scen.get("terminal_forbidden", []):
        for e in tr.events:
            if e.get("scenario") != sid:
                continue
            if e.get("actor") != item["actor"] or e.get("event") != item["event"]:
                continue
            if "activity" in item and e.get("activity") != item["activity"]:
                continue
            tr.r("TERMINAL_FORBIDDEN",
                 "terminal scenario %s: callback %s %s ran after the throwing "
                 "callback (line %s)" % (sid, item["actor"], item["event"],
                                         e.get("_line")))

    prefix = expand_terminal_prefix(man, scen)
    actual = [event_tuple(e, FIELDS) for e in tr.events if e.get("scenario") == sid]
    exp_loose, exp_groups = group_events(prefix)
    act_loose, act_groups = group_events(actual)
    compare_groups(tr, man, "terminal scenario %s" % sid, exp_groups, act_groups)
    sequence_diff(tr, "terminal scenario %s loose events" % sid, exp_loose, act_loose)


FIELDS = ("scenario", "actor", "event", "activity", "bundle", "phase",
          "owner", "threadRole", "main")


def group_events(items):
    """Split a scenario's event stream into (loose, groups).

    A lifecycle "group" is one pre-super/post-super framing pair for a single
    (activity, event): ``pre`` and ``post`` are the framing tuples, ``cb`` the
    ordered callbacks (any event between the framing lines whose actor is not
    the framing Activity). Events outside any group - probe/application events -
    are returned as ``loose`` in stream order. Events carrying the framing
    Activity's id but neither a ``.pre`` nor a ``.post`` suffix (e.g. a probe
    ``onCreate.caught`` marker) are internal framing noise and are ignored.
    """
    loose = []
    groups = []
    cur = None
    for it in items:
        t = event_tuple(it, FIELDS) if isinstance(it, dict) else tuple(it)
        actor, ev, act = t[1], t[2], t[3]
        if actor == act and ev.endswith(".pre"):
            cur = {"key": (act, ev[:-4]), "pre": t, "cb": [], "post": None}
            groups.append(cur)
        elif cur is not None and actor == act and ev.endswith(".post"):
            cur["post"] = t
            cur = None
        elif cur is not None and actor == act:
            continue  # internal framing marker (e.g. onCreate.caught)
        elif cur is not None:
            cur["cb"].append(t)
        else:
            loose.append(t)
    return loose, groups


def _as_tuple(it):
    return event_tuple(it, FIELDS) if isinstance(it, dict) else tuple(it)


def sequence_diff(tr, label, expected, actual):
    """Reject when two ordered event-tuple streams differ, with a precise code."""
    exp = [_as_tuple(t) for t in expected]
    act = [_as_tuple(t) for t in actual]
    if act == exp:
        return
    ec, ac = Counter(exp), Counter(act)
    if ac == ec:
        tr.r("ORDER", "%s events are the right multiset but out of order" % label)
    if ac < ec:
        tr.r("MISSING_EVENT", "%s is missing %d event(s); first missing %r"
             % (label, sum((ec - ac).values()), (ec - ac).most_common(1)[0][0]))
    if ac > ec:
        tr.r("DUPLICATE_EVENT", "%s has %d extra/duplicate event(s); first extra %r"
             % (label, sum((ac - ec).values()), (ac - ec).most_common(1)[0][0]))
    n = min(len(exp), len(act))
    for i in range(n):
        if exp[i] != act[i]:
            for j, f in enumerate(FIELDS):
                if exp[i][j] != act[i][j]:
                    tr.r(_FIELD_REJECT[f], "%s event %d: %s=%r expected %r"
                         % (label, i, f, act[i][j], exp[i][j]))
    tr.r("SEMANTIC_MISMATCH", "%s event list differs (len %d vs %d)"
         % (label, len(act), len(exp)))


def _frame_check(tr, label, key, which, exp, act):
    if exp is None:
        # A truncated expected prefix (a terminal scenario that stops mid-group)
        # does not require that framing line.
        return
    if act is None:
        tr.r("MISSING_EVENT", "%s: %s.%s is missing its %s framing line"
             % (label, key[0], key[1], which))
    if act != exp:
        for j, f in enumerate(FIELDS):
            if act[j] != exp[j]:
                tr.r(_FIELD_REJECT[f], "%s: %s.%s %s: %s=%r expected %r"
                     % (label, key[0], key[1], which, f, act[j], exp[j]))


def _validate_extra_group(tr, man, label, g):
    """An ATL-only extra (activity,event) group: accept it only if well formed.

    Extra groups (e.g. the ATL extra ``ActA onStop``/``onStart`` transition
    callbacks) are not in the Android-derived manifest, so their observer set
    is dynamic; what is pinned is the callback kind/order plumbing: correct
    framing phases, and each callback event mapped to the group's lifecycle
    event with the expected Bundle null-ness.
    """
    cb = man["callback_map"]
    bmap = man.get("bundle_map", {})
    dflt = man["default_bundle"]
    if g["pre"] is None or g["post"] is None:
        tr.r("MISSING_EVENT", "%s: extra lifecycle event %s.%s lacks pre/post framing"
             % (label, g["key"][0], g["key"][1]))
    if g["pre"][5] != "pre" or g["post"][5] != "post":
        tr.r("PHASE", "%s: extra lifecycle event %s.%s has a wrong framing phase"
             % (label, g["key"][0], g["key"][1]))
    want = cb.get(g["key"][1])
    bundle = bmap.get(g["key"][1], dflt)
    for t in g["cb"]:
        if want is not None and t[2] != want:
            tr.r("WRONG_EVENT", "%s: extra lifecycle event %s.%s callback event %r "
                                "expected %r" % (label, g["key"][0], g["key"][1],
                                                 t[2], want))
        if t[4] != bundle:
            tr.r("BUNDLE", "%s: extra lifecycle event %s.%s callback Bundle %r "
                           "expected %r" % (label, g["key"][0], g["key"][1],
                                            t[4], bundle))


def compare_groups(tr, man, label, exp_groups, act_groups):
    """Enforce the per-(activity,event) callback contract.

    Every manifest group must be present, with its pre/post framing, callback
    order, multiplicity, identity, Bundle and phase all exact. Extra groups
    (the pinned ATL divergence) are accepted only if well formed.
    """
    ad = {g["key"]: g for g in act_groups}
    for g in exp_groups:
        key = g["key"]
        if key not in ad:
            tr.r("MISSING_EVENT", "%s: lifecycle event %s.%s never occurred"
                 % (label, key[0], key[1]))
        a = ad[key]
        _frame_check(tr, label, key, "pre", g["pre"], a["pre"])
        sequence_diff(tr, "%s %s.%s callbacks" % (label, key[0], key[1]),
                      g["cb"], a["cb"])
        _frame_check(tr, label, key, "post", g["post"], a["post"])
    expected_keys = {g["key"] for g in exp_groups}
    for g in act_groups:
        if g["key"] not in expected_keys:
            _validate_extra_group(tr, man, label, g)


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
        if s.get("externally_driven") or s.get("terminal"):
            # external close timing is not comparable; a terminal scenario may
            # be truncated on a crashing side (Android) but continue on the
            # other, so only its per-side prefix is compared.
            continue
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


# -------------------------------------------- cross-Activity ordering pinning -
def activity_order(tr, sid):
    """The Activity-only (pre/post) framing sequence for one scenario."""
    out = []
    for e in tr.events:
        if e.get("scenario") != sid:
            continue
        ev = e.get("event", "")
        if e.get("actor") == e.get("activity") and (ev.endswith(".pre") or ev.endswith(".post")):
            out.append((e.get("actor"), ev, e.get("activity")))
    return out


def activity_order_diff(ra, rc, man):
    """True if the candidate's cross-Activity transition ordering diverges."""
    for s in [man["preamble"]] + man["scenarios"]:
        if s.get("terminal") or s.get("externally_driven"):
            continue
        if activity_order(ra, s["id"]) != activity_order(rc, s["id"]):
            return True
    return False


def _is_observer(man, actor):
    """Observer callbacks are probe-side; dynamic observers may not be listed."""
    return actor in set(man.get("observers") or []) or str(actor).startswith("AppObs-")


def _nonobserver(tr, sid, man):
    return [event_tuple(e, FIELDS) for e in tr.events
            if e.get("scenario") == sid and not _is_observer(man, e.get("actor"))]


def compare_baseline(ra, rc, rb, man):
    """PINNED mode: pin the candidate's non-callback stream to the ATL baseline.

    The candidate's Activity transition ordering (and any other pre-existing
    ATL feature divergence) must be byte-for-byte identical to the baseline's
    for every non-terminal scenario. A scenario whose pinned stream differs
    from the Android reference is reported as an explicit DIVERGENCE.

    The candidate's callback-level lifecycle contract was already enforced by
    validate(rc, man, "relaxed").
    """
    ca = ra.json_block.get("config", {})
    cc = rc.json_block.get("config", {})
    cbase = rb.json_block.get("config", {})
    if ca != cc:
        reject("STALE", "candidate config differs from reference config (mismatched evidence)")
    if ca != cbase:
        reject("STALE", "baseline config differs from reference config (mismatched evidence)")
    if ra.header.get("run_id") == rc.header.get("run_id"):
        reject("STALE", "reference and candidate share run_id %r (not independent runs)"
               % ra.header.get("run_id"))
    if rb.header.get("run_id") == rc.header.get("run_id"):
        reject("STALE", "baseline and candidate share run_id %r (not independent runs)"
               % rb.header.get("run_id"))

    divergent = []
    for s in [man["preamble"]] + man["scenarios"]:
        sid = s["id"]
        if s.get("terminal") or s.get("externally_driven"):
            # terminal: validated per-side by validate(); external close timing
            # is not comparable.
            continue
        cn = _nonobserver(rc, sid, man)
        bn = _nonobserver(rb, sid, man)
        rn = _nonobserver(ra, sid, man)
        sequence_diff(rc, "baseline-pinned scenario %s (candidate must equal baseline)" % sid,
                      bn, cn)
        if cn != rn:
            divergent.append(sid)
    for sid in divergent:
        print("DIVERGENCE %s: candidate ordering/feature stream differs from the "
              "Android reference and is pinned byte-for-byte to the ATL baseline" % sid)
    return True


# --------------------------------------------------------------------- main ---
def main(argv):
    args = argv[1:]
    positional = []
    baseline_flag = None
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--baseline":
            if i + 1 >= len(args):
                sys.stderr.write(__doc__)
                return EXIT_ERROR
            baseline_flag = args[i + 1]
            i += 2
            continue
        if a.startswith("--baseline="):
            baseline_flag = a.split("=", 1)[1]
            i += 1
            continue
        if a.startswith("--"):
            sys.stderr.write(__doc__)
            return EXIT_ERROR
        positional.append(a)
        i += 1
    if len(positional) < 2:
        sys.stderr.write(__doc__)
        return EXIT_ERROR

    here = os.path.dirname(os.path.abspath(__file__))
    ref_path = positional[0]
    cand_path = positional[1]
    man_path = os.path.join(here, "manifest.json")
    baseline_path = baseline_flag
    for extra in positional[2:]:
        if extra.endswith(".json"):
            man_path = extra
        elif baseline_path is None:
            baseline_path = extra
        else:
            sys.stderr.write(__doc__)
            return EXIT_ERROR

    try:
        if os.environ.get("VERIFY_SELFTEST_CRASH"):
            # Injected only by run_analyzer_selftests.sh to prove that an internal
            # crash is surfaced as exit 2 / ERROR internal and is NEVER counted as
            # a semantic REJECT.
            raise RuntimeError("injected self-test crash")
        man = load_manifest(man_path)
        if baseline_path is None and man.get("baseline_trace"):
            bt = man["baseline_trace"]
            baseline_path = bt if os.path.isabs(bt) else os.path.join(
                os.path.dirname(os.path.abspath(man_path)), bt)

        ra = parse_trace(Trace(os.path.abspath(ref_path), "reference"))
        validate(ra, man, "full")
        rc = parse_trace(Trace(os.path.abspath(cand_path), "candidate"))

        if baseline_path is None:
            # STRICT mode. Fail closed if the candidate needs a baseline to be
            # judged (its cross-Activity ordering diverges from Android) but the
            # manifest says a baseline is required and none was supplied.
            if man.get("cross_activity_ordering", {}).get("baseline_required") \
                    and activity_order_diff(ra, rc, man):
                reject("MISSING_BASELINE",
                       "candidate cross-Activity ordering diverges from the Android "
                       "reference; a baseline trace is required to pin it (none supplied)")
            validate(rc, man, "full")
            compare_cross(ra, rc, man)
            print("OK all mandatory checks passed (reference and candidate validated, matched)")
            return EXIT_OK

        if not os.path.isfile(baseline_path):
            reject("MISSING_BASELINE", "baseline trace %r not found" % baseline_path)
        rb = parse_trace(Trace(os.path.abspath(baseline_path), "baseline"))
        validate(rb, man, "baseline")
        validate(rc, man, "relaxed")
        compare_baseline(ra, rc, rb, man)
        print("OK candidate validated per-activity-event; divergences pinned to baseline")
        return EXIT_OK
    except Reject as rj:
        print("REJECT %s %s" % (rj.code, rj.reason))
        return EXIT_REJECT
    except Exception as exc:  # noqa: BLE001 - never let a crash look like a reject
        import traceback
        traceback.print_exc()
        print("ERROR internal %s" % exc)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main(sys.argv))
