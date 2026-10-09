// SPDX-License-Identifier: GPL-3.0-only
package android.hardware;

/** CPU-only BLOB buffers; GPU import and parcel transport are not supported. */
public final class HardwareBuffer implements AutoCloseable {
	public static final int BLOB = 0x21;
	public static final long USAGE_CPU_READ_RARELY = 2, USAGE_CPU_READ_OFTEN = 3,
		USAGE_CPU_WRITE_RARELY = 0x20, USAGE_CPU_WRITE_OFTEN = 0x30;
	private long nativeBuffer;
	private final int width, height, format, layers;
	private final long usage;
	private HardwareBuffer(long ptr, int width, int height, int format, int layers, long usage) {
		this.nativeBuffer = ptr; this.width = width; this.height = height;
		this.format = format; this.layers = layers; this.usage = usage;
	}
	public static native HardwareBuffer create(int width, int height, int format, int layers, long usage);
	public static boolean isSupported(int width, int height, int format, int layers, long usage) {
		long read = usage & 15, write = (usage >> 4) & 15;
		return width > 0 && height == 1 && layers == 1 && format == BLOB && (usage & ~255L) == 0 &&
			(read == 0 || read == 2 || read == 3) && (write == 0 || write == 2 || write == 3);
	}
	private void checkOpen() { if (nativeBuffer == 0) throw new IllegalStateException("HardwareBuffer is closed"); }
	public synchronized int getWidth() { checkOpen(); return width; }
	public synchronized int getHeight() { checkOpen(); return height; }
	public synchronized int getFormat() { checkOpen(); return format; }
	public synchronized int getLayers() { checkOpen(); return layers; }
	public synchronized long getUsage() { checkOpen(); return usage; }
	public synchronized boolean isClosed() { return nativeBuffer == 0; }
	public synchronized void close() { if (nativeBuffer != 0) { nativeRelease(nativeBuffer); nativeBuffer = 0; } }
	private static native void nativeRelease(long ptr);
	@Override protected void finalize() throws Throwable { try { close(); } finally { super.finalize(); } }
}
