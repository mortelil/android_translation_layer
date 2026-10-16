// SPDX-License-Identifier: GPL-3.0-only
import android.atl.GskCanvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.RenderNode;

public class TestRetainedDisplayList {
    private static int[] pixels(RenderNode node) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        java.lang.reflect.Method snapshot = Bitmap.class.getDeclaredMethod("getSnapshot");
        snapshot.setAccessible(true);
        new GskCanvas((Long)snapshot.invoke(bitmap)).drawRenderNode(node);
        int[] pixels = new int[1600];
        bitmap.getPixels(pixels, 0, 40, 0, 0, 40, 40);
        return pixels;
    }
    private static void color(RenderNode node, int color) {
        GskCanvas canvas = (GskCanvas)node.start(40, 40);
        Paint paint = new Paint(); paint.setColor(color);
        canvas.drawRect(0, 0, 40, 40, paint);
        paint.setColor(0xff000000); // Recording must freeze mutable paint.
        node.end(canvas);
    }
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("Expected " + Integer.toHexString(expected) + " got " + Integer.toHexString(actual));
    }
    public static void run() throws Exception {
        RenderNode child = RenderNode.create("child", null);
        RenderNode parent = RenderNode.create("parent", null);
        GskCanvas canvas = (GskCanvas)parent.start(40, 40);
        canvas.save();
        canvas.translate(10, 10);
        Path clip = new Path();
        clip.addRoundRect(0, 0, 20, 20, 4, 4, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRenderNode(child);
        canvas.restore();
        parent.end(canvas);
        equal(0, pixels(parent)[20 * 40 + 20]);
        color(child, 0xffff0000);
        int[] p = pixels(parent);
        equal(0xffff0000, p[20 * 40 + 20]);
        equal(0, p[5 * 40 + 5]); equal(0, p[35 * 40 + 35]);
        long cached = parent.getGskNode();
        if (cached != parent.getGskNode()) throw new AssertionError("Unchanged display list rebuilt");
        color(child, 0xff0000ff);
        equal(0xff0000ff, pixels(parent)[20 * 40 + 20]);
        child.discardDisplayList();
        equal(0, pixels(parent)[20 * 40 + 20]);
        color(child, 0xff00ff00);
        equal(0xff00ff00, pixels(parent)[20 * 40 + 20]);
        RenderNode grandparent = RenderNode.create("grandparent", null);
        canvas = (GskCanvas)grandparent.start(40, 40);
        canvas.drawRenderNode(parent); grandparent.end(canvas);
        equal(0xff00ff00, pixels(grandparent)[20 * 40 + 20]);
        color(child, 0xffff0000);
        equal(0xffff0000, pixels(grandparent)[20 * 40 + 20]);
        parent.discardDisplayList(); parent.discardDisplayList();
        equal(0, pixels(grandparent)[20 * 40 + 20]);
        grandparent.discardDisplayList(); child.discardDisplayList();
        RenderNode layer = RenderNode.create("layer", null);
        canvas = (GskCanvas)layer.start(40, 40);
        int save = canvas.saveLayerAlpha(0, 0, 40, 40, 128, 31);
        color(child, 0xffff0000);
        canvas.drawRenderNode(child);
        canvas.drawRenderNode(child); // Group opacity, not per-primitive alpha.
        canvas.restoreToCount(save);
        layer.end(canvas);
        int alpha = pixels(layer)[20 * 40 + 20] >>> 24;
        if (alpha < 127 || alpha > 129) throw new AssertionError("Layer alpha " + alpha);
        child.setTranslationX(30);
        equal(0, pixels(layer)[20 * 40 + 20]);
        layer.discardDisplayList(); child.discardDisplayList();
        RenderNode captured = RenderNode.create("captured state", null);
        canvas = (GskCanvas)captured.start(40, 40);
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setTranslate(10, 10);
        canvas.concat(matrix); matrix.reset();
        Path capturedClip = new Path();
        capturedClip.addRect(0, 0, 10, 10, Path.Direction.CW);
        canvas.clipPath(capturedClip); capturedClip.reset();
        Paint paint = new Paint(); paint.setColor(0xff00ff00);
        canvas.drawRect(0, 0, 40, 40, paint);
        captured.end(canvas); // Also closes a clip left at the base save level.
        p = pixels(captured);
        equal(0xff00ff00, p[15 * 40 + 15]); equal(0, p[25 * 40 + 25]);
        captured.discardDisplayList();
        RenderNode cycle = RenderNode.create("cycle", null);
        canvas = (GskCanvas)cycle.start(40, 40);
        canvas.drawRenderNode(cycle); cycle.end(canvas);
        try {
            cycle.getGskNode(); throw new AssertionError("Cycle accepted");
        } catch (IllegalStateException expected) { cycle.discardDisplayList(); }
        System.out.println("PASS: retained display lists preserve clips, mutable paint, child updates, transitive updates, discard and cache");
    }
}
