// SPDX-License-Identifier: GPL-3.0-only
/*
 * Unit-level acceptance probe for ATL's libavformat-backed
 * android.media.MediaExtractor / MediaMetadataRetriever subset
 * (src/api-impl/android/media/MediaExtractor.java,
 *  src/api-impl-jni/media/android_media_MediaExtractor.c).
 *
 * Fixtures are synthetic and generated host-side by fixtures.py; the directory
 * is passed through ATL_MEDIA_FIXTURES (absolute path, no app data is used).
 *
 * Expected properties are the ones the fixture generator constructed; a pass
 * therefore proves the metadata came from the real container headers rather
 * than from the file name or a canned value.
 *
 * Emits "MEDIA_EXTRACTOR PASS" / "MEDIA_EXTRACTOR FAIL" and always terminates
 * with the line "MEDIA_EXTRACTOR_TEST_DONE" so the bounded runner can stop.
 */
package org.atl.mediaextractor;

import android.app.Activity;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class ExtractorProbe extends Activity {

	public static final String MARKER = "MEDIA_EXTRACTOR_TEST_DONE";
	public static final String PASS = "MEDIA_EXTRACTOR PASS";
	public static final String FAIL = "MEDIA_EXTRACTOR FAIL";

	private final List<String> failures = new ArrayList<String>();
	private final StringBuilder report = new StringBuilder();
	private int checks = 0;

	interface Attempt {
		void run() throws Exception;
	}

	/** Mirror every result line to stdout (marker surface) and to the artifact. */
	private void out(String line) {
		report.append(line).append('\n');
		System.out.println(line);
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		try {
			String dir = System.getenv("ATL_MEDIA_FIXTURES");
			if (dir == null || dir.length() == 0)
				throw new IOException("ATL_MEDIA_FIXTURES is not set");
			runAll(new File(dir));
		} catch (Throwable t) {
			check("harness completed", false);
			out("HARNESS EXCEPTION: " + t);
			t.printStackTrace(System.out);
		}
		out("--- checks=" + checks + " failures=" + failures.size());
		for (String f : failures)
			out("FAILURE: " + f);
		out(failures.isEmpty() ? PASS : FAIL);
		writeResults();
		out(MARKER);
		System.out.flush();
		finish();
	}

	/** The bounded runner collects getFilesDir()/results.json as the artifact. */
	private void writeResults() {
		try {
			File file = new File(getFilesDir(), "results.json");
			String body = "{\"status\":\"" + (failures.isEmpty() ? "PASS" : "FAIL")
			    + "\",\"checks\":" + checks + ",\"failures\":" + failures.size()
			    + ",\"log\":" + json(report.toString()) + "}\n";
			FileOutputStream fos = new FileOutputStream(file);
			try {
				fos.write(body.getBytes("UTF-8"));
			} finally {
				fos.close();
			}
			System.out.println("RESULTS_WRITTEN " + file);
		} catch (Throwable t) {
			System.out.println("RESULTS_WRITE_FAILED " + t);
		}
		System.out.flush();
	}

	private static String json(String text) {
		StringBuilder b = new StringBuilder("\"");
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			case '\t': b.append("\\t"); break;
			default:
				if (c < 0x20)
					b.append(String.format("\\u%04x", (int) c));
				else
					b.append(c);
			}
		}
		return b.append('"').toString();
	}

	/* ---- assertions ---------------------------------------------------- */

	private void check(String what, boolean ok) {
		checks++;
		if (!ok)
			failures.add(what);
		out((ok ? "ok   " : "FAIL ") + what);
	}

	private void checkEq(String what, Object expected, Object actual) {
		check(what + " [expected=" + expected + " actual=" + actual + "]",
		      expected == null ? actual == null : expected.equals(actual));
	}

	private Throwable attempt(Attempt a) {
		try {
			a.run();
			return null;
		} catch (Throwable t) {
			return t;
		}
	}

	/* ---- metadata helpers ---------------------------------------------- */

	private MediaFormat formatOf(MediaExtractor ex, int index) {
		return ex.getTrackFormat(index);
	}

	private String stringOf(MediaExtractor ex, int index, String key) {
		return formatOf(ex, index).getString(key);
	}

	private int intOf(MediaExtractor ex, int index, String key) {
		return formatOf(ex, index).getInteger(key);
	}

	private long longOf(MediaExtractor ex, int index, String key) {
		return formatOf(ex, index).getLong(key);
	}

	private static byte[] readFile(File f) throws IOException {
		byte[] data = new byte[(int) f.length()];
		FileInputStream in = new FileInputStream(f);
		try {
			int done = 0;
			while (done < data.length) {
				int n = in.read(data, done, data.length - done);
				if (n < 0)
					break;
				done += n;
			}
		} finally {
			in.close();
		}
		return data;
	}

	/* ---- the checks ----------------------------------------------------- */

	private void runAll(File dir) throws Exception {
		File wav = new File(dir, "tone-44100-stereo-16bit.wav");
		File wav2 = new File(dir, "tone-22050-mono-8bit.wav");
		File mp3 = new File(dir, "tone-48000-mono.mp3");
		File flac = new File(dir, "tone-32000-stereo.flac");
		File wrapped = new File(dir, "wrapped-tone.wav");
		File empty = new File(dir, "empty.bin");
		File malformed = new File(dir, "malformed.wav");
		File text = new File(dir, "not-media.txt");
		File missing = new File(dir, "does-not-exist.wav");

		knownWav(wav, 44100, 2, 16, 2000);
		knownWav(wav2, 22050, 1, 8, 3000);
		secondFormat(mp3, "audio/mpeg", 48000, 1, false);
		secondFormat(flac, "audio/flac", 32000, 2, true);

		secondFormat(new File(dir,"alaw.wav"), "audio/g711-alaw",8000,1,false);
		secondFormat(new File(dir,"mulaw.wav"), "audio/g711-mlaw",8000,1,false);
		section("mixed container rejects unsupported video track", new Attempt() {
			public void run() throws Exception {
				MediaExtractor e = new MediaExtractor();
				try {
					e.setDataSource(new File(dir,"mixed.mp4").getPath());
					check("mixed container two streams", e.getTrackCount()==2);
					boolean rejected=false, audio=false;
					for(int i=0;i<2;i++) {
						try {audio |= "audio/mp4a-latm".equals(e.getTrackFormat(i).getString("mime"));}
						catch(UnsupportedOperationException expected) {rejected=true;}
					}
					check("video not mislabeled audio", rejected && audio);
				} finally {e.release();}
			}
		});
		section("file descriptor semantics", new Attempt() {
			public void run() throws Exception { descriptorSemantics(wav); }
		});
		section("file descriptor offset/length", new Attempt() {
			public void run() throws Exception { descriptorWindow(wav, wrapped); }
		});
		section("release/reopen semantics", new Attempt() {
			public void run() { releaseSemantics(wav); }
		});
		section("MediaMetadataRetriever", new Attempt() {
			public void run() { retriever(wav, malformed); }
		});
		section("argument validation", new Attempt() {
			public void run() throws Exception { argumentValidation(wav); }
		});
		rejectedInputs(new File[] {empty, malformed, text, missing, dir});
	}

	private void section(String name, Attempt a) {
		Throwable t = attempt(a);
		if (t != null) {
			check("section '" + name + "' completed: " + t, false);
			t.printStackTrace(System.out);
		}
	}

	/** Known-property PCM WAV: header values must match what was constructed. */
	private void knownWav(File f, int rate, int channels, int bits, int durationMs) {
		MediaExtractor ex = new MediaExtractor();
		try {
			ex.setDataSource(f.getPath());
			int tracks = ex.getTrackCount();
			check(f.getName() + " track count (" + tracks + ")", tracks == 1);
			if (tracks < 1)
				return;
			out("METADATA " + f.getName() + " " + formatOf(ex, 0));
			checkEq(f.getName() + " mime", "audio/raw", stringOf(ex, 0, "mime"));
			checkEq(f.getName() + " sample-rate", Integer.valueOf(rate), Integer.valueOf(intOf(ex, 0, "sample-rate")));
			checkEq(f.getName() + " channel-count", Integer.valueOf(channels), Integer.valueOf(intOf(ex, 0, "channel-count")));
			checkEq(f.getName() + " bits-per-sample", Integer.valueOf(bits), Integer.valueOf(intOf(ex, 0, "bits-per-sample")));
			long durUs = longOf(ex, 0, "durationUs");
			long deltaUs = Math.abs(durUs - (long) durationMs * 1000L);
			check(f.getName() + " durationUs=" + durUs + " expected~" + (durationMs * 1000L), deltaUs <= 30000);
		} catch (Throwable t) {
			check(f.getName() + " opened: " + t, false);
		} finally {
			ex.release();
		}
	}

	/** A second and third container: proves the mapping is per-codec, not fixed. */
	private void secondFormat(File f, String mime, int rate, int channels, boolean checkBits) {
		MediaExtractor ex = new MediaExtractor();
		try {
			ex.setDataSource(f.getPath());
			int tracks = ex.getTrackCount();
			check(f.getName() + " track count (" + tracks + ")", tracks >= 1);
			if (tracks < 1)
				return;
			out("METADATA " + f.getName() + " " + formatOf(ex, 0));
			checkEq(f.getName() + " mime", mime, stringOf(ex, 0, "mime"));
			checkEq(f.getName() + " sample-rate", Integer.valueOf(rate), Integer.valueOf(intOf(ex, 0, "sample-rate")));
			checkEq(f.getName() + " channel-count", Integer.valueOf(channels), Integer.valueOf(intOf(ex, 0, "channel-count")));
			if (checkBits)
				checkEq(f.getName() + " bits-per-sample", Integer.valueOf(16), Integer.valueOf(intOf(ex, 0, "bits-per-sample")));
			check(f.getName() + " durationUs > 0", longOf(ex, 0, "durationUs") > 0);
		} catch (Throwable t) {
			check(f.getName() + " opened: " + t, false);
		} finally {
			ex.release();
		}
	}

	/** FileDescriptor open must dup and use pread: position and content intact. */
	private void descriptorSemantics(File wav) throws Exception {
		byte[] bytes = readFile(wav);
		RandomAccessFile raf = new RandomAccessFile(wav, "r");
		try {
			raf.seek(1234);
			MediaExtractor ex = new MediaExtractor();
			ex.setDataSource(raf.getFD());
			check("fd position preserved (" + raf.getFilePointer() + ")", raf.getFilePointer() == 1234);
			checkEq("fd open sample-rate", Integer.valueOf(44100), Integer.valueOf(intOf(ex, 0, "sample-rate")));
			checkEq("fd open channel-count", Integer.valueOf(2), Integer.valueOf(intOf(ex, 0, "channel-count")));
			check("fd readable after open (content intact)", raf.read() == (bytes[1234] & 0xff));
			ex.release();
			check("fd readable after release", raf.read() == (bytes[1235] & 0xff));
			check("fd position still " + raf.getFilePointer(), raf.getFilePointer() == 1236);
			raf.seek(0);
			check("fd seekable after extractor use", raf.read() == (bytes[0] & 0xff));
		} finally {
			raf.close();
		}
	}

	/** offset/length windows, and boundaries that must fail cleanly. */
	private void descriptorWindow(final File wav, final File wrapped) throws Exception {
		final long wavLen = wav.length();

		RandomAccessFile w = new RandomAccessFile(wrapped, "r");
		try {
			MediaExtractor ex = new MediaExtractor();
			ex.setDataSource(w.getFD(), 1000, wavLen);
			checkEq("fd window offset=1000 sample-rate", Integer.valueOf(44100), Integer.valueOf(intOf(ex, 0, "sample-rate")));
			checkEq("fd window offset=1000 mime", "audio/raw", stringOf(ex, 0, "mime"));
			ex.release();
			check("fd position preserved by windowed open", w.getFilePointer() == 0);

			// Window that contains only the junk prefix: must not crash, must fail.
			Throwable t1 = attempt(new Attempt() {
				public void run() throws Exception {
					MediaExtractor e = new MediaExtractor();
					try {
						e.setDataSource(new RandomAccessFile(wrapped, "r").getFD(), 0, 1000);
					} finally {
						e.release();
					}
				}
			});
			check("fd window without media rejected (IOException, got " + t1 + ")", t1 instanceof IOException);

			// length beyond EOF is clamped to the real file size and still works.
			MediaExtractor clamped = new MediaExtractor();
			clamped.setDataSource(w.getFD(), 1000, Long.MAX_VALUE);
			checkEq("fd window clamped to EOF", Integer.valueOf(44100), Integer.valueOf(intOf(clamped, 0, "sample-rate")));
			clamped.release();

			// A window too short for any header must fail cleanly.
			final FileDescriptor fd = w.getFD();
			Throwable t2 = attempt(new Attempt() {
				public void run() throws Exception {
					MediaExtractor e = new MediaExtractor();
					try {
						e.setDataSource(fd, 1000, 8);
					} finally {
						e.release();
					}
				}
			});
			check("fd window shorter than header rejected (IOException, got " + t2 + ")", t2 instanceof IOException);

			// offset past EOF must fail cleanly.
			Throwable t3 = attempt(new Attempt() {
				public void run() throws Exception {
					MediaExtractor e = new MediaExtractor();
					try {
						e.setDataSource(fd, 100000000, -1);
					} finally {
						e.release();
					}
				}
			});
			check("fd offset past EOF rejected (IOException, got " + t3 + ")", t3 instanceof IOException);
		} finally {
			w.close();
		}
	}

	private void releaseSemantics(File wav) {
		final MediaExtractor ex = new MediaExtractor();
		try {
			ex.setDataSource(wav.getPath());
			ex.release();
			Throwable t = attempt(new Attempt() {
				public void run() { ex.getTrackCount(); }
			});
			check("getTrackCount after release throws IllegalStateException (got " + t + ")", t instanceof IllegalStateException);
			Throwable t2 = attempt(new Attempt() {
				public void run() { ex.getTrackFormat(0); }
			});
			check("getTrackFormat after release throws IllegalStateException (got " + t2 + ")", t2 instanceof IllegalStateException);
		} catch (Throwable t) {
			check("release semantics: " + t, false);
		}
		ex.release();
		check("double release is a no-op", true);

		int reopened = 0;
		for (int i = 0; i < 100; i++) {
			MediaExtractor loop = new MediaExtractor();
			try {
				loop.setDataSource(wav.getPath());
				reopened += loop.getTrackCount();
				loop.release();
			} catch (Throwable t) {
				check("repeated open/release iteration " + i + ": " + t, false);
				loop.release();
			}
		}
		check("100 open/release cycles produced 100 tracks (" + reopened + ")", reopened == 100);

		MediaExtractor reuse = new MediaExtractor();
		int reuseTracks = 0;
		for (int i = 0; i < 20; i++) {
			try {
				reuse.setDataSource(wav.getPath());
				reuseTracks += reuse.getTrackCount();
			} catch (Throwable t) {
				check("reuse iteration " + i + ": " + t, false);
				break;
			}
		}
		reuse.release();
		check("20 setDataSource calls on one instance (" + reuseTracks + ")", reuseTracks == 20);
	}

	private void retriever(File wav, File malformed) {
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(wav.getPath());
			checkEq("retriever mime", "audio/raw", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
			checkEq("retriever sample rate", "44100", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE));
			checkEq("retriever bits per sample", "16", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE));
			checkEq("retriever has audio", "yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
			String duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
			check("retriever duration ~2000ms [" + duration + "]",
			      duration != null && Math.abs(Integer.parseInt(duration) - 2000) <= 30);
			check("retriever embedded picture is null", r.getEmbeddedPicture() == null);
			check("retriever frame is null", r.getFrameAtTime() == null);
			r.release();
			check("retriever metadata after release is null",
			      r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == null);
			r.release();
			check("retriever double release is a no-op", true);

			Throwable t = attempt(new Attempt() {
				public void run() { new MediaMetadataRetriever().setDataSource(malformed.getPath()); }
			});
			check("retriever rejects malformed (IllegalArgumentException, got " + t + ")", t instanceof IllegalArgumentException);
		} catch (Throwable t) {
			check("retriever: " + t, false);
		} finally {
			r.release();
		}
	}

	private void argumentValidation(final File wav) throws Exception {
		RandomAccessFile raf = new RandomAccessFile(wav, "r");
		final FileDescriptor fd = raf.getFD();
		try {
			Throwable t1 = attempt(new Attempt() {
				public void run() throws Exception { new MediaExtractor().setDataSource((String) null); }
			});
			check("null path -> IllegalArgumentException (got " + t1 + ")", t1 instanceof IllegalArgumentException);

			Throwable t2 = attempt(new Attempt() {
				public void run() throws Exception { new MediaExtractor().setDataSource((FileDescriptor) null); }
			});
			check("null fd -> IllegalArgumentException (got " + t2 + ")", t2 instanceof IllegalArgumentException);

			Throwable t3 = attempt(new Attempt() {
				public void run() throws Exception { new MediaExtractor().setDataSource(fd, -1, 10); }
			});
			check("negative offset -> IllegalArgumentException (got " + t3 + ")", t3 instanceof IllegalArgumentException);

			Throwable t4 = attempt(new Attempt() {
				public void run() throws Exception { new MediaExtractor().setDataSource(fd, 0, -5); }
			});
			check("length < -1 -> IllegalArgumentException (got " + t4 + ")", t4 instanceof IllegalArgumentException);
		} finally {
			raf.close();
		}
	}

	/** empty / malformed / unsupported / missing / directory: clean rejection. */
	private void rejectedInputs(File[] bad) {
		for (final File f : bad) {
			Throwable t = attempt(new Attempt() {
				public void run() throws Exception {
					MediaExtractor e = new MediaExtractor();
					try {
						e.setDataSource(f.getPath());
					} finally {
						e.release();
					}
				}
			});
			check("rejected " + f.getName() + " (IOException, got " + t + ")", t instanceof IOException);
		}
	}
}