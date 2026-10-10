#!/usr/bin/env python3
"""make_fixtures.py - deterministically build the lifecycle verifier fixture matrix.

The reference side is the REAL Android probe-v1 trace
(``fixtures/reference-android.trace``, sha256
6c3ab16255f65a88f3a9af283616f729d46f5fe298f81a166e03be0507146ff0), which the
verifier validated against the contract-derived manifest. Every negative fixture
is a targeted, minimal mutation of that trace, so a rejection is attributable to
exactly one deviation. The candidate side is the same evidence re-run under a
distinct run_id (this is the only legal per-run difference).

Writes every fixture trace plus ``cases.json`` (name -> inputs, expected exit and
expected REJECT code) under ``fixtures/``. Idempotent.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "fixtures")
REF = os.path.join(FIX, "reference-android.trace")

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


def main():
    os.makedirs(FIX, exist_ok=True)
    real = read_lines(REF)
    real_run = parse_header(real[0])["run_id"]
    cases = []

    def add(name, ref, cand, exit_code, code, manifest=None, env=None):
        expect = "accept" if exit_code == 0 else ("internal" if exit_code == 2 else "reject")
        cases.append({
            "name": name,
            "reference": ref,
            "candidate": cand,
            "manifest": manifest or os.path.join(HERE, "manifest.json"),
            "exit": exit_code,
            "code": code,
            "expect": expect,
            "env": env or "",
        })

    # ---- the VALID matching pair (must exit 0) -----------------------------
    valid_cand = rewrite_run_id(list(real), "lv1-cand-0001")
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
    m = mutate_json(list(real), lambda o: o.__setitem__("run_id", "lv1-forged-9"))
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

    # ======================= candidate-side semantics =======================
    base = list(real)

    def cand(name, mut, exit_code, code):
        lines = mut(rewrite_run_id(list(base), "lv1-cand-" + name))
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

    # ---- VALID complete run including the external close (S3D2) -----------
    ext = rewrite_run_id(list(real), "lv1-cand-extclose")
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

    with open(os.path.join(FIX, "cases.json"), "w", encoding="utf-8") as fh:
        json.dump(cases, fh, indent=2)
        fh.write("\n")
    print("wrote %d fixtures and cases.json" % len(cases))
    return 0


if __name__ == "__main__":
    sys.exit(main())
