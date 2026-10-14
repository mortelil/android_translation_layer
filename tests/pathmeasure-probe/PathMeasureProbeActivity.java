/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.pathmeasure;

import android.app.Activity;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Discriminating probe for android.graphics.PathMeasure.
 *
 *  Cases (each maps to one ASSERT line and one entry in the marker-delimited JSON):
 *   - two/...   : a path with TWO contours - per-contour getLength()/getPosTan and the
 *                 nextContour() truth table (true, true, false) plus the empty state.
 *   - degen/... : a zero-length (move-only) contour in the middle is skipped.
 *   - forceClosed/... : an open triangle with forceClosed OFF vs ON (closing edge added).
 *   - closed/...: an explicitly closed() triangle measures like the force-closed one.
 *   - variants/ : tangent-only (pos == null) and position-only (tan == null) queries.
 *   - endpoint/, range/ : endpoints and out-of-range distances (clamped, still true).
 *   - undersized/ : pos/tan arrays shorter than 2 are rejected (AIOOBE, as on Android).
 *   - empty/, setPath/ : a measure with no path, and setPath(null).
 *   - ownership/ : the measured geometry survives a dropped-and-collected Java Path and
 *                 a Path.reset() + reuse (snapshot semantics). The snapshot checks read
 *                 freed memory on the pre-repair build and run LAST for that reason.
 *
 *  Expectations follow AOSP android-17.0.0_r1 (PathMeasure.java + Skia
 *  SkContourMeasure::getPosTan/buildSegments). */
public class PathMeasureProbeActivity extends Activity {
	private static final String TAG = "[PM] ";
	private static final String BEGIN = "PATHTEST_JSON_BEGIN";
	private static final String END = "PATHTEST_JSON_END";
	private static final float TOL = 1e-3f;

	private final List<String> names = new ArrayList<String>();
	private final List<String> results = new ArrayList<String>();
	private final List<String> details = new ArrayList<String>();
	private int failures;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		System.out.println(TAG + "pathmeasure probe start");
		try {
			curves();
			twoContours();
			degenerateMiddle();
			forceClosed();
			closedContour();
			posTanVariants();
			endpointsAndRange();
			undersizedArrays();
			emptyAndSetPath();
			ownershipAfterGc();
			ownershipAfterReset();
		} catch (Throwable t) {
			check("harness/exception", false, t.getClass().getName() + ": " + t.getMessage());
		}
		String[] limitations = {
			"getSegment() always returns false (unimplemented in ATL; see the PathMeasure class comment)",
		};
		writeResults(limitations);
		System.out.println(TAG + "PASS=" + (names.size() - failures) + " FAIL=" + failures);
		System.out.println(END);
		System.out.flush();
	}

	/* --- scenarios -------------------------------------------------------- */

	/** Contour 1: the 3/4/5 triangle (0,0)->(3,0)->(3,4), length 7.
	 *  Contour 2: (10,0)->(10,6), length 6. */
	private static Path twoContourPath() {
		Path p = new Path();
		p.moveTo(0.0f, 0.0f);
		p.lineTo(3.0f, 0.0f);
		p.lineTo(3.0f, 4.0f);
		p.moveTo(10.0f, 0.0f);
		p.lineTo(10.0f, 6.0f);
		return p;
	}

	private static Path openTrianglePath() {
		Path p = new Path();
		p.moveTo(0.0f, 0.0f);
		p.lineTo(3.0f, 0.0f);
		p.lineTo(3.0f, 4.0f);
		return p;
	}

	// Curve lengths are approximations: Android's default Skia measure reports
	// 14.604048 for this analytic 14.78943 quadratic and 62.42697 for a 62.83185
	// circle. Use a 2% approximation bound for lengths; positions/tangents and
	// straight-line contours keep their existing tight tolerance.
	private void curves() {
		Path p = new Path();
		p.moveTo(0, 0); p.quadTo(5, 10, 10, 0);
		PathMeasure m = new PathMeasure(p, false);
		check("quad/length", Math.abs(m.getLength() - 14.78943f) / 14.78943f < 0.02f, "length=" + m.getLength());
		float[] pos = new float[2], tan = new float[2];
		checkBool("quad/midpoint-valid", true, m.getPosTan(m.getLength()/2, pos, tan));
		checkPos("quad/midpoint", 5, 5, pos);
		checkTan("quad/tangent", 1, 0, tan);
		p.reset(); p.moveTo(0, 0); p.cubicTo(0, 10, 10, 10, 10, 0);
		m.setPath(p, false);
		check("cubic/length", Math.abs(m.getLength() - 20f) / 20f < 0.02f, "length=" + m.getLength());
		checkBool("cubic/midpoint-valid", true, m.getPosTan(m.getLength()/2, pos, tan));
		checkPos("cubic/midpoint", 5, 7.5f, pos);
		checkTan("cubic/tangent", 1, 0, tan);
		p.reset(); p.addCircle(0, 0, 10, Path.Direction.CW);
		m.setPath(p, false);
		check("circle/length", Math.abs(m.getLength() - 62.83185f) / 62.83185f < 0.02f, "length=" + m.getLength());
		checkBool("circle/start-valid", true, m.getPosTan(0, pos, tan));
		checkPos("circle/start", 10, 0, pos);
		checkTan("circle/CW-tangent", 0, 1, tan);
		p.reset(); p.addCircle(3, 4, 10, Path.Direction.CCW); m.setPath(p, false);
		checkBool("circle/CCW-start-valid", true, m.getPosTan(0, pos, tan));
		checkPos("circle/CCW-start", 13, 4, pos);
		checkTan("circle/CCW-tangent", 0, -1, tan);
		float circleLength = m.getLength();
		p.addCircle(0, 0, 0, Path.Direction.CW); p.addCircle(0, 0, -1, Path.Direction.CW);
		m.setPath(p, false);
		checkFloat("circle/invalid-radius-keeps-path", circleLength, m.getLength());
		checkBool("circle/invalid-radius-no-extra-contour", false, m.nextContour());
		p.reset(); p.moveTo(0, 0); p.lineTo(3, 0); p.lineTo(3, 4); p.close(); p.lineTo(0, 2);
		m.setPath(p, false);
		checkFloat("close-then-line/first", 12, m.getLength());
		checkBool("close-then-line/next", true, m.nextContour());
		checkFloat("close-then-line/second", 2, m.getLength());
	}

	private void twoContours() {
		PathMeasure m = new PathMeasure(twoContourPath(), false);
		float[] pos = new float[2];
		float[] tan = new float[2];

		checkFloat("two/length-first", 7.0f, m.getLength());
		checkBool("two/posTan-5", true, m.getPosTan(5.0f, pos, tan));
		checkPos("two/pos-5", 3.0f, 2.0f, pos);
		checkTan("two/tan-5", 0.0f, 1.0f, tan);
		checkBool("two/posTan-end", true, m.getPosTan(7.0f, pos, null));
		checkPos("two/end-pos", 3.0f, 4.0f, pos);

		checkBool("two/next-1", true, m.nextContour());
		checkFloat("two/length-second", 6.0f, m.getLength());
		checkBool("two/posTan-second-3", true, m.getPosTan(3.0f, pos, tan));
		checkPos("two/second-pos-3", 10.0f, 3.0f, pos);
		checkTan("two/second-tan-3", 0.0f, 1.0f, tan);

		checkBool("two/next-2-past-last", false, m.nextContour());
		checkFloat("two/length-past-last", 0.0f, m.getLength());
		checkBool("two/posTan-past-last", false, m.getPosTan(0.0f, pos, tan));
		checkBool("two/next-3-past-last", false, m.nextContour());
		checkBool("two/posTan-past-last-mid", false, m.getPosTan(3.0f, pos, null));
	}

	private void degenerateMiddle() {
		Path p = new Path();
		p.moveTo(0.0f, 0.0f);
		p.lineTo(3.0f, 0.0f);
		p.lineTo(3.0f, 4.0f);
		p.moveTo(50.0f, 50.0f); // zero-length contour: Skia drops it, so must ATL
		p.moveTo(10.0f, 0.0f);
		p.lineTo(10.0f, 6.0f);

		PathMeasure m = new PathMeasure(p, false);
		checkFloat("degen/length-first", 7.0f, m.getLength());
		checkBool("degen/next-skips-empty", true, m.nextContour());
		checkFloat("degen/length-second", 6.0f, m.getLength());
		checkBool("degen/next-past-last", false, m.nextContour());
		checkFloat("degen/length-past-last", 0.0f, m.getLength());
	}

	private void forceClosed() {
		PathMeasure off = new PathMeasure(openTrianglePath(), false);
		PathMeasure on = new PathMeasure(openTrianglePath(), true);
		float[] pos = new float[2];
		float[] tan = new float[2];

		checkFloat("forceClosed/off-length", 7.0f, off.getLength());
		checkFloat("forceClosed/on-length", 12.0f, on.getLength());
		checkFloat("forceClosed/on-minus-off-closing-edge", 5.0f, on.getLength() - off.getLength());

		// The closing chord (3,4)->(0,0) is the last 5 units of the force-closed measure.
		checkBool("forceClosed/on-posTan-midchord", true, on.getPosTan(9.5f, pos, tan));
		checkPos("forceClosed/on-midchord-pos", 1.5f, 2.0f, pos);
		checkTan("forceClosed/on-midchord-tan", -0.6f, -0.8f, tan);
		checkBool("forceClosed/on-posTan-end", true, on.getPosTan(12.0f, pos, tan));
		checkPos("forceClosed/on-end-pos", 0.0f, 0.0f, pos);

		checkBool("forceClosed/off-posTan-end", true, off.getPosTan(7.0f, pos, null));
		checkPos("forceClosed/off-end-pos", 3.0f, 4.0f, pos);
	}

	private void closedContour() {
		Path p = new Path();
		p.moveTo(0.0f, 0.0f);
		p.lineTo(3.0f, 0.0f);
		p.lineTo(3.0f, 4.0f);
		p.close();

		PathMeasure m = new PathMeasure(p, false);
		float[] pos = new float[2];
		float[] tan = new float[2];
		checkFloat("closed/length", 12.0f, m.getLength());
		checkBool("closed/posTan-midchord", true, m.getPosTan(9.5f, pos, tan));
		checkPos("closed/midchord-pos", 1.5f, 2.0f, pos);
		checkTan("closed/midchord-tan", -0.6f, -0.8f, tan);
	}

	private void posTanVariants() {
		PathMeasure m = new PathMeasure(openTrianglePath(), false);
		float[] tan = new float[2];
		float[] pos = new float[2];

		checkBool("variants/tan-only-returns-true", true, m.getPosTan(1.5f, null, tan));
		checkTan("variants/tan-only-tan", 1.0f, 0.0f, tan);
		checkBool("variants/pos-only-returns-true", true, m.getPosTan(1.5f, pos, null));
		checkPos("variants/pos-only-pos", 1.5f, 0.0f, pos);
		checkBool("variants/both-null-returns-true", true, m.getPosTan(1.5f, null, null));
	}

	private void endpointsAndRange() {
		PathMeasure m = new PathMeasure(openTrianglePath(), false);
		float[] pos = new float[2];
		float[] tan = new float[2];

		checkBool("endpoint/posTan-0", true, m.getPosTan(0.0f, pos, tan));
		checkPos("endpoint/pos-0", 0.0f, 0.0f, pos);
		checkTan("endpoint/tan-0", 1.0f, 0.0f, tan);

		checkBool("endpoint/posTan-length", true, m.getPosTan(7.0f, pos, tan));
		checkPos("endpoint/pos-length", 3.0f, 4.0f, pos);
		checkTan("endpoint/tan-length", 0.0f, 1.0f, tan);

		checkBool("range/posTan-below-0", true, m.getPosTan(-5.0f, pos, tan));
		checkPos("range/pos-below-0", 0.0f, 0.0f, pos);
		checkTan("range/tan-below-0", 1.0f, 0.0f, tan);
		checkBool("range/posTan-above-length", true, m.getPosTan(1000.0f, pos, tan));
		checkPos("range/pos-above-length", 3.0f, 4.0f, pos);
		checkTan("range/tan-above-length", 0.0f, 1.0f, tan);
		checkBool("range/posTan-nan-false", false, m.getPosTan(Float.NaN, pos, tan));
	}

	private void undersizedArrays() {
		PathMeasure m = new PathMeasure(openTrianglePath(), false);

		check("undersized/pos-length-1", throwsAioobe(m, new float[1], new float[2]), "expect ArrayIndexOutOfBoundsException");
		check("undersized/tan-length-0", throwsAioobe(m, new float[2], new float[0]), "expect ArrayIndexOutOfBoundsException");
		check("undersized/pos-length-1-tan-null", throwsAioobe(m, new float[1], null), "expect ArrayIndexOutOfBoundsException");
		check("undersized/sized-arrays-ok", !throwsAioobe(m, new float[2], new float[2]), "no exception for length 2");
	}

	private void emptyAndSetPath() {
		PathMeasure e = new PathMeasure();
		float[] pos = new float[2];
		checkFloat("empty/length", 0.0f, e.getLength());
		checkBool("empty/nextContour", false, e.nextContour());
		checkBool("empty/posTan", false, e.getPosTan(0.0f, pos, null));
		checkBool("empty/posTan-far", false, e.getPosTan(100.0f, pos, null));

		PathMeasure m = new PathMeasure(openTrianglePath(), false);
		checkFloat("setPath/initial-length", 7.0f, m.getLength());
		m.setPath(twoContourPath(), false);
		checkFloat("setPath/replace-length", 7.0f, m.getLength());
		checkBool("setPath/replace-next", true, m.nextContour());
		checkFloat("setPath/replace-second-length", 6.0f, m.getLength());
		checkBool("setPath/replace-next-past-last", false, m.nextContour());
		m.setPath(null, false);
		checkFloat("setPath/null-length", 0.0f, m.getLength());
		checkBool("setPath/null-posTan", false, m.getPosTan(0.0f, pos, null));
		checkBool("setPath/null-nextContour", false, m.nextContour());
	}

	/** Ownership (GC): the Java Path is dropped, so without a strong reference the
	 *  measured geometry can be collected. */
	private void ownershipAfterGc() {
		Path p = twoContourPath();
		WeakReference<Path> weak = new WeakReference<Path>(p);
		PathMeasure m = new PathMeasure(p, false);
		p = null;

		for (int i = 0; i < 4; i++) {
			byte[] junk = new byte[1 << 20];
			junk[0] = (byte) i; // keep the allocation alive past the GC call
			System.gc();
		}

		System.out.println(TAG + "GC diagnostic: WeakReference cleared=" + (weak.get() == null));
		checkFloat("ownership/length-after-gc", 7.0f, m.getLength());
		checkBool("ownership/next-after-gc", true, m.nextContour());
		checkFloat("ownership/second-length-after-gc", 6.0f, m.getLength());
		checkBool("ownership/next-past-last-after-gc", false, m.nextContour());
	}

	/** Ownership (snapshot): Path.reset() frees the underlying GskPath. A measure that
	 *  only stored that raw pointer (pre-repair) reads freed memory here. */
	private void ownershipAfterReset() {
		Path p = twoContourPath();
		PathMeasure m = new PathMeasure(p, false);
		checkFloat("ownership/length-before-reset", 7.0f, m.getLength());

		p.reset();
		p.moveTo(0.0f, 0.0f);
		p.lineTo(100.0f, 0.0f); // the measure must NOT see this new geometry

		checkFloat("ownership/snapshot-length-after-reset", 7.0f, m.getLength());
		checkBool("ownership/snapshot-next-after-reset", true, m.nextContour());
		checkFloat("ownership/snapshot-second-length-after-reset", 6.0f, m.getLength());
		checkBool("ownership/snapshot-next-past-last", false, m.nextContour());
	}

	/* --- harness ---------------------------------------------------------- */

	private static boolean throwsAioobe(PathMeasure m, float[] pos, float[] tan) {
		try {
			m.getPosTan(0.0f, pos, tan);
		} catch (ArrayIndexOutOfBoundsException e) {
			return true;
		}
		return false;
	}

	private void check(String name, boolean ok, String detail) {
		names.add(name);
		results.add(ok ? "pass" : "fail");
		details.add(detail);
		if (!ok)
			failures++;
		System.out.println(TAG + "ASSERT " + name + ": " + detail + " -> " + (ok ? "PASS" : "FAIL"));
		System.out.flush();
	}

	private void checkFloat(String name, float expected, float actual) {
		check(name, Math.abs(expected - actual) <= TOL, "expected=" + expected + " actual=" + actual);
	}

	private void checkBool(String name, boolean expected, boolean actual) {
		check(name, expected == actual, "expected=" + expected + " actual=" + actual);
	}

	private void checkPos(String name, float ex, float ey, float[] pos) {
		if (pos == null) {
			check(name, false, "pos array is null");
			return;
		}
		check(name, Math.abs(ex - pos[0]) <= TOL && Math.abs(ey - pos[1]) <= TOL,
				"expected=(" + ex + "," + ey + ") actual=(" + pos[0] + "," + pos[1] + ")");
	}

	private void checkTan(String name, float ex, float ey, float[] tan) {
		if (tan == null) {
			check(name, false, "tan array is null");
			return;
		}
		check(name, Math.abs(ex - tan[0]) <= TOL && Math.abs(ey - tan[1]) <= TOL,
				"expected=(" + ex + "," + ey + ") actual=(" + tan[0] + "," + tan[1] + ")");
	}

	private void writeResults(String[] limitations) {
		StringBuilder json = new StringBuilder();
		json.append("{\"probe\":\"pathmeasure\",\"failures\":").append(failures).append(",\"cases\":[");
		for (int i = 0; i < names.size(); i++) {
			if (i > 0)
				json.append(",");
			json.append("{\"name\":\"").append(names.get(i)).append("\",\"ok\":")
					.append("pass".equals(results.get(i))).append(",\"detail\":\"")
					.append(escape(details.get(i))).append("\"}");
		}
		json.append("],\"limitations\":[");
		for (int i = 0; i < limitations.length; i++) {
			if (i > 0)
				json.append(",");
			json.append("\"").append(escape(limitations[i])).append("\"");
		}
		json.append("]}");
		try {
			System.out.println(BEGIN);
			System.out.print(json.toString());
			System.out.println("\n" + END);
			System.out.flush();
			File f = new File(getFilesDir(), "results.json");
			FileOutputStream out = new FileOutputStream(f);
			Writer w = new OutputStreamWriter(out);
			w.write(json.toString());
			w.write("\n");
			w.flush();
			out.close();
		} catch (Throwable t) {
			System.out.println(TAG + "results write failed: " + t);
		}
	}

	private static String escape(String s) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				b.append('\\').append(c);
			} else if (c == '\n') {
				b.append("\\n");
			} else {
				b.append(c);
			}
		}
		return b.toString();
	}
}