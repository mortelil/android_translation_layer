// SPDX-License-Identifier: GPL-3.0-only
package android.media;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;

/** RGBA image queue backed by ATL's Surface producer. */
public class ImageReader implements AutoCloseable {
	public interface OnImageAvailableListener {
		void onImageAvailable(ImageReader reader);
	}
	private final int width, height, format, maxImages;
	private final ArrayDeque<Frame> pending = new ArrayDeque<>();
	private final HashSet<Frame> acquired = new HashSet<>();
	private final SurfaceTexture producer;
	private final Surface surface;
	private boolean closed;
	private OnImageAvailableListener listener;
	private Handler handler;
	private ImageReader(int width, int height, int format, int maxImages) {
		if (width <= 0 || height <= 0 || maxImages < 1)
			throw new IllegalArgumentException("Invalid ImageReader dimensions/capacity");
		if (format != 1)
			throw new UnsupportedOperationException("ImageReader currently supports RGBA_8888 only");
		this.width = width;
		this.height = height;
		this.format = format;
		this.maxImages = maxImages;
		producer = new SurfaceTexture(false) {
			@Override
			public void atlQueueFrame(byte[] rgba, int w, int h, long timestamp) {
				queue(rgba, w, h, timestamp);
			}
		};
		producer.setDefaultBufferSize(width, height);
		surface = new Surface(producer);
	}
	public static ImageReader newInstance(int width, int height, int format, int maxImages) {
		return new ImageReader(width, height, format, maxImages);
	}
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public int getImageFormat() { return format; }
	public int getMaxImages() { return maxImages; }
	public synchronized Surface getSurface() {
		checkOpen();
		return surface;
	}
	private void checkOpen() {
		if (closed)
			throw new IllegalStateException("ImageReader is closed");
	}
	private synchronized void queue(byte[] pixels, int w, int h, long timestamp) {
		checkOpen();
		if (w != width || h != height || (long)w * h * 4 != pixels.length)
			throw new IllegalArgumentException("Image dimensions differ from reader");
		while (!closed && pending.size() >= maxImages) {
			try {
				wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Image producer interrupted", e);
			}
		}
		checkOpen();
		pending.add(new Frame(pixels, timestamp));
		final OnImageAvailableListener target = listener;
		if (target != null)
			handler.post(new Runnable() { @Override public void run() {
			synchronized (ImageReader.this) { if (closed || target!=listener) return; }
			target.onImageAvailable(ImageReader.this);
		} });
	}
	public synchronized void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
		checkOpen();
		this.listener = listener;
		this.handler = listener == null ? null : handler != null ? handler
		                                                         : new Handler(Looper.myLooper());
	}
	public synchronized Image acquireNextImage() {
		checkOpen();
		if (acquired.size() >= maxImages)
			throw new IllegalStateException("Maximum images already acquired");
		Frame frame = pending.poll();
		if (frame != null)
			acquired.add(frame);
		notifyAll();
		return frame;
	}
	public synchronized Image acquireLatestImage() {
		checkOpen();
		if (acquired.size() >= maxImages)
			throw new IllegalStateException("Maximum images already acquired");
		if (maxImages - acquired.size() >= 2)
			while (pending.size() > 1)
				pending.remove().valid = false;
		return acquireNextImage();
	}
	@Override
	public synchronized void close() {
		if (closed)
			return;
		closed = true;
		listener = null;
		handler = null;
		for (Frame frame : pending)
			frame.valid = false;
		for (Frame frame : acquired)
			frame.valid = false;
		pending.clear();
		acquired.clear();
		notifyAll();
		surface.release();
		producer.release();
	}
	public synchronized void discardFreeBuffers() { /* queued/acquired frames remain owned by the reader */ }
	private final class Frame extends Image {
		private boolean valid = true;
		private final ByteBuffer pixels;
		private final long timestamp;
		Frame(byte[] bytes, long timestamp) {
			pixels = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
			this.timestamp = timestamp;
		}
		private void check() {
			if (!valid)
				throw new IllegalStateException("Image is closed");
		}
		@Override
		public int getWidth() {
			check();
			return width;
		}
		@Override
		public int getHeight() {
			check();
			return height;
		}
		@Override
		public int getFormat() {
			check();
			return format;
		}
		@Override
		public long getTimestamp() {
			check();
			return timestamp;
		}
		@Override
		public Plane[] getPlanes() {
			check();
			return new Plane[] {new Plane(){
				@Override public int getRowStride(){check();
			return width * 4;
		}
		@Override
		public int getPixelStride() {
			check();
			return 4;
		}
		@Override
		public ByteBuffer getBuffer() {
			check();
			return pixels.duplicate();
		}
	}
};
}
@Override
public void close() {
	synchronized (ImageReader.this) {
		valid = false;
		acquired.remove(this);
		ImageReader.this.notifyAll();
	}
}
}
}
