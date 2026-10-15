/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.activity.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, flushed event trace plus the assertion log for the lane-C activity
 * seam probe (rows A05/A06/A07/A08/A09). Every line goes to
 * getFilesDir()/aprobe-trace.log and to stdout, so the evidence survives both
 * a normal window close and a crash.
 *
 * Line formats:
 *   APROBE_TRACE_BEGIN run_id=.. package=..
 *   APROBE_SCEN_BEGIN <id> / APROBE_SCEN_END <id>
 *   APROBE_EVT <seq> <scenario> <actor> <event> <activity> <phase> <bundle>
 *   APROBE <name> PASS|FAIL <detail>
 *   APROBE_INFO <name> <detail>
 *   APROBE_EXCEPTION <text>
 *   APROBE_JSON_BEGIN <json> APROBE_JSON_END
 *   APROBE ALL_DONE <n> PASS <m> FAIL
 *   APROBE_MARK TERMINAL_DONE
 */
final class Trace {
	private static final class Ev {
		final String scen, actor, event, activity, phase, bundle;
		Ev(String scen, String actor, String event, String activity, String phase, String bundle) {
			this.scen = scen; this.actor = actor; this.event = event;
			this.activity = activity; this.phase = phase; this.bundle = bundle;
		}
	}

	private final File dir;
	private final FileOutputStream raw;
	private final Writer writer;
	private final List<Ev> evs = new ArrayList<Ev>();
	private final List<String> checks = new ArrayList<String>();
	private int seq, pass, fail;
	private String scenario = "-";

	Trace(File dir, String runId) throws Exception {
		this.dir = dir;
		raw = new FileOutputStream(new File(dir, "aprobe-trace.log"), false);
		writer = new OutputStreamWriter(raw, "UTF-8");
		line("APROBE_TRACE_BEGIN run_id=" + runId + " package=" + Probe.PKG + " probe=" + Probe.PROBE_ID);
	}

	synchronized void line(String s) {
		try {
			writer.write(s); writer.write("\n"); writer.flush(); raw.flush();
		} catch (Exception e) {
			// A trace write failure must not abort the drive: stdout remains.
		}
		System.out.println(s);
	}

	synchronized void scenBegin(String id) { scenario = id; line("APROBE_SCEN_BEGIN " + id); }
	synchronized void scenEnd(String id) { line("APROBE_SCEN_END " + id); scenario = "-"; }

	synchronized void event(String actor, String event, String activity, String phase, String bundle) {
		Ev e = new Ev(scenario, actor, event, activity, phase, bundle == null ? "-" : bundle);
		evs.add(e);
		line("APROBE_EVT " + (++seq) + " " + e.scen + " " + e.actor + " " + e.event + " "
			+ e.activity + " " + e.phase + " " + e.bundle + " main=" + Probe.onMainThread());
	}

	synchronized void info(String name, String detail) {
		line("APROBE_INFO " + name + " " + detail);
		checks.add("{\"name\":\"" + Trace.esc(name) + "\",\"ok\":true,\"info\":true,\"detail\":\"" + Trace.esc(detail) + "\"}");
	}

	synchronized void check(String name, boolean ok, String detail) {
		if (ok) { pass++; } else { fail++; }
		line("APROBE " + name + " " + (ok ? "PASS" : "FAIL") + " " + detail);
		checks.add("{\"name\":\"" + Trace.esc(name) + "\",\"ok\":" + ok + ",\"detail\":\"" + Trace.esc(detail) + "\"}");
	}

	synchronized void recordException(String text) { line("APROBE_EXCEPTION " + text); }

	synchronized int size() { return evs.size(); }

	/** First index >= from with (activity tag, event, phase==pre) match, else -1. */
	synchronized int firstPre(int from, String tag, String event) {
		for (int i = Math.max(from, 0); i < evs.size(); i++) {
			Ev e = evs.get(i);
			if (e.activity.equals(tag) && e.event.equals(event) && "pre".equals(e.phase)) return i;
		}
		return -1;
	}

	/**
	 * Index of the LAST event >= from and < to with (activity tag, event), any
	 * phase, else -1. Used to reach a callback's post-super line (A05
	 * in-callback recreate), which {@link #firstPre} cannot express.
	 */
	synchronized int lastIndex(int from, int to, String tag, String event) {
		for (int i = Math.min(to, evs.size()) - 1; i >= Math.max(from, 0); i--) {
			Ev e = evs.get(i);
			if (e.activity.equals(tag) && e.event.equals(event)) return i;
		}
		return -1;
	}

	synchronized int countPre(int from, int to, String tag, String event) {
		int n = 0;
		for (int i = Math.max(from, 0); i < Math.min(to, evs.size()); i++) {
			Ev e = evs.get(i);
			if (e.activity.equals(tag) && e.event.equals(event) && "pre".equals(e.phase)) n++;
		}
		return n;
	}

	/** pre-phase lifecycle callbacks (no focus/observer events) as "tag:name". */
	synchronized List<String> lifecycleEvents(int from, int to) {
		List<String> out = new ArrayList<String>();
		for (int i = Math.max(from, 0); i < Math.min(to, evs.size()); i++) {
			Ev e = evs.get(i);
			if (!"pre".equals(e.phase)) continue;
			for (String n : LIFECYCLE) if (n.equals(e.event)) { out.add(e.activity + ":" + n); break; }
		}
		return out;
	}

	synchronized List<String> tagsCreated(int from, int to, String prefix) {
		List<String> out = new ArrayList<String>();
		for (int i = Math.max(from, 0); i < Math.min(to, evs.size()); i++) {
			Ev e = evs.get(i);
			if (e.event.equals("onCreate") && e.activity.startsWith(prefix) && !out.contains(e.activity)) {
				out.add(e.activity);
			}
		}
		return out;
	}

	/** Ordered "activity:event" pairs in the window, for the strict-order checks. */
	synchronized List<String> order(int from, int to, String[] tagEventPairs) {
		List<String> out = new ArrayList<String>();
		for (int i = Math.max(from, 0); i < Math.min(to, evs.size()); i++) {
			Ev e = evs.get(i);
			if (!"pre".equals(e.phase)) continue;
			String k = e.activity + ":" + e.event;
			for (String p : tagEventPairs) if (p.equals(k)) { out.add(k); break; }
		}
		return out;
	}

	synchronized void end() {
		StringBuilder b = new StringBuilder();
		b.append("{\"probe\":\"").append(PROBE).append("\",\"pass\":").append(pass)
			.append(",\"fail\":").append(fail).append(",\"checks\":[");
		for (int i = 0; i < checks.size(); i++) { if (i > 0) b.append(','); b.append(checks.get(i)); }
		b.append("]}");
		String json = b.toString();
		try {
			Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "results.json"), false), "UTF-8");
			w.write(json);
			w.write("\n");
			w.flush();
			w.close();
		} catch (Exception e) {
			line("APROBE_EXCEPTION results-json " + e);
		}
		line("APROBE_JSON_BEGIN " + json + " APROBE_JSON_END");
		line("APROBE ALL_DONE " + pass + " PASS " + fail + " FAIL");
		line("APROBE_MARK TERMINAL_DONE");
	}

	/** Lifecycle callback names of the state engine (focus is deliberately absent). */
	static final String[] LIFECYCLE = {"onCreate", "onStart", "onPostCreate", "onResume", "onPause",
		"onStop", "onRestart", "onDestroy", "onSaveInstanceState", "onRestoreInstanceState",
		"onNewIntent", "onActivityResult"};

	private static final String PROBE = Probe.PROBE_ID;

	static String esc(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				default: b.append(c);
			}
		}
		return b.toString();
	}
}
