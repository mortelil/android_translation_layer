#!/usr/bin/env python3
"""make_fixtures.py - deterministically build the lifecycle/fragment verifier fixture matrix.

The REFERENCE side is the v2.1 canonical trace (``fixtures/reference-v2.1.trace``),
generated deterministically from the CONTRACT-derived ``manifest.json`` through
the verifier's own ``expand_expected``/``expand_terminal_prefix`` expansion. It is
*not* observed ATL or Android output: the pinned framework contract plus the
frozen probe script fully determine it. The original REAL Android probe-v1 trace
is preserved unchanged as ``fixtures/reference-android.trace`` (sha256
6c3ab16255f65a88f3a9af283616f729d46f5fe298f81a166e03be0507146ff0) and is used by
``reference-v1-trace-invalid`` to prove that v1 evidence is not accepted against
the v2.1 manifest.

The reference ends with the TERMINAL L-THROW scenario (its required prefix plus
the post-throw lines), emitted after ``LIFE_ALL_DONE`` exactly as the probe does.

Every negative fixture is a targeted, minimal mutation of the reference trace, so
a rejection is attributable to exactly one deviation. The candidate side of the
accepting cases is the same evidence re-run under a distinct run_id (the only
legal per-run difference).

Writes every fixture trace plus ``cases.json`` (name -> inputs, expected exit and
expected REJECT code) under ``fixtures/``. Idempotent: previously generated
fixtures are removed first (``reference-android.trace`` is preserved).
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
MANIFEST = os.path.join(HERE, "manifest.json")

sys.path.insert(0, HERE)
import verify_lifecycle as V  # noqa: E402 - shared expectation expansion (single source of truth)

REF = os.path.join(FIX, "reference-v2.1.trace")
REAL_V1 = os.path.join(FIX, "reference-android.trace")

EVT = "LIFE_EVT "


def read_lines(path):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    if text.endswith("\n"):
        text = text[:-1]
    return text.split("\n")


def write_lines(name, lines):
    path = os.path.join(FIX, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return path


def write_raw(name, text):
    path = os.path.join(FIX, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def parse_evt(line):
    assert line.startswith(EVT), line
    return json.loads(line[len(EVT):])


def render_evt(obj):
    obj = {k: v for k, v in obj.items() if k != "_line"}
    return EVT + json.dumps(obj, separators=(",", ":"))


def evt_indices(lines):
    return [i for i, ln in enumerate(lines) if ln.startswith(EVT)]


def find_evt(lines, pred):
    for i in evt_indices(lines):
        if pred(parse_evt(lines[i])):
            return i
    raise AssertionError("no event matched")


def mutate_evt(lines, pred, fn):
    i = find_evt(lines, pred)
    obj = parse_evt(lines[i])
    fn(obj)
    lines[i] = render_evt(obj)
    return lines


def renumber(lines):
    k = 0
    for i in evt_indices(lines):
        obj = parse_evt(lines[i])
        k += 1
        obj["seq"] = k
        lines[i] = render_evt(obj)
    return lines


def json_line_index(lines):
    b = lines.index("LIFE_JSON_BEGIN")
    e = lines.index("LIFE_JSON_END")
    assert e == b + 2, (b, e)  # single aggregate line
    return b + 1


def mutate_json(lines, fn):
    i = json_line_index(lines)
    obj = json.loads(lines[i])
    fn(obj)
    lines[i] = json.dumps(obj, separators=(",", ":"))
    return lines


def scen_span(lines, sid):
    b = lines.index("LIFE_SCEN_BEGIN " + sid)
    try:
        e = lines.index("LIFE_SCEN_END " + sid)
    except ValueError:
        e = next(i for i in range(b + 1, len(lines))
                 if lines[i].startswith("LIFE_SCEN_BEGIN"))
    return b, e


def rewrite_run_id(lines, new):
    for i, ln in enumerate(lines):
        if ln.startswith("LIFE_TRACE_BEGIN"):
            lines[i] = ln.replace(
                "run_id=" + parse_header(ln)["run_id"], "run_id=" + new)
    return mutate_json(lines, lambda o: o.__setitem__("run_id", new))


def parse_header(line):
    out = {}
    for tok in line.split()[1:]:
        k, v = tok.split("=", 1)
        out[k] = v
    return out


def build_reference(man, run="lv21-ref-0001", transform=None):
    """Deterministically generate a canonical v2.1 trace from the manifest.

    ``run`` sets the run_id; ``transform(man, sid, tuples) -> tuples`` may
    rewrite a scenario's expected event tuples before rendering (used to build
    fixture candidates with a fixed, deterministic divergence). The aggregate
    JSON is rendered from the same tuple stream, so it stays consistent.
    """
    ident = man["identity"]
    lines = ["LIFE_TRACE_BEGIN task_id=%s run_id=%s package=%s probe=%s"
             % (ident["task_id"], run, ident["package"], ident["probe"])]
    events = []
    state = {"seq": 0}

    def emit(t):
        scen, actor, event, activity, bundle, phase, owner, role, main = t
        state["seq"] += 1
        obj = {"k": "evt", "seq": state["seq"], "scenario": scen, "actor": actor,
               "event": event, "activity": activity, "bundle": bundle,
               "phase": phase, "owner": owner, "thread": "main",
               "threadRole": role, "main": main}
        events.append(obj)
        return EVT + json.dumps(obj, separators=(",", ":"))

    for t in V.expand_expected(man, man["preamble"]):
        lines.append(emit(t))

    ended = []
    for s in man["scenarios"]:
        if s.get("externally_driven"):
            continue  # S3D2 begins after the aggregate JSON, ends on external close
        if s.get("terminal"):
            continue  # L-THROW begins after LIFE_ALL_DONE (see below)
        lines.append("LIFE_SCEN_BEGIN " + s["id"])
        tuples = V.expand_expected(man, s)
        if transform is not None:
            tuples = transform(man, s["id"], tuples)
        for t in tuples:
            lines.append(emit(t))
        lines.append("LIFE_SCEN_END " + s["id"])
        ended.append(s["id"])

    cfg = {
        "package": ident["package"],
        "application": "org.atl.tests.lifecycle.ProbeApp",
        "main_activity": "org.atl.tests.lifecycle.ActA",
        "secondary_activity": "org.atl.tests.lifecycle.ActB",
        "observers": ["AppObs-A", "AppObs-B", "AppObs-C", "AppObs-MUT", "AppObs-VICT",
                      "AppObs-REG", "AppObs-NEW", "AppObs-THROW", "AppObs-POST",
                      "AppObs-EQ1", "AppObs-EQ2"],
        "scenarios": [s["id"] for s in man["scenarios"]],
        "externally_driven": ["S3D2"],
        "intent_extras": False,
        "launch": "launcher",
    }
    agg = {
        "task_id": ident["task_id"],
        "probe": ident["probe"],
        "run_id": run,
        "config": cfg,
        "observations": {
            "scenarios_completed": ended,
            "awaiting_external_close": True,
            "finish_activity_for_d1": "ActB (ActA kept resumed for S3D2)",
        },
        "events": events,
        "exceptions": [],
    }
    lines.append("LIFE_JSON_BEGIN")
    lines.append(json.dumps(agg, separators=(",", ":")))
    lines.append("LIFE_JSON_END")
    lines.append("LIFE_SCEN_BEGIN S3D2")
    lines.append("LIFE_ALL_DONE")
    # TERMINAL scenario: after the aggregate + completion marker. Its required
    # prefix is followed by the two lines ActB emits after it catches the throw
    # (onCreate.caught/.post) and then the reliable completion signal. The
    # canonical reference is crash-free; the REAL Android trace appends a
    # LIFE_EXCEPTION (SuperNotCalledException) after this point.
    for s in man["scenarios"]:
        if not s.get("terminal"):
            continue
        lines.append("LIFE_SCEN_BEGIN " + s["id"])
        for t in V.expand_terminal_prefix(man, s):
            lines.append(emit(t))
        for event, bundle, phase in (("onCreate.caught", "-", "caught"),
                                     ("onCreate.post", "null", "post")):
            lines.append(emit((s["id"], "ActB#11", event, "ActB#11", bundle, phase,
                               man["owner"], man["main_thread_role"], True)))
        mk = man.get("terminal_marker")
        if mk:
            lines.append(mk)
    return lines


def main():
    os.makedirs(FIX, exist_ok=True)
    # Remove previously generated fixtures so a renamed/retired fixture cannot
    # linger and be mistaken for current evidence. The REAL Android probe-v1
    # trace is an input, not a generated fixture, so it is preserved.
    for path in glob.glob(os.path.join(FIX, "*.trace")) + [
            os.path.join(FIX, "cases.json")]:
        if os.path.abspath(path) == os.path.abspath(REAL_V1):
            continue
        try:
            os.remove(path)
        except OSError:
            pass
    man = V.load_manifest(MANIFEST)
    real = build_reference(man)
    write_lines("reference-v2.1.trace", real)
    real_run = parse_header(real[0])["run_id"]
    assert real_run == "lv21-ref-0001"
    cases = []

    def add(name, ref, cand, exit_code, code, manifest=None, env=None, baseline=None):
        expect = "accept" if exit_code == 0 else ("internal" if exit_code == 2 else "reject")
        cases.append({
            "name": name,
            "reference": ref,
            "candidate": cand,
            "manifest": manifest or MANIFEST,
            "baseline": baseline or "",
            "exit": exit_code,
            "code": code,
            "expect": expect,
            "env": env or "",
        })

    # ---- the VALID matching pair (must exit 0) -----------------------------
    valid_cand = rewrite_run_id(list(real), "lv21-cand-0001")
    cand_path = write_lines("valid-candidate.trace", valid_cand)
    add("valid-matching", REF, cand_path, 0, None)

    # ---- regression: both logs empty (the reported false-PASS) -------------
    empty = write_raw("empty.trace", "")
    add("regression-both-empty", empty, empty, 1, "EMPTY_EVIDENCE")

    # ---- either log empty --------------------------------------------------
    add("reference-empty", empty, cand_path, 1, "EMPTY_EVIDENCE")
    add("candidate-empty", REF, empty, 1, "EMPTY_EVIDENCE")

    # ---- missing log file --------------------------------------------------
    add("reference-missing-file", os.path.join(FIX, "does-not-exist.trace"),
        cand_path, 1, "MISSING_EVIDENCE")

    # ---- malformed line ----------------------------------------------------
    m = list(real)
    m.insert(1, "THIS IS NOT A TRACE LINE")
    add("reference-malformed-line", write_lines("malformed-line.trace", m),
        cand_path, 1, "MALFORMED_LINE")

    # ---- truncated line ----------------------------------------------------
    full = "\n".join(real) + "\n"
    cut = len(full) // 3
    truncated = full[:cut].rstrip("\n")
    add("reference-truncated", write_raw("truncated.trace", truncated),
        cand_path, 1, "TRUNCATED")

    # ---- missing mandatory scenario ---------------------------------------
    m = list(real)
    b, e = scen_span(m, "S4.4")
    del m[b:e + 1]
    renumber(m)
    add("reference-missing-scenario", write_lines("missing-scenario.trace", m),
        cand_path, 1, "MISSING_SCENARIO")

    # ---- scenario order ----------------------------------------------------
    m = list(real)
    b1, e1 = scen_span(m, "S4.4")
    b2, e2 = scen_span(m, "S4.5")
    assert e1 < b2
    m = m[:b1] + m[b2:e2 + 1] + m[b1:e1 + 1] + m[e2 + 1:]
    add("reference-scenario-order", write_lines("scenario-order.trace", m),
        cand_path, 1, "SCENARIO_ORDER")

    # ---- wrong scenario identity ------------------------------------------
    m = list(real)
    b, e = scen_span(m, "S4.5")
    for i in range(b, e + 1):
        m[i] = m[i].replace("S4.5", "S4.8")
    for i in evt_indices(m):
        obj = parse_evt(m[i])
        if obj.get("scenario") == "S4.5":
            obj["scenario"] = "S4.8"
            m[i] = render_evt(obj)
    add("reference-scenario-identity", write_lines("scenario-identity.trace", m),
        cand_path, 1, "SCENARIO_IDENTITY")

    # ---- absent completion marker -----------------------------------------
    m = [ln for ln in real if ln != "LIFE_ALL_DONE"]
    add("reference-no-completion", write_lines("no-completion.trace", m),
        cand_path, 1, "NO_COMPLETION")

    # ---- interrupted run (missing end marker) -----------------------------
    m = [ln for ln in real if ln != "LIFE_SCEN_END S4.5"]
    add("reference-interrupted", write_lines("interrupted.trace", m),
        cand_path, 1, "INTERRUPTED")

    # ---- interrupted run (recorded exception) -----------------------------
    m = list(real)
    m.insert(m.index("LIFE_ALL_DONE"),
             "LIFE_EXCEPTION java.lang.RuntimeException: injected")
    add("reference-interrupted-exception",
        write_lines("interrupted-exception.trace", m), cand_path, 1, "INTERRUPTED")

    # ---- terminal scenario: the required prefix must be present ------------
    m = list(real)
    i = find_evt(m, lambda o: o["scenario"] == "L-THROW"
                 and o["actor"] == "AppObs-THROW"
                 and o["event"] == "onActivityCreated")
    del m[i:]  # trace ends before the throwing callback is recorded
    renumber(m)
    add("reference-terminal-missing-prefix",
        write_lines("terminal-missing-prefix.trace", m), cand_path, 1, "MISSING_EVENT")

    # ---- terminal scenario must begin AFTER the aggregate/completion -------
    m = list(real)
    b = m.index("LIFE_SCEN_BEGIN L-THROW")
    block = m[b:]
    del m[b:]
    at = m.index("LIFE_ALL_DONE")
    m = m[:at] + block + m[at:]
    add("reference-terminal-preaggregate",
        write_lines("terminal-preaggregate.trace", m), cand_path, 1, "TERMINAL_ORDER")

    # ---- a broken aggregate still rejects even with a terminal scenario ----
    m = [ln for ln in real
         if ln not in ("LIFE_JSON_BEGIN", "LIFE_JSON_END")
         and not ln.startswith("{\"task_id\"")]
    add("reference-terminal-broken-aggregate",
        write_lines("terminal-broken-aggregate.trace", m), cand_path, 1, "INTERRUPTED")

    # ---- the terminal scenario is mandatory (its begin must be present) ----
    m = [ln for ln in real
         if ln != "LIFE_SCEN_BEGIN L-THROW"
         and not (ln.startswith(EVT) and parse_evt(ln).get("scenario") == "L-THROW")]
    add("reference-terminal-missing-scenario",
        write_lines("terminal-missing-scenario.trace", m),
        cand_path, 1, "SCENARIO_MULTIPLICITY")

    # ---- missing mandatory field ------------------------------------------
    m = list(real)
    mutate_evt(m, lambda o: o["event"] == "onActivityCreated", lambda o: o.pop("owner"))
    add("reference-missing-field", write_lines("missing-field.trace", m),
        cand_path, 1, "MISSING_FIELD")

    # ---- wrong APK identity ------------------------------------------------
    m = list(real)
    m[0] = m[0].replace("package=org.atl.tests.lifecycle", "package=org.atl.tests.other")
    add("reference-apk-identity", write_lines("apk-identity.trace", m),
        cand_path, 1, "APK_IDENTITY")

    # ---- wrong run identity (header vs aggregate) -------------------------
    m = mutate_json(list(real), lambda o: o.__setitem__("run_id", "lv21-forged-9"))
    add("reference-run-identity", write_lines("run-identity.trace", m),
        cand_path, 1, "RUN_IDENTITY")

    # ---- wrong numeric field: non-int type --------------------------------
    m = mutate_evt(list(real), lambda o: o["seq"] == 7, lambda o: o.__setitem__("seq", "7"))
    add("reference-bad-type", write_lines("bad-type.trace", m),
        cand_path, 1, "BAD_TYPE")

    # ---- wrong numeric field: non-contiguous seq --------------------------
    m = mutate_evt(list(real), lambda o: o["seq"] == 10, lambda o: o.__setitem__("seq", 500))
    add("reference-wrong-numeric", write_lines("wrong-numeric.trace", m),
        cand_path, 1, "SEQ")

    # ---- nonfinite where a finite integer is required ---------------------
    m = mutate_evt(list(real), lambda o: o["seq"] == 12,
                   lambda o: o.__setitem__("seq", float("inf")))
    add("reference-nonfinite", write_lines("nonfinite.trace", m),
        cand_path, 1, "NONFINITE")

    # ---- wrong owner -------------------------------------------------------
    m = mutate_evt(list(real), lambda o: o["event"] == "onActivityStarted",
                   lambda o: o.__setitem__("owner", "OtherApp"))
    add("reference-wrong-owner", write_lines("wrong-owner.trace", m),
        cand_path, 1, "OWNER")

    # ---- wrong thread ------------------------------------------------------
    m = mutate_evt(list(real), lambda o: o["event"] == "onActivityPaused",
                   lambda o: (o.__setitem__("main", False),
                              o.__setitem__("threadRole", "other")))
    add("reference-wrong-thread", write_lines("wrong-thread.trace", m),
        cand_path, 1, "THREAD")

    # ---- the REAL Android probe-v1 trace is not valid v2 evidence ----------
    add("reference-v1-trace-invalid", REAL_V1, cand_path, 1, "APK_IDENTITY")

    # ======================= candidate-side semantics =======================
    base = list(real)

    def cand(name, mut, exit_code, code):
        lines = mut(rewrite_run_id(list(base), "lv21-cand-" + name))
        add(name, REF, write_lines(name + ".trace", lines), exit_code, code)

    # ---- missing event -----------------------------------------------------
    def drop_callback(lines, evt_name, actor, activity):
        i = find_evt(lines, lambda o: o["event"] == evt_name and o["actor"] == actor
                     and o["activity"] == activity)
        del lines[i]

    def m_missing(lines):
        drop_callback(lines, "onActivityCreated", "AppObs-B", "ActA#1")
        return renumber(lines)
    cand("candidate-missing-event", m_missing, 1, "MISSING_EVENT")

    # ---- extra duplicate ---------------------------------------------------
    def m_duplicate(lines):
        i = find_evt(lines, lambda o: o["event"] == "onActivityStarted"
                     and o["actor"] == "AppObs-B" and o["activity"] == "ActB#1")
        lines.insert(i + 1, lines[i])
        return renumber(lines)
    cand("candidate-duplicate-event", m_duplicate, 1, "DUPLICATE_EVENT")

    # ---- invalid ordering --------------------------------------------------
    def m_ordering(lines):
        a = find_evt(lines, lambda o: o["event"] == "onActivityCreated"
                     and o["actor"] == "AppObs-A" and o["activity"] == "ActA#1")
        b = find_evt(lines, lambda o: o["event"] == "onActivityCreated"
                     and o["actor"] == "AppObs-B" and o["activity"] == "ActA#1")
        assert b == a + 1
        lines[a], lines[b] = lines[b], lines[a]
        return renumber(lines)
    cand("candidate-ordering", m_ordering, 1, "ORDER")

    # ---- wrong instance / association -------------------------------------
    def m_misassoc(lines):
        return mutate_evt(lines, lambda o: o["event"] == "onActivityCreated"
                          and o["actor"] == "AppObs-A",
                          lambda o: o.__setitem__("activity", "ActA#9"))
    cand("candidate-wrong-instance", m_misassoc, 1, "MISASSOC")

    # ---- wrong Bundle association -----------------------------------------
    def m_bundle(lines):
        return mutate_evt(lines, lambda o: o["event"] == "onActivityCreated"
                          and o["bundle"] == "null",
                          lambda o: o.__setitem__("bundle", "-"))
    cand("candidate-wrong-bundle", m_bundle, 1, "BUNDLE")

    # ---- wrong callback event ---------------------------------------------
    def m_wrong_event(lines):
        return mutate_evt(lines, lambda o: o["event"] == "onActivityResumed",
                          lambda o: o.__setitem__("event", "onActivityStarted"))
    cand("candidate-wrong-event", m_wrong_event, 1, "WRONG_EVENT")

    # ---- wrong phase -------------------------------------------------------
    def m_phase(lines):
        return mutate_evt(lines, lambda o: o["event"] == "onResume.pre",
                          lambda o: o.__setitem__("phase", "post"))
    cand("candidate-wrong-phase", m_phase, 1, "PHASE")

    # ---- stale / mismatched evidence (config differs from reference) ------
    def m_stale_config(lines):
        return mutate_json(lines, lambda o: o["config"].__setitem__(
            "observers", ["AppObs-A", "AppObs-B", "AppObs-C", "AppObs-X"]))
    cand("candidate-stale-config", m_stale_config, 1, "STALE")

    # ---- stale: candidate is actually the reference run -------------------
    add("stale-same-run", REF, write_lines("same-run.trace", list(real)), 1, "STALE")

    # ---- event with an unknown scenario field -----------------------------
    def m_event_scenario(lines):
        return mutate_evt(lines, lambda o: o["event"] == "onActivityCreated"
                          and o["actor"] == "AppObs-A",
                          lambda o: o.__setitem__("scenario", "S9"))
    cand("candidate-event-scenario", m_event_scenario, 1, "EVENT_SCENARIO")

    # ---- event placed outside its scenario span ---------------------------
    def m_misplaced(lines):
        i = find_evt(lines, lambda o: o["event"] == "onResume.post"
                     and o["scenario"] == "S1")
        line = lines.pop(i)
        end = lines.index("LIFE_SCEN_END S1")
        lines.insert(end + 1, line)  # now after the S1 end marker, still labelled S1
        return renumber(lines)
    cand("candidate-misplaced-event", m_misplaced, 1, "MISPLACED_EVENT")

    # ---- GAP-1 regression: duplicate LIFE_SCEN_END for a scenario whose
    #      manifest multiplicity is end:1 (was accepted before hardening) ----
    def m_dup_end(lines):
        i = lines.index("LIFE_SCEN_END S1")
        lines.insert(i + 1, "LIFE_SCEN_END S1")
        return lines
    cand("candidate-duplicate-scen-end", m_dup_end, 1, "SCENARIO_MULTIPLICITY")

    # ---- GAP-2 regression: aggregate JSON claims zero events while the
    #      LIFE_EVT line stream is complete (was accepted before hardening) --
    def m_json_events_empty(lines):
        return mutate_json(lines, lambda o: o.__setitem__("events", []))
    cand("candidate-json-events-empty", m_json_events_empty, 1, "JSON_MISMATCH")

    # ==================== v2 contract-specific negatives ====================

    def insert_after(lines, pred, obj):
        i = find_evt(lines, pred)
        lines.insert(i + 1, render_evt(obj))
        return lines

    # ---- L-SNAP: the victim removed during dispatch must still run once ----
    def m_lsnap_victim_dropped(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-SNAP"
                     and o["actor"] == "AppObs-VICT"
                     and o["event"] == "onActivityCreated")
        del lines[i]
        return renumber(lines)
    cand("candidate-lsnap-victim-dropped", m_lsnap_victim_dropped, 1, "MISSING_EVENT")

    # ---- L-SNAP: the removed victim must NOT run for later events ----------
    def m_lsnap_victim_leaks(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-SNAP"
                     and o["actor"] == "AppObs-MUT"
                     and o["event"] == "onActivityStarted")
        obj = parse_evt(lines[i])
        obj["actor"] = "AppObs-VICT"
        lines.insert(i + 1, render_evt(obj))
        return renumber(lines)
    cand("candidate-lsnap-victim-leaks", m_lsnap_victim_leaks, 1, "DUPLICATE_EVENT")

    # ---- L-SNAP: snapshot order (mutator, its action, then victim) --------
    def m_lsnap_order(lines):
        a = find_evt(lines, lambda o: o["scenario"] == "L-SNAP"
                     and o["actor"] == "AppObs-MUT"
                     and o["event"] == "unregisterCallback")
        b = find_evt(lines, lambda o: o["scenario"] == "L-SNAP"
                     and o["actor"] == "AppObs-VICT")
        lines[a], lines[b] = lines[b], lines[a]
        return renumber(lines)
    cand("candidate-lsnap-order", m_lsnap_order, 1, "ORDER")

    # ---- L-REGD: a callback added during dispatch must not run in-flight --
    def m_lregd_new_in_snapshot(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-REGD"
                     and o["actor"] == "AppObs-REG"
                     and o["event"] == "registerCallback")
        obj = parse_evt(lines[i])
        obj["actor"] = "AppObs-NEW"
        obj["event"] = "onActivityCreated"
        obj["activity"] = "ActB#9"
        obj["bundle"] = "null"
        lines.insert(i + 1, render_evt(obj))
        return renumber(lines)
    cand("candidate-lregd-new-in-snapshot", m_lregd_new_in_snapshot, 1, "DUPLICATE_EVENT")

    # ---- L-THROW (terminal): the callback after the throw must be skipped --
    def m_lthrow_post_invoked(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-THROW"
                     and o["actor"] == "AppObs-THROW"
                     and o["event"] == "onActivityCreated")
        obj = parse_evt(lines[i])
        obj["actor"] = "AppObs-POST"
        lines.insert(i + 1, render_evt(obj))
        return renumber(lines)
    cand("candidate-lthrow-post-invoked", m_lthrow_post_invoked, 1, "TERMINAL_FORBIDDEN")

    # ---- L-THROW (terminal): the required prefix must be complete ----------
    def m_lthrow_missing_prefix(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-THROW"
                     and o["actor"] == "AppObs-THROW"
                     and o["event"] == "onActivityCreated")
        del lines[i:]  # trace ends before the throwing callback is recorded
        return renumber(lines)
    cand("candidate-lthrow-missing-prefix", m_lthrow_missing_prefix, 1, "MISSING_EVENT")

    # ---- L-THROW (terminal): prefix instance must match the manifest --------
    def m_lthrow_wrong_instance(lines):
        return mutate_evt(lines, lambda o: o["scenario"] == "L-THROW"
                          and o["actor"] == "AppObs-THROW"
                          and o["event"] == "onActivityCreated",
                          lambda o: o.__setitem__("activity", "ActB#9"))
    cand("candidate-lthrow-wrong-instance", m_lthrow_wrong_instance, 1, "MISASSOC")

    # ---- L-EQUALS: the equal-but-distinct instance removed obsEq1 ---------
    def m_lequals_eq1_not_removed(lines):
        i = find_evt(lines, lambda o: o["scenario"] == "L-EQUALS"
                     and o["actor"] == "AppObs-A"
                     and o["event"] == "onActivityCreated")
        obj = parse_evt(lines[i])
        obj["actor"] = "AppObs-EQ1"
        lines.insert(i + 1, render_evt(obj))
        return renumber(lines)
    cand("candidate-lequals-eq1-not-removed", m_lequals_eq1_not_removed, 1, "DUPLICATE_EVENT")

    # ---- F-DUP-TAG: newest-added fragment must win the reverse scan -------
    def m_fdup_newest(lines):
        return mutate_evt(lines, lambda o: o["event"] == "find:tdup=frag#3",
                          lambda o: o.__setitem__("event", "find:tdup=frag#2"))
    cand("candidate-fdup-newest", m_fdup_newest, 1, "WRONG_EVENT")

    # ---- F-PRE-COMMIT: lookup before executePendingTransactions is null ----
    def m_fprecommit_early(lines):
        return mutate_evt(lines, lambda o: o["event"] == "find:tpre=null",
                          lambda o: o.__setitem__("event", "find:tpre=frag#4"))
    cand("candidate-fprecommit-early", m_fprecommit_early, 1, "WRONG_EVENT")

    # ---- F-REMOVE: a removed fragment must be purged from mActive ---------
    def m_fremove_active(lines):
        return mutate_evt(lines, lambda o: o["event"] == "find:t1=null",
                          lambda o: o.__setitem__("event", "find:t1=frag#1"))
    cand("candidate-fremove-active", m_fremove_active, 1, "WRONG_EVENT")

    # ---- F-NULL-MISS: an unknown tag must not resolve ---------------------
    def m_fnullmiss_hit(lines):
        return mutate_evt(lines, lambda o: o["event"] == "find:missing=null",
                          lambda o: o.__setitem__("event", "find:missing=frag#5"))
    cand("candidate-fnullmiss-hit", m_fnullmiss_hit, 1, "WRONG_EVENT")

    # ---- F-PRE-COMMIT: commit must precede the post-commit lookup ---------
    def m_ft_order(lines):
        a = find_evt(lines, lambda o: o["scenario"] == "F-PRE-COMMIT"
                     and o["event"] == "find:tpre=null")
        b = find_evt(lines, lambda o: o["scenario"] == "F-PRE-COMMIT"
                     and o["event"] == "commit")
        assert b == a + 1
        lines[a], lines[b] = lines[b], lines[a]
        return renumber(lines)
    cand("candidate-ft-order", m_ft_order, 1, "ORDER")

    # ---- wrong activity instance tag in a v2 scenario ---------------------
    def m_v2_misassoc(lines):
        return mutate_evt(lines, lambda o: o["scenario"] == "L-EQUALS"
                          and o["actor"] == "AppObs-A"
                          and o["event"] == "onActivityCreated",
                          lambda o: o.__setitem__("activity", "ActB#9"))
    cand("candidate-v2-wrong-instance", m_v2_misassoc, 1, "MISASSOC")

    # ---- VALID complete run including the external close (S3D2) -----------
    ext = rewrite_run_id(list(real), "lv21-cand-extclose")
    seq = len(evt_indices(ext))

    def ev(actor, name, phase):
        nonlocal seq
        seq += 1
        return render_evt({
            "k": "evt", "seq": seq, "scenario": "S3D2", "actor": actor,
            "event": name, "activity": "ActA#1", "bundle": "-", "phase": phase,
            "owner": "ProbeApp", "thread": "main", "threadRole": "main", "main": True})

    for base, cbname in (("onPause", "onActivityPaused"),
                         ("onStop", "onActivityStopped"),
                         ("onDestroy", "onActivityDestroyed")):
        ext.append(ev("ActA#1", base + ".pre", "pre"))
        ext.append(ev("AppObs-A", cbname, "-"))
        ext.append(ev("ActA#1", base + ".post", "post"))
    ext.append("LIFE_SCEN_END S3D2")
    add("valid-with-external-close", REF,
        write_lines("valid-external-close.trace", ext), 0, None)

    # ---- malformed manifest (must be a clean reason, not a crash) ---------
    bad_manifest = os.path.join(FIX, "bad-manifest.json")
    with open(bad_manifest, "w", encoding="utf-8") as fh:
        fh.write("{ this is not json ]")
    add("bad-manifest", REF, cand_path, 1, "MANIFEST_INVALID", manifest=bad_manifest)

    # ---- crash must surface as an internal error, never as a REJECT -------
    add("crash-guard-not-a-rejection", REF, cand_path, 2, None,
        env="VERIFY_SELFTEST_CRASH=1")

    # ---- ATL cross-Activity ordering divergence, pinned to a baseline -----
    # The ATL side runs the outgoing Activity's onStop before creating the
    # incoming Activity and its onStart after the incoming one pauses. Model
    # that fixed divergence synthetically by inserting the extra ActA#1
    # onStop/onStart groups into S2 (the same shape as the real GREEN trace).
    def _atl_group(sid, activity, base, cbname):
        owner, role = man["owner"], man["main_thread_role"]
        pre = (sid, activity, base + ".pre", activity, "-", "pre", owner, role, True)
        post = (sid, activity, base + ".post", activity, "-", "post", owner, role, True)
        cbs = [(sid, "AppObs-A", cbname, activity, "-", "-", owner, role, True),
               (sid, "AppObs-B", cbname, activity, "-", "-", owner, role, True)]
        return [pre] + cbs + [post]

    def _first(tuples, pred):
        for i, t in enumerate(tuples):
            if pred(t):
                return i
        return None

    def atl_transform(m, sid, tuples):
        out = list(tuples)
        if sid != "S2":
            return out
        i = _first(out, lambda t: t[3] == "ActB#1" and t[2] == "onCreate.pre")
        if i is None:
            return out
        out[i:i] = _atl_group(sid, "ActA#1", "onStop", "onActivityStopped")
        j = _first(out, lambda t: t[3] == "ActA#1" and t[2] == "onResume.pre")
        if j is not None:
            out[j:j] = _atl_group(sid, "ActA#1", "onStart", "onActivityStarted")
        return out

    def reorder_transform(m, sid, tuples):
        out = atl_transform(m, sid, list(tuples))
        if sid == "S1":
            ia = _first(out, lambda t: t[1] == "AppObs-A" and t[2] == "onActivityCreated")
            ib = _first(out, lambda t: t[1] == "AppObs-B" and t[2] == "onActivityCreated")
            if ia is not None and ib is not None:
                out[ia], out[ib] = out[ib], out[ia]
        return out

    def missing_obs_transform(m, sid, tuples):
        out = atl_transform(m, sid, list(tuples))
        if sid == "S1":
            out = [t for t in out
                   if not (t[1] == "AppObs-B" and t[2] == "onActivityCreated")]
        return out

    atl_cand = build_reference(man, run="lv21-atl-0001", transform=atl_transform)
    atl_base = build_reference(man, run="lv21-atl-base-0001", transform=atl_transform)
    ref_base = build_reference(man, run="lv21-ref-base-0001")  # Android ordering
    atl_path = write_lines("candidate-cross-activity.trace", atl_cand)
    atl_base_path = write_lines("baseline-atl.trace", atl_base)
    ref_base_path = write_lines("baseline-android-order.trace", ref_base)

    # (a) callback-correct but cross-Activity-divergent, WITH matching baseline
    add("candidate-cross-activity-divergent-baseline-match",
        REF, atl_path, 0, None, baseline=atl_base_path)
    # (b) same candidate, baseline uses the Android Activity ordering -> reject
    add("candidate-cross-activity-divergent-baseline-mismatch",
        REF, atl_path, 1, "DUPLICATE_EVENT", baseline=ref_base_path)
    # (e) divergent candidate with NO baseline -> fail closed
    add("candidate-cross-activity-divergent-no-baseline",
        REF, atl_path, 1, "MISSING_BASELINE")
    # (c) within-event callback reorder (baseline unchanged, still pinned)
    add("candidate-within-event-reorder",
        REF, write_lines("candidate-within-event-reorder.trace",
                         build_reference(man, run="lv21-atl-reorder-0001",
                                         transform=reorder_transform)),
        1, "ORDER", baseline=atl_base_path)
    # (d) one missing observer for a single event
    add("candidate-missing-observer",
        REF, write_lines("candidate-missing-observer.trace",
                         build_reference(man, run="lv21-atl-miss-0001",
                                         transform=missing_obs_transform)),
        1, "MISSING_EVENT", baseline=atl_base_path)

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
        fh.write("\n")
    print("wrote %d fixtures and cases.json" % len(cases))
    return 0


if __name__ == "__main__":
    sys.exit(main())