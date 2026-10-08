package android.graphics;

import android.os.Handler;
import android.os.Looper;

/** Latest-frame queue with an external OpenGL texture consumer. */
public class SurfaceTexture {
	public interface OnFrameAvailableListener {
		void onFrameAvailable(SurfaceTexture surfaceTexture);
	}
	private final Looper callbackLooper;
	private OnFrameAvailableListener listener;
	private Handler handler;
	private boolean released, attached;
	private int textureName;
	private long context;
	private int width = 1, height = 1;
	private byte[] pendingPixels, currentPixels;
	private int pendingWidth, pendingHeight, currentWidth, currentHeight;
	private long pendingTimestamp, timestamp;

	public SurfaceTexture(int texName) {
		callbackLooper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
		textureName = texName;
		attached = true;
		context = nativeCurrentContext();
	}
	public SurfaceTexture(int texName, boolean singleBufferMode) {
		this(texName);
		if (singleBufferMode)
			throw new UnsupportedOperationException("Single-buffer SurfaceTexture is not implemented");
	}
	public SurfaceTexture(boolean singleBufferMode) {
		this(0, singleBufferMode);
		attached = false;
		context = 0;
	}
	private void checkReleased() {
		if (released)
			throw new IllegalStateException("SurfaceTexture is released");
	}
	public synchronized void setDefaultBufferSize(int width, int height) {
		checkReleased();
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("Invalid buffer size");
		this.width = width;
		this.height = height;
	}
	// Internal producer access for android.view.Surface.
	public synchronized int[] atlBufferSize() {
		checkReleased();
		return new int[] {width, height};
	}
	public synchronized void atlQueueFrame(byte[] rgba, int width, int height, long timestamp) {
		checkReleased();
		if (width <= 0 || height <= 0 || (long)width * height * 4 != rgba.length)
			throw new IllegalArgumentException("Invalid RGBA frame");
		pendingPixels = rgba;
		pendingWidth = width;
		pendingHeight = height;
		pendingTimestamp = timestamp;
		final OnFrameAvailableListener target = listener;
		if (target != null)
			handler.post(new Runnable() { @Override public void run() {
			synchronized (SurfaceTexture.this) {
				if (released || listener != target) return;
			}
			target.onFrameAvailable(SurfaceTexture.this);
		} });
	}
	public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {
		setOnFrameAvailableListener(listener, null);
	}
	public synchronized void setOnFrameAvailableListener(OnFrameAvailableListener listener, Handler handler) {
		this.listener = listener;
		this.handler = listener == null ? null : handler != null ? handler
		                                                         : new Handler(callbackLooper);
	}
	public synchronized void detachFromGLContext() {
		checkReleased();
		if (!attached)
			throw new IllegalStateException("Already detached");
		if (context != 0)
			nativeDetach(context, textureName);
		attached = false;
		context = 0;
	}
	public synchronized void attachToGLContext(int texName) {
		checkReleased();
		if (attached)
			throw new IllegalStateException("Already attached");
		long newContext = nativeAttach(texName);
		textureName = texName;
		context = newContext;
		attached = true;
		if (currentPixels != null)
			nativeUpdate(context, textureName, currentPixels, currentWidth, currentHeight);
	}
	public synchronized void updateTexImage() {
		checkReleased();
		if (!attached)
			throw new IllegalStateException("SurfaceTexture is detached");
		if (context == 0)
			context = nativeAttach(textureName);
		if (context != nativeCurrentContext())
			throw new IllegalStateException("Wrong GL context");
		if (pendingPixels == null)
			return;
		nativeUpdate(context, textureName, pendingPixels, pendingWidth, pendingHeight);
		currentPixels = pendingPixels;
		currentWidth = pendingWidth;
		currentHeight = pendingHeight;
		timestamp = pendingTimestamp;
		pendingPixels = null;
	}
	public synchronized void getTransformMatrix(float[] matrix) {
		if (matrix.length != 16)
			throw new IllegalArgumentException("Expected 16-element matrix");
		java.util.Arrays.fill(matrix, 0);
		matrix[0] = matrix[10] = matrix[15] = 1;
		// Canvas frames arrive top-row first; external textures use bottom-left coordinates.
		matrix[5] = -1;
		matrix[13] = 1;
	}
	public synchronized long getTimestamp() { return timestamp; }
	public synchronized boolean isReleased() { return released; }
	public synchronized void release() {
		released = true;
		pendingPixels = currentPixels = null;
		listener = null;
		handler = null;
	}
	private static native long nativeCurrentContext();
	private static native long nativeAttach(int textureName);
	private static native void nativeDetach(long context, int textureName);
	private static native void nativeUpdate(long context, int textureName, byte[] rgba, int width, int height);
}
