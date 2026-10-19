package android.view;

import android.atl.GskCanvas;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;

public class Surface {
	public long widget;
	private java.lang.ref.WeakReference<SurfaceTexture> texture;
	private long nativeWindow;
	private boolean released;

    // SurfaceView owns this widget-backed surface's creation/destruction cycle.
    synchronized void atlSetWidget(long widget) {
        this.widget = widget;
        released = false;
    }
	private SurfaceCanvas lockedCanvas;

	public Surface() {}
	public Surface(SurfaceTexture surfaceTexture) {
		if (surfaceTexture == null)
			throw new IllegalArgumentException("Null SurfaceTexture");
		texture = new java.lang.ref.WeakReference<>(surfaceTexture);
	}
	// Used by ANativeWindow_toSurface. The passed reference is owned by this Surface.
	private Surface(long handle, SurfaceTexture consumer) {
		if (consumer != null) texture = new java.lang.ref.WeakReference<>(consumer);
		nativeWindow = handle;
	}
	private SurfaceTexture atlGetTexture() { return texture == null ? null : texture.get(); }
	public synchronized boolean isValid() {
		if (released) return false;
		if (nativeWindow != 0) return nativeIsValid(nativeWindow);
		SurfaceTexture consumer = atlGetTexture();
		return texture != null ? consumer != null && !consumer.isReleased() : widget != 0;
	}
	public synchronized Canvas lockHardwareCanvas() { return lockCanvas(null); }
	public synchronized Canvas lockCanvas(Rect dirty) {
		if (!isValid())
			throw new IllegalStateException("Invalid Surface");
		if (lockedCanvas != null)
			throw new IllegalStateException("Surface already locked");
		SurfaceTexture consumer = atlGetTexture();
		if (consumer == null)
			throw new UnsupportedOperationException("Use SurfaceHolder for widget surfaces");
		int[] size = consumer.atlBufferSize();
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
		SurfaceTexture consumer = atlGetTexture();
		if (!released && consumer != null)
			consumer.atlQueueFrame(rgba, frame.width, frame.height, System.nanoTime());
	}
	public synchronized void release() {
		released = true;
		long handle = nativeWindow;
		nativeWindow = 0;
		if (handle != 0) nativeRelease(handle);
	}
	@Override protected void finalize() throws Throwable {
		try { release(); } finally { super.finalize(); }
	}
	private static native void nativeRelease(long handle);
	private static native boolean nativeIsValid(long handle);

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
