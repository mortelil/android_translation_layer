package android.view;

import android.atl.GskCanvas;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;

public class Surface implements android.os.Parcelable {
	private android.os.IBinder producer;
	public long widget;
	private java.lang.ref.WeakReference<SurfaceTexture> texture;
	private long nativeWindow;
	private boolean released;

    // SurfaceView owns this widget-backed surface's creation/destruction cycle.
    synchronized void atlSetWidget(long widget) {
        this.widget = widget;
        producer = null;
        released = false;
    }
	private SurfaceCanvas lockedCanvas;

    // AOSP Surface parcel contract: transport a producer capability, never a
    // process-local widget pointer. ATL uses a private Binder protocol, not the
    // Android native Parcel wire format. Reference: android-16.0.0_r1 Surface.java.
    public int describeContents() { return 0; }
    public void writeToParcel(android.os.Parcel dest, int flags) {
        if (dest == null) throw new IllegalArgumentException("null parcel");
        android.os.IBinder endpoint;
        synchronized (this) { endpoint = producer; }
        if (endpoint == null && isValid()) {
            // Do not hold this monitor while acquisition dispatches to GTK.
            long handle = nativeAcquire(this);
            if (handle != 0) {
                synchronized (this) {
                    if (producer == null) producer = new Producer(handle);
                    else nativeRelease(handle);
                    endpoint = producer;
                }
            }
        }
        dest.writeStrongBinder(endpoint);
        if ((flags & android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) release();
    }
    public void readFromParcel(android.os.Parcel source) {
        if (source == null) throw new IllegalArgumentException("null parcel");
        android.os.IBinder endpoint = source.readStrongBinder();
        long handle = endpoint == null ? 0 : nativeRemote(new RemoteProducer(endpoint));
        synchronized (this) {
            release();
            producer = endpoint;
            nativeWindow = handle;
            widget = 0;
            texture = null;
            released = false;
        }
    }
    public static final android.os.Parcelable.Creator<Surface> CREATOR = new android.os.Parcelable.Creator<Surface>() {
        public Surface createFromParcel(android.os.Parcel source) {
            Surface surface = new Surface(); surface.readFromParcel(source); return surface;
        }
        public Surface[] newArray(int count) { return new Surface[count]; }
    };
    private static final String PROTOCOL = "android.atl.SurfaceProducer.v1";
    private static final class Producer extends android.os.Binder {
        private final long handle;
        Producer(long handle) { this.handle = handle; }
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(PROTOCOL);
            if (code == 1) {
                int[] size = nativeSize(handle);
                reply.writeNoException(); reply.writeInt(size[0]); reply.writeInt(size[1]);
            } else {
                int width = data.readInt(), height = data.readInt();
                android.os.ParcelFileDescriptor fd = data.readFileDescriptor();
                boolean queued = false;
                try { if (fd != null) queued = nativePresent(handle, fd.getFd(), width, height); }
                finally { if (fd != null) try { fd.close(); } catch (java.io.IOException ignored) {} }
                reply.writeNoException(); reply.writeInt(queued ? 1 : 0);
            }
            return true;
        }
        @Override protected void finalize() throws Throwable {
            try { nativeRelease(handle); } finally { super.finalize(); }
        }
    }
    // Retained by a native producer window; does not retain its Surface wrapper.
    private static final class RemoteProducer {
        private final android.os.IBinder endpoint;
        RemoteProducer(android.os.IBinder endpoint) { this.endpoint = endpoint; }
        private boolean transact(int code, android.os.Parcel data, android.os.Parcel reply) throws android.os.RemoteException {
            return endpoint.transact(code, data, reply, 0);
        }
        int[] size() {
            android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken(PROTOCOL);
                if (!transact(1, data, reply)) return new int[]{0,0};
                reply.readException(); return new int[]{reply.readInt(), reply.readInt()};
            } catch (android.os.RemoteException e) { return new int[]{0,0}; }
            finally { data.recycle(); reply.recycle(); }
        }
        boolean post(int fd, int width, int height) {
            android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
            try {
                android.os.ParcelFileDescriptor copy = android.os.ParcelFileDescriptor.fromFd(fd);
                try {
                    data.writeInterfaceToken(PROTOCOL); data.writeInt(width); data.writeInt(height);
                    data.writeFileDescriptor(copy.getFileDescriptor());
                    if (!transact(2, data, reply)) return false;
                    reply.readException(); return reply.readInt() != 0;
                } finally { copy.close(); }
            } catch (java.io.IOException e) { return false; }
              catch (android.os.RemoteException e) { return false; }
            finally { data.recycle(); reply.recycle(); }
        }
    }
    private static native long nativeAcquire(Surface surface);
    private static native long nativeRemote(RemoteProducer producer);
    private static native int[] nativeSize(long handle);
    private static native boolean nativePresent(long handle, int fd, int width, int height);

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
		producer = null;
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
