#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""verify_services.py - durable, fail-closed oracle for the LOCAL-SERVICES probe.

Usage:
    verify_services.py <android-ref.json> <candidate.json>
    verify_services.py --scenarios A,B,... <android-ref.json> <candidate.json>

Exit 0 only when every mandatory scenario's ordered event stream matches the
reference on all fields except the normalized nondeterministic ``binder``
identity and the per-run ``seq`` counter (see "Normalization" below).  A
semantic rejection prints one line ``REJECT <CODE> <detail>`` and
exits 1.  An internal error prints ``ERROR internal <detail>`` and exits 2 (a
crash is never surfaced as a semantic rejection).

``--scenarios`` selects a space-free comma list of scenario names for a
per-scenario verdict.  Without the flag the whole-run behaviour is exactly as
described above (every mandatory/reference scenario is compared).  With the
flag only the named scenarios are compared, but the mode stays fail-closed:

* an unknown name (not present in the reference, the candidate, the reference
  ``config.scenarios`` or the manifest), a repeated name, or an empty entry is a
  usage error -> ``ERROR usage ...`` on stderr, exit 2;
* a named scenario absent from either side -> ``REJECT SCENARIO_MISSING``; a
  named scenario present but emptied or absent from its run's
  ``observations.scenarios_completed``/``scenarios_observed`` -> ``REJECT
  ABORTED`` (identical to whole-run mode, so an aborted/empty *selected*
  scenario can never be masked);
* the ``EVIDENCE``/``SCHEMA``/``STALE``/``CONFIG`` guards and the
  whole-document ``DUPLICATE``/``COUNT`` integrity checks stay active;
* the ``EXCEPTIONS`` guard is scoped to the selected scenarios (an exception
  string is owned by its leading scenario token), so an undeclared exception in
  an unselected scenario does not alter the verdict, while an undeclared
  exception in a selected scenario still rejects;
* only the named scenarios' event streams are compared, so a known residual
  divergence in an *unselected* scenario does not affect the verdict.

Normalization follows the proven ad-hoc comparator
``agent-work/LOCAL-SERVICES/svc-010-016/atl/compare.py``: drop ``binder``.
The ``seq`` counter is additionally dropped from the reference/candidate
*field* comparison and from ordering, because the probe assigns it once per run
across ALL scenarios, so its literal value drifts whenever an earlier scenario's
event count differs.  Within-scenario order is already encoded by list position.
``seq`` is deliberately NOT dropped from the structural-identity key used by the
duplicate check (see ``CMP_NONDET``): the frozen reference legitimately repeats
event content that is identical except for ``seq`` (e.g. SVC-010's double
``onUnbind``), so a ``seq``-insensitive structural key would reject the reference
itself.  Exception strings are normalized only for the nondeterministic object
identity hash (``@<hex>`` -> ``@<hash>``); message text and the scenario prefix
are kept.

The reference's ``exceptions`` list is the set of *expected* exceptions; the
candidate is rejected when it carries an exception the reference does not
declare (which includes any exception when the reference declares none).
"""
import json
import os
import re
import sys
from collections import Counter

EXIT_OK = 0
EXIT_REJECT = 1
EXIT_ERROR = 2

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "service-contract.json")

# Fields carrying nondeterministic object identities -> normalized away for the
# structural-identity key (``canonical``) used by the duplicate check.
NONDET = ("binder",)
# Fields excluded from the reference/candidate *comparison* (field diff and
# ordering): ``binder`` plus the per-run ``seq`` counter, which the probe
# assigns once across all scenarios and which therefore drifts whenever an
# earlier scenario's event count differs.  ``seq`` is kept in ``NONDET``'s
# structural key on purpose (see module docstring).
CMP_NONDET = ("binder", "seq")
# Fields whose values are numeric-or-null in the frozen probe.
NUMERIC_FIELDS = ("seq", "startId", "flags", "returnValue", "instance")
THREAD_FIELDS = ("thread", "callThread", "main")

# Event keys of the frozen probe schema (order used for field-level diffing).
EVENT_KEYS = ("seq", "scenario", "component", "instance", "event", "startId",
              "flags", "intentExtra", "cmd", "returnValue", "thread",
              "callThread", "ret", "client", "binder", "retType", "main",
              "exception")

_TEXT_EXC_HASH = re.compile(r"@[0-9a-fA-F]+")


class Reject(Exception):
    def __init__(self, code, detail):
        Exception.__init__(self, "%s %s" % (code, detail))
        self.code = code
        self.detail = detail


def reject(code, detail):
    raise Reject(code, detail)


class UsageError(Exception):
    """Bad invocation (unknown/duplicate/empty --scenarios name). Never a
    semantic rejection of the candidate: reported as exit 2."""


# --------------------------------------------------------------------- load --

def load_doc(path, side):
    if not os.path.isfile(path):
        reject("EVIDENCE", "%s: no such file: %s" % (side, path))
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError as exc:
        reject("EVIDENCE", "%s: unreadable: %s (%s)" % (side, path, exc))
    if not raw.strip():
        reject("EVIDENCE", "%s: empty evidence: %s" % (side, path))
    try:
        doc = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        reject("EVIDENCE", "%s: unparseable JSON: %s (%s)" % (side, path, exc))
    if not isinstance(doc, dict):
        reject("SCHEMA", "%s: top-level JSON is not an object" % side)
    return doc


def check_schema(doc, side):
    for key in ("task_id", "probe", "run_id", "config", "observations",
                "scenarios", "exceptions"):
        if key not in doc:
            reject("SCHEMA", "%s: missing top-level key %r" % (side, key))
        if not isinstance(doc[key], (dict, list, str)) and doc[key] is not None:
            reject("SCHEMA", "%s: key %r has unusable type" % (side, key))
    if not isinstance(doc["scenarios"], list):
        reject("SCHEMA", "%s: 'scenarios' is not a list" % side)
    if not isinstance(doc["exceptions"], list):
        reject("SCHEMA", "%s: 'exceptions' is not a list" % side)
    obs = doc["observations"]
    if not isinstance(obs, dict) or "event_count" not in obs:
        reject("SCHEMA", "%s: 'observations.event_count' missing" % side)
    if not isinstance(doc["run_id"], str) or not doc["run_id"]:
        reject("SCHEMA", "%s: 'run_id' is not a non-empty string" % side)
    if not isinstance(doc["config"], dict):
        reject("SCHEMA", "%s: 'config' is not an object" % side)
    for i, scen in enumerate(doc["scenarios"]):
        if not isinstance(scen, dict):
            reject("SCHEMA", "%s: scenarios[%d] is not an object" % (side, i))
        if not isinstance(scen.get("scenario"), str):
            reject("SCHEMA", "%s: scenarios[%d].scenario missing" % (side, i))
        if not isinstance(scen.get("events"), list):
            reject("SCHEMA", "%s: scenarios[%d].events is not a list" % (side, i))
        for j, ev in enumerate(scen["events"]):
            if not isinstance(ev, dict):
                reject("SCHEMA", "%s: %s events[%d] is not an object"
                       % (side, scen["scenario"], j))


# ----------------------------------------------------------------- scenarios --

def scenario_map(doc):
    out = {}
    for scen in doc["scenarios"]:
        name = scen["scenario"]
        if name in out:
            reject("SCHEMA", "duplicate scenario block %r in run" % name)
        out[name] = scen["events"]
    return out


def normalize_event(ev):
    return {k: v for k, v in ev.items() if k not in NONDET}


def normalize_cmp(ev):
    """Event with all cross-run-nondeterministic fields removed (binder+seq).

    Used for the reference/candidate field diff and ordering; the ``seq``
    counter drifts by a run-global offset and carries no cross-run meaning.
    """
    return {k: v for k, v in ev.items() if k not in CMP_NONDET}


def canonical(ev):
    return json.dumps(normalize_event(ev), sort_keys=True, separators=(",", ":"))


def canonical_cmp(ev):
    """Structural identity for ordering: ignore ``seq`` (order != seq values)."""
    return json.dumps(normalize_cmp(ev), sort_keys=True, separators=(",", ":"))


def normalize_exception(text):
    return _TEXT_EXC_HASH.sub("@<hash>", str(text))


def exception_scenario(text):
    """Leading scenario token of an exception string (frozen probe schema:
    ``"SVC-010 unbindService(c2): ..."``). Empty if there is none."""
    parts = str(text).split(None, 1)
    return parts[0] if parts else ""


def load_manifest():
    """Optional self-contained manifest of mandatory scenarios."""
    if not os.path.isfile(MANIFEST):
        return {}
    try:
        with open(MANIFEST, "rb") as fh:
            man = json.loads(fh.read().decode("utf-8"))
    except (OSError, ValueError) as exc:
        reject("SCHEMA", "manifest: unreadable: %s" % exc)
    if not isinstance(man, dict):
        reject("SCHEMA", "manifest: not an object")
    mand = man.get("mandatory_scenarios", [])
    if not isinstance(mand, list) or any(not isinstance(s, str) for s in mand):
        reject("SCHEMA", "manifest: 'mandatory_scenarios' is not a string list")
    return man


# ------------------------------------------------------------- self-checks --

def check_duplicates(doc, smap, side):
    seen = {}
    for name, events in smap.items():
        local = set()
        for ev in events:
            key = canonical(ev)
            if key in local:
                reject("DUPLICATE",
                       "%s: duplicate event in %s (seq=%s)"
                       % (side, name, ev.get("seq")))
            local.add(key)
            seq = ev.get("seq")
            if seq is not None:
                if seq in seen:
                    reject("DUPLICATE",
                           "%s: seq %s reused (%s and %s)"
                           % (side, seq, seen[seq], name))
                seen[seq] = name


def check_count(doc, smap, side):
    declared = doc["observations"]["event_count"]
    actual = sum(len(v) for v in smap.values())
    if not isinstance(declared, int) or isinstance(declared, bool):
        reject("COUNT", "%s: observations.event_count is not an integer: %r"
               % (side, declared))
    if declared != actual:
        reject("COUNT", "%s: declared event_count=%d but %d events present"
               % (side, declared, actual))


def check_completeness(doc, smap, side, names=None):
    obs = doc["observations"]
    completed = obs.get("scenarios_completed", [])
    observed = obs.get("scenarios_observed", [])
    if not isinstance(completed, list) or not isinstance(observed, list):
        reject("SCHEMA", "%s: scenarios_completed/scenarios_observed not lists"
               % side)
    for name in (smap if names is None else names):
        if name not in completed:
            reject("ABORTED", "%s: scenario %s present but not completed"
                   % (side, name))
        if name not in observed:
            reject("ABORTED", "%s: scenario %s present but not observed"
                   % (side, name))
        if not smap[name]:
            reject("ABORTED", "%s: scenario %s aborted (no events)"
                   % (side, name))


def resolve_subset(names, ref_smap, cand_smap, ref, man):
    """Validate a --scenarios selection; fail closed on unknown/duplicate.

    The known set is the union of the reference and candidate scenario names,
    the reference ``config.scenarios`` and the manifest's mandatory scenarios.
    A name known only from the candidate is NOT unknown: it is reported by the
    normal presence check as ``SCENARIO_MISSING`` (missing from the reference).
    """
    known = set(ref_smap) | set(cand_smap)
    known |= set(ref["config"].get("scenarios", []))
    known |= set(man.get("mandatory_scenarios", []))
    out = []
    seen = set()
    for name in names:
        if name in seen:
            raise UsageError("duplicate scenario %r in --scenarios list" % name)
        if name not in known:
            raise UsageError(
                "unknown scenario %r (not in reference, candidate, config or "
                "manifest)" % name)
        seen.add(name)
        out.append(name)
    return out


# ------------------------------------------------------------------- compare --

def classify_field(key, ref_val, cand_val):
    if key in THREAD_FIELDS:
        return "THREAD"
    if key in NUMERIC_FIELDS:
        ref_num = isinstance(ref_val, int) and not isinstance(ref_val, bool)
        cand_num = isinstance(cand_val, int) and not isinstance(cand_val, bool)
        if ref_val is None or cand_val is None or not (ref_num and cand_num):
            if not (ref_num and cand_num) and ref_val != cand_val:
                return "NUMBER"
    return "FIELD"


def first_field_diff(ref_ev, cand_ev):
    ref_n = normalize_cmp(ref_ev)
    cand_n = normalize_cmp(cand_ev)
    keys = list(EVENT_KEYS)
    for k in sorted(set(ref_n) | set(cand_n)):
        if k not in keys:
            keys.append(k)
    for key in keys:
        if ref_n.get(key) != cand_n.get(key) or (key in ref_n) != (key in cand_n):
            return key, ref_n.get(key), cand_n.get(key)
    return None, None, None


def compare_scenario(name, ref_events, cand_events):
    if len(cand_events) < len(ref_events):
        reject("MISSING", "scenario %s: candidate has %d of %d reference events"
               % (name, len(cand_events), len(ref_events)))
    if len(cand_events) > len(ref_events):
        reject("EXTRA", "scenario %s: candidate has %d events, reference %d"
               % (name, len(cand_events), len(ref_events)))
    ref_n = [normalize_cmp(e) for e in ref_events]
    cand_n = [normalize_cmp(e) for e in cand_events]
    if ref_n == cand_n:
        return
    ref_multi = Counter(canonical_cmp(e) for e in ref_events)
    cand_multi = Counter(canonical_cmp(e) for e in cand_events)
    if ref_multi == cand_multi:
        for i in range(len(ref_n)):
            if ref_n[i] != cand_n[i]:
                reject("ORDER", "scenario %s: events reordered at index %d"
                       % (name, i))
    for i in range(len(ref_events)):
        if ref_n[i] == cand_n[i]:
            continue
        key, rv, cv = first_field_diff(ref_events[i], cand_events[i])
        code = classify_field(key, rv, cv)
        reject(code, "scenario %s: event[%d] field %r differs: ref=%r cand=%r"
               % (name, i, key, rv, cv))


# ---------------------------------------------------------------------- main --

def verify(ref_path, cand_path, scenarios=None):
    man = load_manifest()
    ref = load_doc(ref_path, "reference")
    cand = load_doc(cand_path, "candidate")
    check_schema(ref, "reference")
    check_schema(cand, "candidate")

    if cand["run_id"] == ref["run_id"]:
        reject("STALE", "candidate run_id %r equals reference run_id (stale run)"
               % cand["run_id"])
    if cand["config"] != ref["config"]:
        reject("CONFIG", "candidate config differs from reference config")

    ref_smap = scenario_map(ref)
    cand_smap = scenario_map(cand)

    # In subset mode the selection is validated (unknown/duplicate -> usage
    # error) and every named scenario must exist on both sides before anything
    # is compared, so an absent selected scenario is never silently skipped.
    selected = None
    if scenarios is not None:
        selected = resolve_subset(scenarios, ref_smap, cand_smap, ref, man)
        for name in selected:
            if name not in ref_smap:
                reject("SCENARIO_MISSING",
                       "scenario %s missing from reference" % name)
            if name not in cand_smap:
                reject("SCENARIO_MISSING",
                       "scenario %s missing from candidate" % name)

    # Exception strings are prefixed with the owning scenario name (see the
    # frozen probe schema).  In subset mode only exceptions owned by a selected
    # scenario participate, so an undeclared exception in an *unselected*
    # scenario cannot mask/alter a selected verdict; a selected scenario's
    # undeclared exception still rejects.
    ref_exc = Counter(normalize_exception(e) for e in ref["exceptions"])
    cand_exc = Counter(normalize_exception(e) for e in cand["exceptions"])
    scope = None if selected is None else set(selected)
    for text, n in cand_exc.items():
        if scope is not None and exception_scenario(text) not in scope:
            continue
        if n > ref_exc.get(text, 0):
            reject("EXCEPTIONS",
                   "candidate has unexpected exception: %s" % text)

    # Whole-document integrity: always over every scenario (never masks a
    # selected problem; a globally corrupt run is rejected either way).
    check_duplicates(ref, ref_smap, "reference")
    check_count(ref, ref_smap, "reference")
    check_completeness(ref, ref_smap, "reference", names=selected)

    check_duplicates(cand, cand_smap, "candidate")
    check_count(cand, cand_smap, "candidate")
    check_completeness(cand, cand_smap, "candidate", names=selected)

    if selected is None:
        mandatory = set(ref_smap)
        mandatory |= set(ref["config"].get("scenarios", []))
        mandatory |= set(man.get("mandatory_scenarios", []))
        for name in sorted(mandatory):
            if name not in cand_smap:
                reject("SCENARIO_MISSING", "scenario %s missing from candidate"
                       % name)
        for name in sorted(set(cand_smap) - set(ref_smap)):
            reject("SCENARIO_EXTRA", "candidate has undeclared scenario %s"
                   % name)
        selected = sorted(ref_smap)

    for name in selected:
        compare_scenario(name, ref_smap[name], cand_smap[name])

    total = sum(len(ref_smap[n]) for n in selected)
    if scenarios is None:
        print("OK %d mandatory scenarios, %d events match (binder+seq "
              "normalized)" % (len(selected), total))
    else:
        print("OK subset %s: %d scenarios, %d events match (binder+seq "
              "normalized)" % (",".join(selected), len(selected), total))
    return EXIT_OK


def parse_scenarios(text):
    """Split a space-free comma list, rejecting empty entries."""
    names = [tok.strip() for tok in text.split(",")]
    for name in names:
        if not name:
            raise UsageError("empty scenario name in --scenarios %r" % text)
    return names


def main(argv):
    args = argv[1:]
    scenarios = None
    positional = []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == "--scenarios":
            if scenarios is not None:
                sys.stderr.write("ERROR usage duplicate --scenarios option\n")
                return EXIT_ERROR
            if i + 1 >= len(args):
                sys.stderr.write("ERROR usage --scenarios needs a value\n")
                return EXIT_ERROR
            scenarios = args[i + 1]
            i += 2
        elif arg.startswith("--scenarios="):
            if scenarios is not None:
                sys.stderr.write("ERROR usage duplicate --scenarios option\n")
                return EXIT_ERROR
            scenarios = arg.split("=", 1)[1]
            i += 1
        elif arg.startswith("--"):
            sys.stderr.write("ERROR usage unknown option %s\n" % arg)
            return EXIT_ERROR
        else:
            positional.append(arg)
            i += 1
    if len(positional) != 2:
        sys.stderr.write("usage: verify_services.py [--scenarios A,B,...] "
                         "<android-ref.json> <candidate.json>\n")
        return EXIT_ERROR
    try:
        names = None if scenarios is None else parse_scenarios(scenarios)
        return verify(positional[0], positional[1], names)
    except UsageError as exc:
        sys.stderr.write("ERROR usage %s\n" % exc)
        return EXIT_ERROR
    except Reject as rej:
        print("REJECT %s %s" % (rej.code, rej.detail))
        return EXIT_REJECT
    except Exception as exc:  # noqa: BLE001 - crash must never look like REJECT
        sys.stderr.write("ERROR internal %s: %s\n" % (type(exc).__name__, exc))
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main(sys.argv))
