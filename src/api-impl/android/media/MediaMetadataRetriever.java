package android.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.IOException;

/**
 * Metadata extraction for ATL, backed by the same libavformat demuxer as
 * {@link MediaExtractor}. Only header/packet-level metadata is available:
 * embedded pictures, frame extraction and decoded tags are not.
 */
public class MediaMetadataRetriever {

	public static final int METADATA_KEY_DURATION = 9;
	public static final int METADATA_KEY_MIMETYPE = 12;
	public static final int METADATA_KEY_HAS_AUDIO = 16;
	public static final int METADATA_KEY_BITRATE = 20;
	public static final int METADATA_KEY_SAMPLERATE = 38;
	public static final int METADATA_KEY_BITS_PER_SAMPLE = 39;

	private MediaExtractor extractor;
	private MediaFormat trackFormat;

	public void release() {
		if (extractor != null) {
			extractor.release();
			extractor = null;
			trackFormat = null;
		}
	}

	public void setDataSource(Context context, Uri uri) {
		MediaExtractor e = new MediaExtractor();
		try {
			e.setDataSource(context, uri, null);
		} catch (IOException ex) {
			e.release();
			throw new IllegalArgumentException("cannot open " + uri + ": " + ex);
		}
		adopt(e);
	}

	public void setDataSource(String path) {
		MediaExtractor e = new MediaExtractor();
		try {
			e.setDataSource(path);
		} catch (IOException ex) {
			e.release();
			throw new IllegalArgumentException("cannot open " + path + ": " + ex);
		}
		adopt(e);
	}

	private void adopt(MediaExtractor e) {
		release();
		extractor = e;
		trackFormat = pickTrack(e);
	}

	/** Audio tracks are preferred, which is what callers of this API want. */
	private static MediaFormat pickTrack(MediaExtractor e) {
		MediaFormat first = null;
		int count = e.getTrackCount();
		for (int i = 0; i < count; i++) {
			MediaFormat format = e.getTrackFormat(i);
			if (first == null)
				first = format;
			String mime = format.getString("mime");
			if (mime != null && mime.startsWith("audio/"))
				return format;
		}
		return first;
	}

	public byte[] getEmbeddedPicture() {
		return null;
	}

	public String extractMetadata(int key) {
		if (trackFormat == null)
			return null;
		switch (key) {
			case METADATA_KEY_MIMETYPE:
				return trackFormat.getString("mime");
			case METADATA_KEY_DURATION: {
				if (!trackFormat.containsKey("durationUs"))
					return null;
				long duration_us = trackFormat.getLong("durationUs");
				return duration_us > 0 ? String.valueOf(duration_us / 1000) : null;
			}
			case METADATA_KEY_HAS_AUDIO: {
				String mime = trackFormat.getString("mime");
				return mime != null && mime.startsWith("audio/") ? "yes" : "no";
			}
			case METADATA_KEY_BITRATE:
				return trackFormat.containsKey("bitrate") ? String.valueOf(trackFormat.getInteger("bitrate")) : null;
			case METADATA_KEY_SAMPLERATE:
				return trackFormat.containsKey("sample-rate") ? String.valueOf(trackFormat.getInteger("sample-rate")) : null;
			case METADATA_KEY_BITS_PER_SAMPLE:
				return trackFormat.containsKey("bits-per-sample") ? String.valueOf(trackFormat.getInteger("bits-per-sample")) : null;
			default:
				return null;
		}
	}

	public Bitmap getFrameAtTime(long time) {
		return null;
	}

	public Bitmap getFrameAtTime() {
		return null;
	}
}
