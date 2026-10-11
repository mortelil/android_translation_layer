/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.path;

import android.app.Activity;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * G-PATH probe: exercises BOTH {@link android.graphics.Path#op} overloads over a
 * controlled shape set and emits a deterministic observation JSON.
 *
 * Observation channels (per case):
 *   - boolean return of op()
 *   - receiver isEmpty() and getFillType()
 *   - computeBounds() (float, exact)
 *   - approximate() point set (geometry-derived; Skia flattening)
 *   - Region.setPath() membership mask over a fixed integer grid (geometry-derived)
 *   - operand-mutation flags (destination semantics / aliasing)
 *   - exception_expected: true for the null-operand cases that MUST raise; for
 *     those the comparator compares only the boolean return, the exception
 *     class and the operand roles, and NEVER the post-exception geometry (the
 *     pre-call receiver state is recorded as receiver_before for evidence).
 *
 * Curved boundaries are moveTo/lineTo polygons (NOT addCircle/addOval/
 * addRoundRect, whose ATL direction handling is an out-of-scope pre-existing
 * gap), so no case's op verdict depends on an operand-construction defect.
 *
 * None of these channels rasterises the path (no drawPath/bitmap), so the probe
 * does NOT depend on ATL's WINDING-only rasteriser.
 *
 * Result transport: a delimited JSON block on stdout
 *   PATHPROBE_JSON_BEGIN ... PATHPROBE_JSON_END
 * written also to getFilesDir()/results.json and, when available,
 * getExternalFilesDir(null)/results.json. The completion sentinel is
 * PATHPROBE_JSON_END.
 */
public class ProbeActivity extends Activity {

    private static final String TASK_ID = "PATH-OP-G";
    private static final String PROBE_ID = "path-probe";
    private static final String BEGIN = "PATHPROBE_JSON_BEGIN";
    private static final String END = "PATHPROBE_JSON_END";

    /** Fixed integer sampling grid for the Region membership mask. */
    private static final int GX0 = 0, GY0 = 0, GX1 = 300, GY1 = 320, GSTEP = 4;
    /** Fixed flattening tolerance handed to Path.approximate. */
    private static final float APPROX_ERROR = 1.0f;
    /**
     * Segments in a "curved" operand polygon. Curved boundaries are built from
     * moveTo/lineTo instead of addCircle/addOval/addRoundRect, whose ATL
     * direction handling is a pre-existing gap (addCircle is a stub, addOval/
     * addRoundRect drop Direction), so an addCircle operand would make the
     * Path.op verdict depend on an out-of-scope operand-construction defect.
     */
    private static final int POLY_SEGMENTS = 72;

    private static final String runId =
            "path-probe-" + Long.toHexString(System.currentTimeMillis());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json;
        try {
            json = runProbe();
        } catch (Throwable t) {
            json = "{\"task_id\":\"" + TASK_ID + "\",\"probe\":\"" + PROBE_ID
                    + "\",\"fatal\":\"" + esc(String.valueOf(t)) + "\"}";
        }
        writeResults(json);
        System.out.println(BEGIN);
        System.out.println(json);
        System.out.println(END);
        System.out.flush();
    }

    // ------------------------------------------------------------- cases ----

    private static final class Case {
        final String id, overload, opName, desc, srcFill;
        final Path p1, p2;      // operands (may be null)
        final Path recv;        // receiver
        final Path.Op op;
        /**
         * True when the case is EXPECTED to raise (null operand). For such a
         * case the comparator compares the exception CLASS (plus the boolean
         * return) and NEVER the post-exception geometry; the pre-call receiver
         * state is emitted for evidence as receiver_before.
         */
        boolean exceptionExpected;

        Case(String id, String overload, String opName, String desc, String srcFill,
             Path p1, Path p2, Path recv, Path.Op op) {
            this.id = id; this.overload = overload; this.opName = opName;
            this.desc = desc; this.srcFill = srcFill;
            this.p1 = p1; this.p2 = p2; this.recv = recv; this.op = op;
        }
    }

    private static Case expectedExc(Case c) {
        c.exceptionExpected = true;
        return c;
    }

    private String runProbe() throws Exception {
        List<Case> cases = buildCases();
        List<String> obs = new ArrayList<String>();
        List<String> exc = new ArrayList<String>();
        for (Case c : cases) {
            try {
                obs.add(observe(c));
            } catch (Throwable t) {
                exc.add(c.id + ": " + String.valueOf(t));
            }
        }
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("{\"task_id\":\"").append(TASK_ID).append('"');
        b.append(",\"probe\":\"").append(PROBE_ID).append('"');
        b.append(",\"run_id\":\"").append(esc(runId)).append('"');
        b.append(",\"run_kind\":\"").append(esc(runKind())).append('"');
        b.append(",\"build\":{");
        b.append("\"fingerprint\":\"").append(esc(nz(Build.FINGERPRINT))).append('"');
        b.append(",\"manufacturer\":\"").append(esc(nz(Build.MANUFACTURER))).append('"');
        b.append(",\"brand\":\"").append(esc(nz(Build.BRAND))).append('"');
        b.append(",\"model\":\"").append(esc(nz(Build.MODEL))).append('"');
        b.append(",\"sdk_int\":").append(Build.VERSION.SDK_INT);
        b.append('}');
        b.append(",\"config\":{");
        b.append("\"grid\":[").append(GX0).append(',').append(GY0).append(',')
                .append(GX1).append(',').append(GY1).append(',').append(GSTEP).append(']');
        b.append(",\"approx_error\":").append(fmt(APPROX_ERROR));
        b.append(",\"case_count\":").append(cases.size());
        b.append('}');
        b.append(",\"cases\":[");
        for (int i = 0; i < obs.size(); i++) {
            if (i > 0) b.append(',');
            b.append(obs.get(i));
        }
        b.append(']');
        b.append(",\"exceptions\":[");
        for (int i = 0; i < exc.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(esc(exc.get(i))).append('"');
        }
        b.append(']');
        b.append('}');
        return b.toString();
    }

    /**
     * ATL reports empty/unknown build identity; the frozen Android reference is
     * the sdk_gphone64 emulator. Raw build fields are emitted too so a
     * misclassification is visible and cross-checked by the comparator.
     */
    private static String runKind() {
        String fp = nz(Build.FINGERPRINT).toLowerCase();
        String mf = nz(Build.MANUFACTURER).toLowerCase();
        String model = nz(Build.MODEL).toLowerCase();
        if (fp.isEmpty() || model.isEmpty() || fp.contains("atl")
                || mf.contains("atl") || model.contains("atl")
                || fp.contains("unknown")) {
            return "atl";
        }
        return "android";
    }

    private static List<Case> buildCases() {
        List<Case> cs = new ArrayList<Case>();
        final Path.Op UNION = Path.Op.UNION, INT = Path.Op.INTERSECT,
                DIFF = Path.Op.DIFFERENCE, RD = Path.Op.REVERSE_DIFFERENCE,
                XOR = Path.Op.XOR;
        final String W = "WINDING", E = "EVEN_ODD";

        // ---- A: overlapping rectangles, both overloads, all five ops ----
        addPair(cs, "A", "overlap-rects", W, "instance");
        addPair(cs, "A", "overlap-rects", W, "static");

        // ---- B: overlapping curved boundary and rectangle, static, all five
        // ops. The curve is a fine moveTo/lineTo polygon (NOT addCircle/addOval/
        // addRoundRect: addCircle is a pre-existing ATL stub and addOval/
        // addRoundRect drop Direction, so any of them would make the Path.op
        // verdict depend on an out-of-scope operand-construction defect).
        for (Path.Op op : new Path.Op[]{UNION, INT, DIFF, RD, XOR}) {
            cs.add(new Case("B-" + op.name(), "static", op.name(),
                    "polyCircle(50,50,r50," + POLY_SEGMENTS + "seg) vs "
                            + "rect(50,0,150,100), WINDING", W,
                    polyCircle(50, 50, 50, W), rect(50, 0, 150, 100, W),
                    new Path(), op));
        }
        // Same WINDING/EVEN_ODD pair coverage for the curved boundary.
        for (Path.Op op : new Path.Op[]{UNION, INT, DIFF, RD, XOR}) {
            cs.add(new Case("B-E-" + op.name(), "static", op.name(),
                    "polyCircle(50,50,r50," + POLY_SEGMENTS + "seg) vs "
                            + "rect(50,0,150,100), EVEN_ODD", E,
                    polyCircle(50, 50, 50, E), rect(50, 0, 150, 100, E),
                    new Path(), op));
        }

        // ---- C: disjoint rectangles, static, all five ops ----
        for (Path.Op op : new Path.Op[]{UNION, INT, DIFF, RD, XOR}) {
            cs.add(new Case("C-" + op.name(), "static", op.name(),
                    "rect(0,0,50,50) vs rect(100,0,150,50)", W,
                    rect(0, 0, 50, 50, W), rect(100, 0, 150, 50, W),
                    new Path(), op));
        }
        Path cd = rect(0, 0, 50, 50, W);
        cs.add(new Case("C-instance-DIFF", "instance", "DIFFERENCE",
                "disjoint rects, receiver a", W,
                cd, rect(100, 0, 150, 50, W), cd, DIFF));

        // ---- D: nested ring (outer rect with inner hole) as operand ----
        // WINDING hole = opposite contour directions.
        for (Path.Op op : new Path.Op[]{UNION, INT, DIFF, RD, XOR}) {
            cs.add(new Case("D-W-" + op.name(), "static", op.name(),
                    "ring(WINDING) vs rect(40,240,60,260)", W,
                    ringWinding(), rect(40, 240, 60, 260, W), new Path(), op));
        }
        // EVEN_ODD hole = same-direction contours + EVEN_ODD fill.
        for (Path.Op op : new Path.Op[]{UNION, INT, DIFF, RD, XOR}) {
            cs.add(new Case("D-E-" + op.name(), "static", op.name(),
                    "ring(EVEN_ODD) vs rect(40,240,60,260)", E,
                    ringEvenOdd(), rect(40, 240, 60, 260, W), new Path(), op));
        }

        // ---- E: empty operands ----
        cs.add(new Case("E-empty-union-rect", "static", "UNION",
                "empty vs rect(200,0,250,50)", W,
                empty(W), rect(200, 0, 250, 50, W), new Path(), UNION));
        cs.add(new Case("E-rect-union-empty", "static", "UNION",
                "rect(200,0,250,50) vs empty", W,
                rect(200, 0, 250, 50, W), empty(W), new Path(), UNION));
        cs.add(new Case("E-empty-int-rect", "static", "INTERSECT",
                "empty vs rect", W,
                empty(W), rect(200, 0, 250, 50, W), new Path(), INT));
        cs.add(new Case("E-empty-diff-rect", "static", "DIFFERENCE",
                "empty vs rect", W,
                empty(W), rect(200, 0, 250, 50, W), new Path(), DIFF));
        cs.add(new Case("E-rect-diff-empty", "static", "DIFFERENCE",
                "rect vs empty", W,
                rect(200, 0, 250, 50, W), empty(W), new Path(), DIFF));
        cs.add(new Case("E-empty-rd-rect", "static", "REVERSE_DIFFERENCE",
                "empty vs rect", W,
                empty(W), rect(200, 0, 250, 50, W), new Path(), RD));
        cs.add(new Case("E-empty-xor-rect", "static", "XOR",
                "empty vs rect", W,
                empty(W), rect(200, 0, 250, 50, W), new Path(), XOR));
        cs.add(new Case("E-empty-empty-union", "static", "UNION",
                "empty vs empty", W,
                empty(W), empty(W), new Path(), UNION));
        cs.add(new Case("E-empty-empty-xor", "static", "XOR",
                "empty vs empty", W,
                empty(W), empty(W), new Path(), XOR));
        Path ee = empty(W);
        cs.add(new Case("E-instance-empty-union-rect", "instance", "UNION",
                "empty receiver op rect", W,
                ee, rect(200, 0, 250, 50, W), ee, UNION));

        // ---- F: self-op (same object as both operands) ----
        cs.add(new Case("F-self-union", "static", "UNION",
                "op(a,a,UNION) => a", W,
                rect(0, 0, 100, 100, W), rect(0, 0, 100, 100, W), new Path(), UNION));
        cs.add(new Case("F-self-int", "static", "INTERSECT",
                "op(a,a,INTERSECT) => a", W,
                rect(0, 0, 100, 100, W), rect(0, 0, 100, 100, W), new Path(), INT));
        cs.add(new Case("F-self-diff", "static", "DIFFERENCE",
                "op(a,a,DIFFERENCE) => empty", W,
                rect(0, 0, 100, 100, W), rect(0, 0, 100, 100, W), new Path(), DIFF));
        cs.add(new Case("F-self-xor", "static", "XOR",
                "op(a,a,XOR) => empty", W,
                rect(0, 0, 100, 100, W), rect(0, 0, 100, 100, W), new Path(), XOR));

        // ---- G: receiver aliasing (op(a,b,a) / op(a,a,b)) with true object
        // identity, then H null operands and I EVEN_ODD sources ----
        cs.add(alias("G-alias-a-union", "UNION", "op(a,b,a) UNION"));
        cs.add(alias("G-alias-a-diff", "DIFFERENCE", "op(a,b,a) DIFFERENCE"));
        cs.add(alias("G-alias-a-rd", "REVERSE_DIFFERENCE", "op(a,b,a) REVERSE_DIFFERENCE"));
        cs.add(alias2("G-alias-b-union", "UNION", "op(a,a,b) UNION"));
        cs.add(alias2("G-alias-b-xor", "XOR", "op(a,a,b) XOR"));

        // ---- H: null operands (exception-expected; the reference throws NPE) --
        cs.add(expectedExc(new Case("H-null-p1-union", "static", "UNION",
                "op(null, b, UNION) throws NPE, receiver unchanged", "WINDING",
                null, rect(0, 0, 100, 100, "WINDING"), new Path(), Path.Op.UNION)));
        cs.add(expectedExc(new Case("H-null-p2-union", "static", "UNION",
                "op(a, null, UNION) throws NPE, receiver unchanged", "WINDING",
                rect(0, 0, 100, 100, "WINDING"), null, new Path(), Path.Op.UNION)));
        cs.add(expectedExc(new Case("H-null-both-union", "static", "UNION",
                "op(null, null, UNION) throws NPE, receiver unchanged", "WINDING",
                null, null, new Path(), Path.Op.UNION)));
        Path hi = rect(0, 0, 100, 100, "WINDING");
        cs.add(expectedExc(new Case("H-instance-null-union", "instance", "UNION",
                "a.op(null, UNION) throws NPE, a unchanged", "WINDING",
                hi, null, hi, Path.Op.UNION)));

        // ---- I: EVEN_ODD sources (overlap) ----
        for (Path.Op op : new Path.Op[]{Path.Op.UNION, Path.Op.INTERSECT,
                Path.Op.DIFFERENCE, Path.Op.REVERSE_DIFFERENCE, Path.Op.XOR}) {
            cs.add(new Case("I-EO-" + op.name(), "static", op.name(),
                    "overlap rects, both EVEN_ODD", "EVEN_ODD",
                    rect(0, 0, 100, 100, "EVEN_ODD"), rect(50, 50, 150, 150, "EVEN_ODD"),
                    new Path(), op));
        }
        cs.add(new Case("I-mixed-union", "static", "UNION",
                "WINDING a vs EVEN_ODD b", "MIXED",
                rect(0, 0, 100, 100, "WINDING"), rect(50, 50, 150, 150, "EVEN_ODD"),
                new Path(), Path.Op.UNION));
        cs.add(new Case("I-mixed-diff", "static", "DIFFERENCE",
                "WINDING a vs EVEN_ODD b", "MIXED",
                rect(0, 0, 100, 100, "WINDING"), rect(50, 50, 150, 150, "EVEN_ODD"),
                new Path(), Path.Op.DIFFERENCE));
        return cs;
    }

    private static void addPair(List<Case> cs, String prefix, String name,
                                String fill, String overload) {
        Path.Op[] ops = {Path.Op.UNION, Path.Op.INTERSECT, Path.Op.DIFFERENCE,
                Path.Op.REVERSE_DIFFERENCE, Path.Op.XOR};
        for (Path.Op op : ops) {
            if ("instance".equals(overload)) {
                Path a = rect(0, 0, 100, 100, fill);
                cs.add(new Case(prefix + "-inst-" + op.name(), "instance", op.name(),
                        "a.op(b," + op.name() + ") overlap rects", fill,
                        a, rect(50, 50, 150, 150, fill), a, op));
            } else {
                cs.add(new Case(prefix + "-static-" + op.name(), "static", op.name(),
                        "op(a,b," + op.name() + ") overlap rects", fill,
                        rect(0, 0, 100, 100, fill), rect(50, 50, 150, 150, fill),
                        new Path(), op));
            }
        }
    }

    private static Case alias(String id, String opName, String desc) {
        // both operand slots reference the SAME object a.
        Path a = rect(0, 0, 100, 100, "WINDING");
        Path b = rect(50, 50, 150, 150, "WINDING");
        return new Case(id, "static", opName, desc, "WINDING",
                a, b, a, Path.Op.valueOf(opName));
    }

    private static Case alias2(String id, String opName, String desc) {
        Path a = rect(0, 0, 100, 100, "WINDING");
        Path b = rect(50, 50, 150, 150, "WINDING");
        return new Case(id, "static", opName, desc, "WINDING",
                a, a, b, Path.Op.valueOf(opName));
    }

    // ----------------------------------------------------------- shapes ----

    private static Path rect(float l, float t, float r, float b, String fill) {
        Path p = new Path();
        p.addRect(l, t, r, b, Path.Direction.CW);
        p.setFillType(fillOf(fill));
        return p;
    }

    /**
     * A "circle" of radius r about (cx,cy) as a fine closed polygon built from
     * moveTo/lineTo (no addCircle/addOval/addRoundRect). Deterministic vertex
     * order; carved from the circle's 72-segment ring.
     */
    private static Path polyCircle(float cx, float cy, float r, String fill) {
        Path p = new Path();
        for (int i = 0; i < POLY_SEGMENTS; i++) {
            double a = 2.0 * Math.PI * i / POLY_SEGMENTS;
            float x = cx + (float) (r * Math.cos(a));
            float y = cy + (float) (r * Math.sin(a));
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        p.close();
        p.setFillType(fillOf(fill));
        return p;
    }

    /** Outer rect(0,200,100,300) CW + inner rect(25,225,75,275) CCW => WINDING hole. */
    private static Path ringWinding() {
        Path p = new Path();
        p.addRect(0, 200, 100, 300, Path.Direction.CW);
        p.addRect(25, 225, 75, 275, Path.Direction.CCW);
        p.setFillType(Path.FillType.WINDING);
        return p;
    }

    /** Same two rects, same direction, EVEN_ODD => hole. */
    private static Path ringEvenOdd() {
        Path p = new Path();
        p.addRect(0, 200, 100, 300, Path.Direction.CW);
        p.addRect(25, 225, 75, 275, Path.Direction.CW);
        p.setFillType(Path.FillType.EVEN_ODD);
        return p;
    }

    private static Path empty(String fill) {
        Path p = new Path();
        p.setFillType(fillOf(fill));
        return p;
    }

    private static Path.FillType fillOf(String fill) {
        return "EVEN_ODD".equals(fill) ? Path.FillType.EVEN_ODD : Path.FillType.WINDING;
    }

    // -------------------------------------------------------- observation ----

    private String observe(Case c) throws Exception {
        String p1Before = sig(c.p1);
        String p2Before = sig(c.p2);
        // Pre-call receiver state, recorded for exception-expected cases BEFORE
        // sig()/the op so the probe's own observation cannot materialise the
        // ATL receiver handle (an Android isEmpty() reflects geometry and is
        // unaffected, so a post-NPE is_empty comparison would be contaminated).
        String recvBeforeState = c.exceptionExpected ? describe(c.recv) : null;
        String recvBefore = sig(c.recv);

        String err = null;
        boolean ret = false;
        try {
            if ("instance".equals(c.overload)) {
                ret = c.recv.op(c.p2, c.op);
            } else {
                ret = c.recv.op(c.p1, c.p2, c.op);
            }
        } catch (Throwable t) {
            err = String.valueOf(t);
        }

        String p1After = sig(c.p1);
        String p2After = sig(c.p2);

        StringBuilder b = new StringBuilder(2048);
        b.append("{\"id\":\"").append(esc(c.id)).append('"');
        b.append(",\"overload\":\"").append(c.overload).append('"');
        b.append(",\"op\":\"").append(c.opName).append('"');
        b.append(",\"desc\":\"").append(esc(c.desc)).append('"');
        b.append(",\"source_fill\":\"").append(esc(c.srcFill)).append('"');
        b.append(",\"ret\":").append(ret);
        b.append(",\"exception\":").append(err == null ? "null" : "\"" + esc(err) + "\"");
        b.append(",\"p1_role\":\"").append(role(c.p1, c.recv)).append('"');
        b.append(",\"p2_role\":\"").append(role(c.p2, c.recv)).append('"');
        b.append(",\"p1_changed\":").append(changed(c.p1, p1Before, p1After));
        b.append(",\"p2_changed\":").append(changed(c.p2, p2Before, p2After));
        b.append(",\"exception_expected\":").append(c.exceptionExpected);
        b.append(",\"receiver_before\":")
                .append(recvBeforeState == null ? "null" : recvBeforeState);
        b.append(",\"receiver\":").append(describe(c.recv));
        b.append(",\"receiver_changed\":").append(changed(c.recv, recvBefore, sig(c.recv)));
        b.append('}');
        return b.toString();
    }

    private static String role(Path p, Path recv) {
        if (p == null) return "null";
        if (p == recv) return "receiver";
        return "operand";
    }

    private static Boolean changed(Path p, String before, String after) {
        if (p == null || before == null || after == null) return null;
        return Boolean.valueOf(!before.equals(after));
    }

    /** Geometry-only signature used for operand-mutation detection. */
    private static String sig(Path p) {
        if (p == null) return null;
        try {
            RectF r = new RectF();
            p.computeBounds(r, true);
            return fmt(r.left) + "," + fmt(r.top) + "," + fmt(r.right) + ","
                    + fmt(r.bottom) + "|" + regionHash(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Full observation of a path object. */
    private static String describe(Path p) {
        if (p == null) return "null";
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"is_empty\":").append(p.isEmpty());
        b.append(",\"fill_type\":\"").append(p.getFillType().name()).append('"');
        RectF r = new RectF();
        try {
            p.computeBounds(r, true);
            b.append(",\"bounds_status\":\"ok\"");
            b.append(",\"bounds\":[").append(fmt(r.left)).append(',').append(fmt(r.top))
                    .append(',').append(fmt(r.right)).append(',').append(fmt(r.bottom)).append(']');
        } catch (Throwable t) {
            b.append(",\"bounds_status\":\"error\"");
            b.append(",\"bounds_error\":\"").append(esc(String.valueOf(t))).append('"');
        }
        b.append(",\"approx\":").append(approxJson(p));
        b.append(",\"region\":").append(regionJson(p));
        b.append('}');
        return b.toString();
    }

    private static String approxJson(Path p) {
        float[] pts;
        try {
            pts = p.approximate(APPROX_ERROR);
        } catch (Throwable t) {
            return "{\"status\":\"error\",\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
        if (pts == null) {
            return "{\"status\":\"error\",\"error\":\"null\"}";
        }
        // Android Path.approximate() returns (t, x, y) triples: t in [0,1] is the
        // position along the contour and restarts at 0 for each contour.
        int contours = 0, points = 0;
        boolean parseOk = pts.length % 3 == 0;
        if (parseOk) {
            for (int i = 0; i + 2 < pts.length; i += 3) {
                float t = pts[i];
                if (i == 0 || t == 0.0f) contours++;
                points++;
            }
        }
        StringBuilder b = new StringBuilder(pts.length * 8 + 64);
        b.append("{\"status\":\"ok\"");
        b.append(",\"parse\":\"").append(parseOk ? "ok" : "error").append('"');
        b.append(",\"contour_count\":").append(parseOk ? contours : -1);
        b.append(",\"point_count\":").append(parseOk ? points : -1);
        b.append(",\"raw_count\":").append(pts.length);
        b.append(",\"raw\":[");
        for (int k = 0; k < pts.length; k++) {
            if (k > 0) b.append(',');
            b.append(fmt(pts[k]));
        }
        b.append("]}");
        return b.toString();
    }

    private static String regionJson(Path p) {
        try {
            Region clip = new Region(GX0, GY0, GX1, GY1);
            Region r = new Region();
            boolean ok = r.setPath(p, clip);
            long h = 1469598103934665603L;
            int count = 0;
            StringBuilder bits = new StringBuilder(6144);
            for (int y = GY0; y < GY1; y += GSTEP) {
                for (int x = GX0; x < GX1; x += GSTEP) {
                    boolean in = r.contains(x, y);
                    if (in) count++;
                    bits.append(in ? '1' : '0');
                    h ^= in ? 1L : 0L;
                    h *= 1099511628211L;
                }
            }
            return "{\"status\":\"ok\",\"set_path\":\"" + (ok ? "true" : "false")
                    + "\",\"hash\":\"" + Long.toHexString(h) + "\""
                    + ",\"inside_count\":" + count
                    + ",\"sample_count\":" + bits.length()
                    + ",\"mask\":\"" + packHex(bits) + "\"}";
        } catch (Throwable t) {
            return "{\"status\":\"error\",\"error\":\"" + esc(String.valueOf(t)) + "\"}";
        }
    }

    /** Pack a '0'/'1' character string into a lowercase hex nibble string. */
    private static String packHex(CharSequence bits) {
        int n = bits.length();
        StringBuilder out = new StringBuilder((n + 3) / 4);
        for (int i = 0; i < n; i += 4) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                v = (v << 1) | (i + j < n && bits.charAt(i + j) == '1' ? 1 : 0);
            }
            out.append(Character.forDigit(v, 16));
        }
        return out.toString();
    }

    private static String regionHash(Path p) {
        try {
            Region clip = new Region(GX0, GY0, GX1, GY1);
            Region r = new Region();
            r.setPath(p, clip);
            long h = 1469598103934665603L;
            int count = 0;
            for (int y = GY0; y < GY1; y += GSTEP) {
                for (int x = GX0; x < GX1; x += GSTEP) {
                    long bit = r.contains(x, y) ? 1L : 0L;
                    if (bit == 1L) count++;
                    h ^= bit;
                    h *= 1099511628211L;
                }
            }
            return "r" + Long.toHexString(h) + "c" + count;
        } catch (Throwable t) {
            return "rerr";
        }
    }

    // ------------------------------------------------------------ output ----

    private void writeResults(String json) {
        try {
            writeJson(new File(getFilesDir(), "results.json"), json);
        } catch (Throwable ignored) {
            // stdout sentinel remains the primary transport
        }
        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                writeJson(new File(ext, "results.json"), json);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeJson(File f, String json) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        try {
            w.write(json);
        } finally {
            w.close();
        }
    }

    // ------------------------------------------------------------- utils ----

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(float v) {
        if (Float.isNaN(v)) return "\"NaN\"";
        if (Float.isInfinite(v)) return v > 0 ? "\"Infinity\"" : "\"-Infinity\"";
        return String.valueOf(Math.round(v * 1000.0f) / 1000.0f);
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}
