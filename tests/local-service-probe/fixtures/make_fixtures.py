#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""make_fixtures.py - deterministic generator for the SVC analyzer self-tests.

Reads the frozen reference evidence ``fixtures/base/android-ref.json`` (a copy of
the Android v5 probe run) and writes:

  fixtures/refs/android-ref.json          exact reference
  fixtures/refs/android-ref-noexc.json    same, exceptions emptied
  fixtures/positive/candidate.json        valid candidate (run_id + binder only)
  fixtures/negative/<case>.json           one intended-rejection mutation each
  fixtures/cases.json                     the case matrix consumed by the driver

Every write is deterministic: no timestamps, no randomness, no absolute paths.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "base", "android-ref.json")
REFS = os.path.join(HERE, "refs")
POS = os.path.join(HERE, "positive")
NEG = os.path.join(HERE, "negative")

CAND_RUN_ID = "candidate-run-0001"


def load():
    with open(BASE, "rb") as fh:
        return json.loads(fh.read().decode("utf-8"))


def clone(doc):
    return json.loads(json.dumps(doc))


def dump(path, doc):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, sort_keys=False)
        fh.write("\n")


def dump_raw(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def scenario(doc, name):
    for scen in doc["scenarios"]:
        if scen["scenario"] == name:
            return scen
    raise KeyError(name)


def make_candidate(base):
    """A valid candidate: same evidence, fresh run_id, binder normalized away."""
    doc = clone(base)
    doc["run_id"] = CAND_RUN_ID
    for scen in doc["scenarios"]:
        for ev in scen["events"]:
            if "binder" in ev:
                ev["binder"] = "candidate-hash"
    return doc


def main():
    for d in (REFS, POS, NEG):
        os.makedirs(d, exist_ok=True)

    base = load()

    ref_path = os.path.join(REFS, "android-ref.json")
    dump(ref_path, base)

    ref_noexc = clone(base)
    ref_noexc["exceptions"] = []
    ref_noexc_path = os.path.join(REFS, "android-ref-noexc.json")
    dump(ref_noexc_path, ref_noexc)

    cand = make_candidate(base)
    pos_path = os.path.join(POS, "candidate.json")
    dump(pos_path, cand)

    def neg(name, mutate):
        doc = make_candidate(base)
        mutate(doc)
        path = os.path.join(NEG, name + ".json")
        dump(path, doc)
        return path

    cases = []

    def case(name, ref, cand_path, exit_code, code, args=None):
        cases.append({"name": name, "ref": ref, "cand": cand_path,
                      "exit": exit_code, "code": code, "args": args or []})

    case("positive", ref_path, pos_path, 0, None)

    # unparseable / empty evidence
    dump_raw(os.path.join(NEG, "empty.json"), "")
    case("empty", ref_path, os.path.join(NEG, "empty.json"), 1, "EVIDENCE")
    dump_raw(os.path.join(NEG, "unparseable.json"), "{ this is not json\n")
    case("unparseable", ref_path, os.path.join(NEG, "unparseable.json"),
         1, "EVIDENCE")

    # stale run
    case("stale", ref_path,
         neg("stale", lambda d: d.__setitem__("run_id", base["run_id"])),
         1, "STALE")

    # config mismatch
    case("config", ref_path,
         neg("config", lambda d: d["config"].__setitem__(
             "settle_ms", d["config"].get("settle_ms", 0) + 1)),
         1, "CONFIG")

    # unexpected exception (reference declares none)
    def add_exc(d):
        d["exceptions"] = ["SVC-010 probe: java.lang.RuntimeException: boom"]
    case("exceptions", ref_noexc_path, neg("exceptions", add_exc), 1,
         "EXCEPTIONS")

    # declared-vs-actual event count mismatch
    case("count", ref_path,
         neg("count", lambda d: d["observations"].__setitem__(
             "event_count", d["observations"]["event_count"] + 1)),
         1, "COUNT")

    # duplicate event
    def dup(d):
        sc = scenario(d, "SVC-005")
        sc["events"].insert(1, clone(sc["events"][0]))
        d["observations"]["event_count"] += 1
    case("duplicate", ref_path, neg("duplicate", dup), 1, "DUPLICATE")

    # incomplete/aborted scenario (present, emptied, not completed)
    def abort(d):
        sc = scenario(d, "SVC-004")
        n = len(sc["events"])
        sc["events"] = []
        d["observations"]["event_count"] -= n
        d["observations"]["scenarios_completed"].remove("SVC-004")
        d["observations"]["scenarios_observed"].remove("SVC-004")
    case("aborted", ref_path, neg("aborted", abort), 1, "ABORTED")

    # scenario missing from candidate
    def scen_missing(d):
        name = "SVC-007"
        sc = scenario(d, name)
        n = len(sc["events"])
        d["scenarios"] = [s for s in d["scenarios"] if s["scenario"] != name]
        d["observations"]["event_count"] -= n
        d["observations"]["scenarios_completed"].remove(name)
        d["observations"]["scenarios_observed"].remove(name)
    case("scenario_missing", ref_path, neg("scenario_missing", scen_missing),
         1, "SCENARIO_MISSING")

    # missing event (self-consistent count)
    def missing(d):
        sc = scenario(d, "SVC-002")
        sc["events"].pop(4)
        d["observations"]["event_count"] -= 1
    case("missing", ref_path, neg("missing", missing), 1, "MISSING")

    # truncated tail (self-consistent count)
    def truncated(d):
        sc = scenario(d, "SVC-005")
        sc["events"].pop()
        d["observations"]["event_count"] -= 1
    case("truncated", ref_path, neg("truncated", truncated), 1, "MISSING")

    # extra event (self-consistent count)
    def extra(d):
        sc = scenario(d, "SVC-005")
        max_seq = max(e.get("seq", 0)
                      for s in d["scenarios"] for e in s["events"])
        ev = clone(sc["events"][0])
        ev["seq"] = max_seq + 1
        ev["scenario"] = "SVC-005"
        sc["events"].append(ev)
        d["observations"]["event_count"] += 1
    case("extra", ref_path, neg("extra", extra), 1, "EXTRA")

    # wrong order (adjacent swap, same multiset)
    def wrong_order(d):
        ev = scenario(d, "SVC-002")["events"]
        ev[1], ev[2] = ev[2], ev[1]
    case("wrong_order", ref_path, neg("wrong_order", wrong_order), 1, "ORDER")

    # --- seq normalization (run-global counter; see verify_services.py) -------
    # (a) seq is assigned once per run across ALL scenarios, so a constant
    # shift (drift caused by any earlier scenario) must NOT reject, in
    # whole-run and in --scenarios subset mode.
    def seq_shift(d):
        for s in d["scenarios"]:
            for e in s["events"]:
                if isinstance(e.get("seq"), int):
                    e["seq"] += 7
    case("seq_shift", ref_path, neg("seq_shift", seq_shift), 0, None)
    case("seq_shift_subset", ref_path, os.path.join(NEG, "seq_shift.json"),
         0, None, ["--scenarios", "SVC-002,SVC-005"])

    # (b) two events swapped in a scenario still reject ORDER, even when the
    # run's seq numbering was rewritten (drift).
    def seq_shift_order(d):
        seq_shift(d)
        ev = scenario(d, "SVC-005")["events"]
        ev[1], ev[2] = ev[2], ev[1]
    case("seq_shift_order", ref_path, neg("seq_shift_order", seq_shift_order),
         1, "ORDER")

    # (c) a duplicated event still rejects DUPLICATE when the run's seq
    # numbering is rewritten (drift); the duplicate reuses its source seq, the
    # one structural signal that a genuine double-emission always leaves.
    def dup_rewritten_seq(d):
        seq_shift(d)
        sc = scenario(d, "SVC-005")
        sc["events"].insert(1, clone(sc["events"][0]))
        d["observations"]["event_count"] += 1
    case("dup_rewritten_seq", ref_path,
         neg("dup_rewritten_seq", dup_rewritten_seq), 1, "DUPLICATE")

    # changed field
    def changed_field(d):
        scenario(d, "SVC-002")["events"][2]["returnValue"] = 3
    case("changed_field", ref_path, neg("changed_field", changed_field),
         1, "FIELD")

    # changed owner/component
    def wrong_component(d):
        scenario(d, "SVC-002")["events"][0]["component"] = \
            "org.atl.tests.svc/.WrongOwner"
    case("wrong_component", ref_path, neg("wrong_component", wrong_component),
         1, "FIELD")

    # wrong thread
    def wrong_thread(d):
        scenario(d, "SVC-002")["events"][2]["main"] = False
    case("wrong_thread", ref_path, neg("wrong_thread", wrong_thread),
         1, "THREAD")

    # invalid number (numeric field carries a string)
    def invalid_number(d):
        scenario(d, "SVC-002")["events"][2]["startId"] = "1"
    case("invalid_number", ref_path, neg("invalid_number", invalid_number),
         1, "NUMBER")

    # --scenarios subset mode: matching selection passes on an otherwise valid
    # pair, and a selected scenario that is absent/aborted still rejects.
    case("subset_pass", ref_path, pos_path, 0, None,
         ["--scenarios", "SVC-002,SVC-005"])
    case("subset_aborted", ref_path, os.path.join(NEG, "aborted.json"), 1,
         "ABORTED", ["--scenarios", "SVC-004"])
    case("subset_scenario_missing", ref_path,
         os.path.join(NEG, "scenario_missing.json"), 1, "SCENARIO_MISSING",
         ["--scenarios", "SVC-007"])
    # Fail-closed selection errors: unknown / duplicate name -> exit 2.
    case("subset_unknown", ref_path, pos_path, 2, "-",
         ["--scenarios", "SVC-999"])
    case("subset_duplicate", ref_path, pos_path, 2, "-",
         ["--scenarios", "SVC-002,SVC-002"])
    # EXCEPTIONS guard is scoped to selected scenarios in subset mode: a
    # selected scenario's undeclared exception still rejects...
    case("subset_exceptions_selected", ref_noexc_path,
         os.path.join(NEG, "exceptions.json"), 1, "EXCEPTIONS",
         ["--scenarios", "SVC-010"])
    # ...but an undeclared exception owned by an *unselected* scenario does not
    # block the selected scenario's verdict.
    case("subset_exceptions_unscoped", ref_noexc_path,
         os.path.join(NEG, "exceptions.json"), 0, None,
         ["--scenarios", "SVC-002"])

    cases_path = os.path.join(HERE, "cases.json")
    with open(cases_path, "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=1)
        fh.write("\n")
    print("generated %d cases in %s" % (len(cases), cases_path))


if __name__ == "__main__":
    main()
