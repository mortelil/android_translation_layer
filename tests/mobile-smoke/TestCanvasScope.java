// SPDX-License-Identifier: GPL-3.0-only
import android.atl.GskCanvas;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.View;

public class TestCanvasScope {
	static class RecordingCanvas extends GskCanvas {
		int saves, clips;
		RecordingCanvas() { super(0); }
		@Override protected void native_save(long snapshot) { saves++; }
		@Override protected void native_restore(long snapshot) { saves--; }
		@Override protected void native_clipRect(long snapshot, float l, float t, float r, float b) { clips++; }
		@Override protected void native_clipOutPath(long snapshot, long path, int fillType) { clips++; }
		@Override protected void native_pop(long snapshot, int count) { clips -= count; }
		@Override protected void native_translate(long snapshot, float dx, float dy) {}
	}
	public static void run(Context context) {
		RecordingCanvas canvas = new RecordingCanvas();
		View view = new View(context) {
			@Override public void draw(Canvas c) {
				c.translate(10, 20);
				c.clipRect(0, 0, 3, 4);
				c.clipOutPath(new android.graphics.Path());
				c.save();
				c.clipRect(1, 1, 2, 2);
				// Deliberately leave state for the enclosing view draw scope to unwind.
			}
		};
		for (int i = 0; i < 3; i++) {
			canvas.drawView(view, 42);
			assertReset(canvas);
		}
		try {
			canvas.drawView(new View(context) {
				@Override public void draw(Canvas c) {
					c.clipRect(0, 0, 1, 1);
					throw new IllegalArgumentException("test draw failure");
				}
			}, 43);
			throw new AssertionError("draw exception swallowed");
		} catch (IllegalArgumentException expected) { assertReset(canvas); }
		System.out.println("PASS: per-view canvas clips/transforms reset across frames and exceptions");
		android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888);
		Canvas pixels = new Canvas(bitmap);
		android.graphics.Paint paint = new android.graphics.Paint();
		paint.setColor(0xffff0000);
		pixels.drawRect(0, 0, 20, 20, paint);
		android.graphics.Path hole = new android.graphics.Path();
		hole.addRect(5, 5, 15, 15, android.graphics.Path.Direction.CW);
		int saved = pixels.save();
		pixels.clipOutPath(hole);
		paint.setColor(0xff0000ff);
		pixels.drawRect(0, 0, 20, 20, paint);
		pixels.restoreToCount(saved);
		paint.setColor(0xff00ff00);
		pixels.drawRect(8, 8, 12, 12, paint);
		int[] result = new int[400];
		bitmap.getPixels(result, 0, 20, 0, 0, 20, 20);
		if (result[2 * 20 + 2] != 0xff0000ff || result[6 * 20 + 6] != 0xffff0000 || result[9 * 20 + 9] != 0xff00ff00)
			throw new AssertionError("Path difference must exclude its interior and restore normally");
		System.out.println("PASS: inverse path clipping pixels and save/restore");
	}
	private static void assertReset(RecordingCanvas canvas) {
		Matrix m = new Matrix(); canvas.getMatrix(m);
		if (canvas.getSaveCount() != 1 || canvas.saves != 0 || canvas.clips != 0 || canvas.snapshot != 0 || !m.isIdentity())
			throw new AssertionError("Leaked drawing state");
	}
}
