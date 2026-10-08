// SPDX-License-Identifier: GPL-3.0-only
package android.media;

import android.graphics.Rect;
import java.nio.ByteBuffer;

/** Base contract for images supplied by media producers. */
public abstract class Image implements AutoCloseable {
	private Rect cropRect;

	protected Image() {}
	public abstract int getFormat();
	public abstract int getWidth();
	public abstract int getHeight();
	public abstract long getTimestamp();
	public abstract Plane[] getPlanes();
	@Override
	public abstract void close();

	public Rect getCropRect() {
		return cropRect == null ? new Rect(0, 0, getWidth(), getHeight()) : new Rect(cropRect);
	}

	public void setCropRect(Rect rect) {
		cropRect = rect == null ? null : new Rect(rect);
		if (cropRect != null && !cropRect.intersect(0, 0, getWidth(), getHeight()))
			cropRect.setEmpty();
	}

	public abstract static class Plane {
		protected Plane() {}
		public abstract int getRowStride();
		public abstract int getPixelStride();
		public abstract ByteBuffer getBuffer();
	}
}
