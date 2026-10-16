package android.graphics;

import android.os.Parcelable;
import android.os.Parcel;
import android.os.BadParcelableException;
import android.util.DisplayMetrics;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/*
 * Bitmap is implemented as GdkTexture or GtkSnapshot. It can only be one of the two at a time.
 * The methods getTexture() and getSnapshot() automatically convert between the two as needed.
 */
public final class Bitmap implements Parcelable {
    @Override public synchronized void writeToParcel(Parcel dest, int flags) {
        if (recycled) throw new IllegalStateException("Bitmap is recycled");
        if (config.gdk_memory_format == -1)
            throw new UnsupportedOperationException("Unsupported bitmap format: " + config);
        Config parcelConfig = config == Config.HARDWARE ? Config.ARGB_8888 : config;
        long count = (long) stride * height;
        if (count <= 0 || count > Integer.MAX_VALUE)
            throw new IllegalStateException("Bitmap is too large to parcel");
        ByteBuffer buffer = ByteBuffer.allocate((int) count);
        native_copy_to_buffer(getTexture(), buffer, config.gdk_memory_format, stride);
        dest.writeInt(width);
        dest.writeInt(height);
        dest.writeString(parcelConfig.name());
        dest.writeInt(mutable ? 1 : 0);
        dest.writeInt(hasAlpha ? 1 : 0);
        dest.writeInt(density);
        dest.writeByteArray(buffer.array());
    }

    public static final Parcelable.Creator<Bitmap> CREATOR = new Parcelable.Creator<Bitmap>() {
        @Override public Bitmap createFromParcel(Parcel in) {
            int width = in.readInt(), height = in.readInt();
            Config config;
            try { config = Config.valueOf(in.readString()); }
            catch (RuntimeException e) { throw new BadParcelableException(e); }
            boolean mutable = in.readInt() != 0, alpha = in.readInt() != 0;
            int density = in.readInt();
            byte[] pixels = in.createByteArray();
            long row = ((long) width * config.bytes_per_pixel + 3) & ~3L;
            long count = row * height;
            if (width <= 0 || height <= 0 || row > Integer.MAX_VALUE
                    || count > Integer.MAX_VALUE || config.gdk_memory_format == -1
                    || config == Config.HARDWARE || pixels == null || count != pixels.length)
                throw new BadParcelableException("Invalid bitmap parcel");
            Bitmap bitmap = new Bitmap(width, height, config);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
            bitmap.mutable = mutable;
            bitmap.hasAlpha = alpha;
            bitmap.density = density;
            return bitmap;
        }
        @Override public Bitmap[] newArray(int size) { return new Bitmap[size]; }
    };


	public enum Config {
		RGB_565(2, -1, /*ANDROID_BITMAP_FORMAT_RGB_565*/ 4),
		ARGB_8888(4, /*GDK_MEMORY_R8G8B8A8*/ 5, /**ANDROID_BITMAP_FORMAT_RGBA_8888*/ 1),
		ARGB_4444(2, -1, /*ANDROID_BITMAP_FORMAT_RGBA_4444*/ 7),
		ALPHA_8(1, /*GDK_MEMORY_A8*/ 24, /*ANDROID_BITMAP_FORMAT_A_8*/ 8),
		RGBA_F16(8, /*GDK_MEMORY_R16G16B16A16_FLOAT*/ 14, /*ANDROID_BITMAP_FORMAT_RGBA_F16*/ 9),
		HARDWARE(4, /*GDK_MEMORY_R8G8B8A8*/ 5, /*ANDROID_BITMAP_FORMAT_RGBA_8888*/ 1);

		private int bytes_per_pixel;
		private int gdk_memory_format;
		int android_memory_format; // used by native function AndroidBitmap_getInfo()

		private Config(int bytes_per_pixel, int gdk_memory_format, int android_memory_format) {
			this.bytes_per_pixel = bytes_per_pixel;
			this.gdk_memory_format = gdk_memory_format;
			this.android_memory_format = android_memory_format;
		}
	}

	public enum CompressFormat {
		JPEG,
		PNG,
		WEBP,
		WEBP_LOSSY,
		WEBP_LOSSLESS,
	}

	private int width;
	private int height;
	private int stride;
	private long texture;
	private long snapshot;
	private Config config = Config.ARGB_8888;
	private boolean hasAlpha = true;
	long bytes = 0; // used by native function AndroidBitmap_lockPixels()
	private boolean recycled = false;
	boolean mutable = true;
	public static final int DENSITY_NONE = 0;
	private int density = DisplayMetrics.DENSITY_DEVICE;

	Bitmap(long texture) {
		this(native_get_width(texture), native_get_height(texture), Config.ARGB_8888);
		this.texture = texture;
	}

	private Bitmap(int width, int height, Config config) {
		this.config = config;
		this.width = width;
		this.height = height;
		int stride = width * config.bytes_per_pixel;
		this.stride = (stride + 3) & ~3; // 4-byte alignment
	}

	public static Bitmap createBitmap(int width, int height, Config config) {
		return new Bitmap(width, height, config);
	}

	public static Bitmap createBitmap(DisplayMetrics metrics, int width, int height, Config config) {
		Bitmap bitmap = new Bitmap(width, height, config);
		if (metrics != null) bitmap.density = metrics.densityDpi;
		return bitmap;
	}

	public static Bitmap createBitmap(DisplayMetrics metrics, int width, int height, Config config, boolean hasAlpha, ColorSpace colorSpace) {
		Bitmap bitmap = new Bitmap(width, height, config);
		bitmap.hasAlpha = hasAlpha;
		if (metrics != null) bitmap.density = metrics.densityDpi;
		return bitmap;
	}

	public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
		Bitmap dest = new Bitmap(width, height, src.getConfig());
		dest.density = src.density;
		new Canvas(dest).drawBitmap(src, new Rect(x, y, x + width, y + height), new Rect(0, 0, width, height), null);
		return dest;
	}

	public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height, Matrix matrix, boolean filter) {
		Bitmap dest = new Bitmap(width, height, src.getConfig());
		dest.density = src.density;
		Canvas canvas = new Canvas(dest);
		canvas.concat(matrix);
		canvas.drawBitmap(src, new Rect(x, y, x + width, y + height), new Rect(0, 0, width, height), null);
		return dest;
	}

	public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
		return createBitmap(width, height, config);
	}

	public static Bitmap createBitmap(Bitmap src) {
		Bitmap bitmap = new Bitmap(native_ref_texture(src.getTexture()));
		bitmap.density = src.density;
		return bitmap;
	}

	public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
		Bitmap dest = new Bitmap(dstWidth, dstHeight, src.getConfig());
		dest.density = src.density;
		new Canvas(dest).drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new Rect(0, 0, dstWidth, dstHeight), null);
		return dest;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public Config getConfig() {
		return config;
	}

	public synchronized long getTexture() {
		if (texture == 0) {
			texture = native_create_texture(snapshot, width, height, stride, config.gdk_memory_format);
			snapshot = 0;
		}
		return texture;
	}

	synchronized long getSnapshot() {
		if (snapshot == 0) {
			snapshot = native_create_snapshot(texture);
			texture = 0;
		}
		return snapshot;
	}

	public void eraseColor(int color) {
		if (color == Color.TRANSPARENT) {
			native_recycle(texture, snapshot);
			snapshot = native_erase_color(color, width, height);
			texture = 0;
		} else {
			Paint paint = new Paint();
			paint.setColor(color);
			new Canvas(this).drawRect(0, 0, width, height, paint);
		}
	}

	/* Drops the native buffer this bitmap currently owns, without marking it recycled.
	 * The native pixel-buffer API (AndroidBitmap_lockPixels/unlockPixels, see
	 * libandroid/bitmap.c) replaces the texture in place, and AOSP leaves the bitmap
	 * fully usable there; only recycle() may set the recycled flag. */
	void releaseNativeBuffers() {
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = 0;
	}

	public void recycle() {
		releaseNativeBuffers();
		recycled = true;
	}

	public int getRowBytes() {
		return stride;
	}

	// used by BitmapFactory to satify Options.inBitmap if set
	void transferFrom(Bitmap src) {
		native_recycle(this.texture, this.snapshot);
		this.texture = src.texture;
		this.snapshot = src.snapshot;
		this.width = src.width;
		this.height = src.height;
		this.stride = src.stride;
		this.config = src.config;
		this.hasAlpha = src.hasAlpha;
		this.mutable = src.mutable;
		this.density = src.density;
		this.recycled = false;
		src.texture = 0;
		src.snapshot = 0;
		src.recycled = true;
	}

	public int getAllocationByteCount() {
		return height * getRowBytes();
	}

	public void prepareToDraw() {
		getTexture();
	}

	public void setDensity(int density) { this.density = density; }

	public int getDensity() { return density; }

	public static int scaleFromDensity(int size, int source, int target) {
		if (source == 0 || target == 0 || source == target) return size;
		return (int)(((long) size * target + source / 2) / source);
	}

	public int getScaledWidth(int density) {
		return scaleFromDensity(width, this.density, density);
	}

	public int getScaledHeight(int density) {
		return scaleFromDensity(height, this.density, density);
	}

	public int getScaledWidth(Canvas canvas) { return getScaledWidth(canvas.getDensity()); }
	public int getScaledHeight(Canvas canvas) { return getScaledHeight(canvas.getDensity()); }
	public int getScaledWidth(DisplayMetrics metrics) { return getScaledWidth(metrics.densityDpi); }
	public int getScaledHeight(DisplayMetrics metrics) { return getScaledHeight(metrics.densityDpi); }

	public boolean isRecycled() {
		return recycled;
	}

	public void setHasAlpha(boolean hasAlpha) {
		this.hasAlpha = hasAlpha;
	}

	public boolean hasAlpha() {
		return hasAlpha;
	}

	/**
	 * The colour space of this bitmap. ATL decodes and composites every bitmap
	 * as 8-bit sRGB (GTK/GSK has no wide-gamut/HDR path), so this is always the
	 * sRGB colour space and {@link ColorSpace#isWideGamut()} is false - it never
	 * claims a gamut the renderer cannot deliver.
	 */
	public ColorSpace getColorSpace() {
		return ColorSpace.get(ColorSpace.Named.SRGB);
	}

	public Bitmap copy(Bitmap.Config config, boolean isMutable) {
		Bitmap bitmap = new Bitmap(width, height, config);
		bitmap.texture = native_ref_texture(getTexture());
		bitmap.density = density;
		return bitmap;
	}

	public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
		if (width == 0 || height == 0)
			return;
		checkPixelsAccess(x, y, width, height, offset, stride, pixels);
		native_get_pixels(getTexture(), pixels, offset, stride, x, y, width, height);
	}

	// Same argument validation as AOSP Bitmap.checkPixelsAccess: the native
	// side reads the rectangle from the texture and writes it to the caller's
	// array, so both must be checked before the call.
	private void checkPixelsAccess(int x, int y, int width, int height, int offset, int stride, int[] pixels) {
		if (width < 0 || height < 0)
			throw new IllegalArgumentException("width and height must be >= 0");
		if (x < 0 || (long)x + width > getWidth())
			throw new IllegalArgumentException("x + width must be <= bitmap.width()");
		if (y < 0 || (long)y + height > getHeight())
			throw new IllegalArgumentException("y + height must be <= bitmap.height()");
		if (Math.abs((long)stride) < width)
			throw new IllegalArgumentException("abs(stride) must be >= width");
		long lastScanline = offset + (height - 1L) * stride;
		if (offset < 0 || ((long)offset + width > pixels.length)
				|| lastScanline < 0 || (lastScanline + width > pixels.length))
			throw new ArrayIndexOutOfBoundsException();
	}

	public int getPixel(int x, int y) {
		int[] pixels = new int[1];
		getPixels(pixels, 0, 1, x, y, 1, 1);
		return pixels[0];
	}

	public void copyPixelsToBuffer(Buffer buffer) {
		if (config.gdk_memory_format == -1) {
			System.out.println("copyPixelsToBuffer: format " + config.name() + " not implemented");
			System.exit(1);
		}
		native_copy_to_buffer(getTexture(), buffer, config.gdk_memory_format, getRowBytes());
		buffer.position(buffer.position() + getAllocationByteCount());
	}

	public synchronized void copyPixelsFromBuffer(Buffer buffer) {
		if (recycled)
			throw new IllegalStateException("Bitmap is recycled");
		if (config == Config.HARDWARE)
			throw new IllegalStateException("Hardware bitmap cannot be written");
		if (config.gdk_memory_format == -1)
			throw new UnsupportedOperationException("Unsupported bitmap format: " + config);
		int shift;
		if (buffer instanceof ByteBuffer)
			shift = 0;
		else if (buffer instanceof ShortBuffer)
			shift = 1;
		else if (buffer instanceof IntBuffer)
			shift = 2;
		else
			throw new RuntimeException("Unsupported buffer type");
		int count = getByteCount();
		if (((long)buffer.remaining() << shift) < count)
			throw new RuntimeException("Buffer is too small for bitmap pixels");
		byte[] pixels = new byte[count];
		ByteBuffer packed = ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder());
		if (shift == 0)
			((ByteBuffer)buffer).duplicate().get(pixels);
		else if (shift == 1) {
			ShortBuffer source = ((ShortBuffer)buffer).duplicate();
			while (packed.remaining() >= 2)
				packed.putShort(source.get());
		} else {
			IntBuffer source = ((IntBuffer)buffer).duplicate();
			while (packed.remaining() >= 4)
				packed.putInt(source.get());
		}
		long replacement = native_texture_from_bytes(pixels, width, height, stride, config.gdk_memory_format);
		native_recycle(texture, snapshot);
		texture = replacement;
		snapshot = 0;
		buffer.position(buffer.position() + (count >> shift));
	}

	public int getByteCount() {
		return getAllocationByteCount();
	}

	public boolean isMutable() {
		return mutable;
	}

	public boolean compress(Bitmap.CompressFormat format, int quality, OutputStream stream) throws IOException {
		if (format == CompressFormat.PNG) {
			stream.write(native_save_to_png(getTexture()));
			return true;
		} else {
			stream.write(("fixme Bitmap.compress " + format.name()).getBytes());
			return false;
		}
	}

	public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
		if (recycled || !mutable) throw new IllegalStateException("bitmap is not writable");
        if (width == 0 || height == 0) return;
        checkPixelsAccess(x, y, width, height, offset, stride, pixels);
        long replacement = native_set_pixels(getTexture(), pixels, offset, stride, x, y, width, height);
        if (replacement != 0) {
            native_recycle(texture, 0);
            texture = replacement;
        }
	}

	public void setPixel(int x, int y, int color) {
		int[] pixels = new int[] {color};
		setPixels(pixels, 0, 1, x, y, 1, 1);
	}

	public void reconfigure(int width, int height, Bitmap.Config config) {}

	public void setPremultiplied(boolean premultiplied) {}

	public Bitmap extractAlpha() {
		return this.copy(config, mutable);
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void finalize() throws Throwable {
		try {
			recycle();
		} finally {
			super.finalize();
		}
	}

	private static native long native_create_snapshot(long texture);
	private static native long native_texture_from_bytes(byte[] pixels, int width, int height, int stride, int format);
	private static native long native_create_texture(long snapshot, int width, int height, int stride, int format);
	private static native int native_get_width(long texture);
	private static native int native_get_height(long texture);
	private static native long native_erase_color(int color, int width, int height);
	private static native void native_recycle(long texture, long snapshot);
	private static native long native_ref_texture(long texture);
	private static native void native_get_pixels(long texture, int[] pixels, int offset, int stride, int x, int y, int width, int height);
	private static native void native_copy_to_buffer(long texture, Buffer buffer, int memory_format, int stride);
	private static native byte[] native_save_to_png(long texture);
	private static native long native_set_pixels(long texture, int[] pixels, int offset, int stride, int x, int y, int width, int height);
}
