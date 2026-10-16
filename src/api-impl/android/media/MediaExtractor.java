package android.media;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;

/**
 * ABI/bionic binding for the demuxer subset of android.media.MediaExtractor.
 *
 * ATL has no MediaCodec pipeline here: this class only opens a container,
 * enumerates its tracks and exposes each track's MediaFormat. It is backed by
 * libavformat (already linked into the ATL media JNI library) so the metadata
 * is read from the real container/stream headers instead of guessed.
 *
 * Sample-buffer APIs (readSampleData/seekTo/getSampleTime/...) are deliberately
 * not provided: ATL cannot feed decoded samples and must not pretend to.
 */
public class MediaExtractor {

	private long native_handle;

	public MediaExtractor() {
	}

	/**
	 * Open a file by path. Throws IOException when the container cannot be
	 * opened or no stream is found.
	 */
	public void setDataSource(String path) throws IOException {
		if (path == null)
			throw new IllegalArgumentException("path must not be null");
		release();
		long handle = native_open_path(path);
		if (handle == 0)
			throw new IOException("MediaExtractor: cannot open " + path);
		native_handle = handle;
	}

	public void setDataSource(FileDescriptor fd) throws IOException {
		setDataSource(fd, 0, -1);
	}

	/**
	 * Open a byte range of a caller-owned descriptor. The descriptor is
	 * duplicated on the native side and read with pread(), so the caller's
	 * descriptor and its current file position are left untouched.
	 *
	 * @param length number of bytes to expose, or -1 for "until EOF"
	 */
	public void setDataSource(FileDescriptor fd, long offset, long length) throws IOException {
		if (fd == null)
			throw new IllegalArgumentException("fd must not be null");
		if (offset < 0)
			throw new IllegalArgumentException("offset must not be negative");
		if (length < -1)
			throw new IllegalArgumentException("length must be -1 or non-negative");
		release();
		long handle = native_open_fd(fd, offset, length);
		if (handle == 0)
			throw new IOException("MediaExtractor: cannot open descriptor");
		native_handle = handle;
	}

	public void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
		if (uri == null)
			throw new IllegalArgumentException("uri must not be null");
		String scheme = uri.getScheme();
		if (scheme == null || scheme.length() == 0 || "file".equalsIgnoreCase(scheme)) {
			setDataSource(uri.getPath());
			return;
		}
		if (context == null)
			throw new IllegalArgumentException("context must not be null for uri " + uri);
		ContentResolver resolver = context.getContentResolver();
		ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r");
		if (pfd == null)
			throw new IOException("MediaExtractor: no descriptor for " + uri);
		try {
			// native_open_fd dups the descriptor, so closing ours cannot
			// invalidate the native reader.
			setDataSource(pfd.getFileDescriptor());
		} finally {
			pfd.close();
		}
	}

	private void ensureOpen() {
		if (native_handle == 0)
			throw new IllegalStateException("MediaExtractor has been released or not initialized");
	}

	public int getTrackCount() {
		ensureOpen();
		return native_getTrackCount(native_handle);
	}

	/**
	 * @throws IndexOutOfBoundsException when index is not a valid track
	 */
	public MediaFormat getTrackFormat(int index) {
		ensureOpen();
		int count = native_getTrackCount(native_handle);
		if (index < 0 || index >= count)
			throw new IndexOutOfBoundsException("track index " + index + " out of range [0," + count + ")");
		MediaFormat format = new MediaFormat();
		String mime = native_getTrackMime(native_handle, index);
		if (mime != null)
			format.setString("mime", mime);
		int[] values = native_getTrackInts(native_handle, index);
		if (values != null) {
			if (values[0] > 0)
				format.setInteger("sample-rate", values[0]);
			if (values[1] > 0)
				format.setInteger("channel-count", values[1]);
			if (values[2] > 0)
				format.setInteger("bitrate", values[2]);
			if (values[3] > 0)
				format.setInteger("bits-per-sample", values[3]);
		}
		long duration_us = native_getTrackDurationUs(native_handle, index);
		if (duration_us > 0)
			format.setLong("durationUs", duration_us);
		return format;
	}

	/**
	 * Release the native demuxer. Idempotent; any later use of this instance
	 * fails with IllegalStateException.
	 */
	public void release() {
		if (native_handle != 0) {
			native_release(native_handle);
			native_handle = 0;
		}
	}

	protected void finalize() {
		release();
	}

	private static native long native_open_path(String path);

	private static native long native_open_fd(FileDescriptor fd, long offset, long length);

	private static native int native_getTrackCount(long handle);

	private static native String native_getTrackMime(long handle, int index);

	private static native int[] native_getTrackInts(long handle, int index);

	private static native long native_getTrackDurationUs(long handle, int index);

	private static native void native_release(long handle);
}
