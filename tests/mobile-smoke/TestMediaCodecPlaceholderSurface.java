// SPDX-License-Identifier: GPL-3.0-only
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

public final class TestMediaCodecPlaceholderSurface {
	public static void run() throws Exception {
		MediaCodec codec = MediaCodec.createByCodecName("vp9");
		try {
			MediaFormat format = new MediaFormat();
			format.setString("mime", "video/x-vnd.on2.vp9");
			format.setInteger("width", 320);
			format.setInteger("height", 240);
			// Mirrors ExoPlayer's startup PlaceholderSurface: no GTK widget exists
			// yet, but codec setup must succeed so a later Surface can be attached.
			codec.configure(format, new Surface(), null, 0);
			codec.start();
			System.out.println("PASS: MediaCodec accepts a headless placeholder output surface");
		} finally {
			codec.release();
		}
	}
}
