package android.view;

import android.atl.GskCanvas;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;

public class Surface {
	public long widget;
	private SurfaceTexture texture;
	private boolean released;
	private SurfaceCanvas lockedCanvas;

	public Surface() {}
	public Surface(SurfaceTexture surfaceTexture) {
		if (surfaceTexture == null)
			throw new IllegalArgumentException("Null SurfaceTexture");
		texture = surfaceTexture;
	}
	public synchronized boolean isValid() {
		return !released && (texture != null ? !texture.isReleased() : widget != 0);
	}
	public synchronized Canvas lockHardwareCanvas() { return lockCanvas(null); }
	public synchronized Canvas lockCanvas(Rect dirty) {
		if (!isValid())
			throw new IllegalStateException("Invalid Surface");
		if (lockedCanvas != null)
			throw new IllegalStateException("Surface already locked");
		if (texture == null)
			throw new UnsupportedOperationException("Use SurfaceHolder for widget surfaces");
		int[] size = texture.atlBufferSize();
		lockedCanvas = new SurfaceCanvas(size[0], size[1]);
		return lockedCanvas;
	}
	public synchronized void unlockCanvasAndPost(Canvas canvas) {
		if (canvas != lockedCanvas || lockedCanvas == null)
			throw new IllegalArgumentException("Canvas was not locked by this Surface");
		SurfaceCanvas frame = lockedCanvas;
		lockedCanvas = null;
		long snapshot = frame.snapshot;
		frame.snapshot = 0;
		byte[] rgba = nativePostCanvas(snapshot, frame.width, frame.height);
		if (!released)
			texture.atlQueueFrame(rgba, frame.width, frame.height, System.nanoTime());
	}
	public synchronized void release() { released = true; }

	private static class SurfaceCanvas extends GskCanvas {
		final int width, height;
		SurfaceCanvas(int width, int height) {
			super(nativeResetCanvas(0));
			this.width = width;
			this.height = height;
		}
		@Override
		public int getWidth() { return width; }
		@Override
		public int getHeight() { return height; }
		@Override
		public void drawColor(int color) { drawColor(color, PorterDuff.Mode.SRC_OVER); }
		@Override
		public void drawColor(int color, PorterDuff.Mode mode) {
			if (mode == PorterDuff.Mode.CLEAR || mode == PorterDuff.Mode.SRC) {
				if (getSaveCount() != 1)
					throw new UnsupportedOperationException("Clearing a saved Surface canvas");
				snapshot = nativeResetCanvas(snapshot);
				if (mode == PorterDuff.Mode.CLEAR)
					return;
			} else if (mode != PorterDuff.Mode.SRC_OVER) {
				throw new UnsupportedOperationException("Unsupported Surface color blend: " + mode);
			}
			Paint paint = new Paint();
			paint.setColor(color);
			drawRect(0, 0, width, height, paint);
		}
	}
	private static native long nativeResetCanvas(long snapshot);
	private static native byte[] nativePostCanvas(long snapshot, int width, int height);
}
