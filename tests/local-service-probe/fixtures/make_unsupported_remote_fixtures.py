#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""make_unsupported_remote_fixtures.py - deterministic fixtures for
verify_unsupported_remote.py (the SVC-014 remote/unsupported contract).

Reads the frozen reference evidence ``fixtures/base/android-ref.json`` (a copy of
the Android v5 probe run) and writes:

  fixtures/remote/positive/placed.json      SVC-014b with the separation proof
                                            (the service's identity reply) and a
                                            serving pid different from the client's
  fixtures/remote/negative/<case>.json      one intended-rejection mutation each
  fixtures/remote/cases.json                the case matrix used by
                                            run_unsupported_remote_selftests.sh

The positive fixture is the frozen reference plus exactly one thing the frozen
run cannot have: the ``remoteIdentity`` row the probe now records when the
client asks the connected service for its pid/process name. The frozen
reference declares the placed arm without any separation evidence, which is why
``negative/identity_missing.json`` (its shape) must be rejected: "the remote
callbacks are absent from the caller's log" is not proof that another process
served the placement.

Every write is deterministic: no timestamps, no randomness, no absolute paths
inside the documents (the case matrix carries absolute fixture paths, as
make_fixtures.py does for the analyzer's matrix).
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, "base", "android-ref.json")
OUT = os.path.join(HERE, "remote")
POS = os.path.join(OUT, "positive")
NEG = os.path.join(OUT, "negative")

RUN_ID = "candidate-remote-0001"
# The client is a different process than the service in the positive fixture.
CLIENT_PID = 1337
SERVICE_PID = 4711
REMOTE_PROCESS = "org.atl.tests.svc:remote"
IDENTITY_EVENT = "remoteIdentity"


def load():
    with open(BASE, "rb") as fh:
        return json.loads(fh.read().decode("utf-8"))


def clone(doc):
    return json.loads(json.dumps(doc))


def dump(path, doc):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, sort_keys=False)
        fh.write("\n")


def scenario(doc, name):
    for scen in doc["scenarios"]:
        if scen["scenario"] == name:
            return scen
    raise KeyError(name)


def stamp(doc):
    """Keep seq unique and the recorded event count consistent after a mutation."""
    seq = 0
    for scen in doc["scenarios"]:
        for event in scen["events"]:
            seq += 1
            event["seq"] = seq
    doc["observations"]["event_count"] = seq
    return doc


def identity_row(template, ret, exception=None, ret_type="String"):
    """The client-side separation proof row (one transaction the service answered)."""
    row = clone(template)
    row["event"] = IDENTITY_EVENT
    row["component"] = "org.atl.tests.svc/.SvcRemote"
    row["client"] = "c2"
    row["startId"] = -1
    row["flags"] = -1
    row["intentExtra"] = "none"
    row["cmd"] = ""
    row["returnValue"] = None
    row["thread"] = "main"
    row["callThread"] = "main"
    row["ret"] = ret
    row["retType"] = ret_type
    row["main"] = True
    row["exception"] = exception
    return row


def remote_events(doc):
    return scenario(doc, "SVC-014")["events"]


def remote_index(doc):
    """Index of the last SVC-014b row, so appends stay inside the scenario block."""
    events = remote_events(doc)
    index = 0
    for i, event in enumerate(events):
        if event.get("component", "").endswith("SvcRemote"):
            index = i
    return index


def insert_identity(doc, ret=None, exception=None, count=1, position="after_connect", ret_type="String"):
    """Insert the identity row(s) into SVC-014b at a chosen point."""
    events = remote_events(doc)
    ret = ret if ret is not None else \
        "pid=%d client_pid=%d process=%s" % (SERVICE_PID, CLIENT_PID, REMOTE_PROCESS)
    if position == "after_connect":
        anchor = max(i for i, e in enumerate(events) if e["event"] == "onServiceConnected") + 1
    elif position == "after_bind":
        anchor = max(i for i, e in enumerate(events) if e["event"] == "bindService") + 1
    elif position == "before_bind":
        anchor = min(i for i, e in enumerate(events) if e["event"] == "bindService")
    elif position == "after_stop":
        anchor = remote_index(doc) + 1
    else:
        raise ValueError(position)
    template = events[max(i for i, e in enumerate(events) if e["event"] == "onServiceConnected")]
    events[anchor:anchor] = [identity_row(template, ret, exception, ret_type) for _ in range(count)]
    stamp(doc)
    return doc


def make_positive(base):
    doc = clone(base)
    doc["run_id"] = RUN_ID
    for scen in doc["scenarios"]:
        for event in scen["events"]:
            if event.get("binder"):
                event["binder"] = "candidate-hash"
    insert_identity(doc)
    return doc


def refusal(doc):
    """Turn SVC-014b into the explicit-refusal arm (no client ever connected)."""
    events = remote_events(doc)
    for event in events:
        if event["component"] == "org.atl.tests.svc/.SvcRemote" and event["event"] in (
                "startService", "bindService", "stopService"):
            event["ret"] = "exception:java.lang.UnsupportedOperationException"
            event["retType"] = "exception"
            event["exception"] = "java.lang.UnsupportedOperationException"
    kept = [e for e in events
            if not (e["component"] == "org.atl.tests.svc/.SvcRemote"
                    and e["event"] == "onServiceConnected")]
    scenario(doc, "SVC-014")["events"] = kept
    stamp(doc)
    return doc


def unisolated(doc):
    """The classic silent-local case: the component's own callbacks in the caller."""
    events = remote_events(doc)
    anchor = max(i for i, e in enumerate(events) if e["event"] == "onServiceConnected") + 1
    template = events[max(i for i, e in enumerate(events) if e["event"] == "onServiceConnected")]
    rows = []
    for event_name in ("onCreate", "onStartCommand", "onBind", "onUnbind"):
        row = clone(template)
        row["event"] = event_name
        row["ret"] = None
        row["retType"] = "void"
        row["instance"] = 1
        row["binder"] = "local-hash" if event_name == "onBind" else None
        rows.append(row)
    events[anchor:anchor] = rows
    stamp(doc)
    return doc


def main():
    for path in (POS, NEG):
        os.makedirs(path, exist_ok=True)

    base = load()
    positive = make_positive(base)
    positive_path = os.path.join(POS, "placed.json")
    dump(positive_path, positive)

    cases = []

    def case(name, mutate, code):
        doc = mutate(clone(positive))
        path = os.path.join(NEG, name + ".json")
        dump(path, doc)
        cases.append({"name": name, "cand": path, "exit": 0 if code is None else 1, "code": code})

    def drop_row(doc):
        events = remote_events(doc)
        scenario(doc, "SVC-014")["events"] = [e for e in events if e["event"] != IDENTITY_EVENT]
        return stamp(doc)

    cases.append({"name": "positive", "cand": positive_path, "exit": 0, "code": None})

    # Missing separation evidence: the frozen reference's own SVC-014b shape. It
    # has the placed arm and no in-caller callbacks, and must still be rejected.
    case("identity_missing", drop_row, "IDENTITY_MISSING")
    # A placement served in the caller's own process.
    case("identity_same_pid",
         lambda d: insert_identity(drop_row(d), "pid=%d client_pid=%d process=%s"
                                  % (SERVICE_PID, SERVICE_PID, REMOTE_PROCESS)),
         "IDENTITY_SAME_PROCESS")
    # Incomplete / malformed payloads: no client pid, non-numeric pid, zero pid.
    case("identity_no_client_pid",
         lambda d: insert_identity(drop_row(d), "pid=%d process=%s" % (SERVICE_PID, REMOTE_PROCESS)),
         "IDENTITY_MALFORMED")
    case("identity_unparseable",
         lambda d: insert_identity(drop_row(d), "pid=abc client_pid=%d process=x" % CLIENT_PID),
         "IDENTITY_MALFORMED")
    case("identity_zero_pid",
         lambda d: insert_identity(drop_row(d), "pid=0 client_pid=%d process=%s"
                                  % (CLIENT_PID, REMOTE_PROCESS)),
         "IDENTITY_MALFORMED")
    case("identity_unknown_process",
         lambda d: insert_identity(drop_row(d), "pid=%d client_pid=%d process=unknown"
                                  % (SERVICE_PID, CLIENT_PID)),
         "IDENTITY_MALFORMED")
    # The identity transaction itself failed: no proof was obtained.
    case("identity_failed_call",
         lambda d: insert_identity(drop_row(d), "exception:java.lang.IllegalStateException",
                                  exception="java.lang.IllegalStateException", ret_type="exception"),
         "IDENTITY_MALFORMED")
    # Two answers: ambiguous evidence.
    case("identity_duplicate",
         lambda d: insert_identity(drop_row(d), count=2),
         "IDENTITY_DUPLICATE")
    # Answered outside the live binding (before bind / after stop).
    case("identity_before_bind",
         lambda d: insert_identity(drop_row(d), position="before_bind"),
         "IDENTITY_ORDER")
    case("identity_after_stop",
         lambda d: insert_identity(drop_row(d), position="after_stop"),
         "IDENTITY_ORDER")
    # A refusal cannot be accompanied by served work.
    case("refusal_with_identity", refusal, "REFUSAL_EVIDENCE")
    # The silent-local case the contract exists for: the component ran in the caller.
    case("unisolated_instance", unisolated, "UNISOLATED_INSTANCE")

    with open(os.path.join(OUT, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=1, sort_keys=False)
        fh.write("\n")


if __name__ == "__main__":
    main()
