// SPDX-License-Identifier: GPL-3.0-only
package org.atl.tests.audio;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Discriminating contract probe for ATL's AudioTrack PCM backend.
 *
 * It is meant to FAIL on the rejected implementation (which accepted any sample
 * rate without confirming it, mishandled negative native errors, had no
 * non-blocking/blocking distinction, no owned lifecycle, and reported success
 * while dropping PCM) and PASS on a coherent owned PCM model.
 *
 * Side effects that are asserted, not inferred:
 *   - getMinBufferSize()/getNativeOutputSampleRate() values,
 *   - the exact transfer counts of byte[]/short[]/ByteBuffer writes,
 *   - the playback head position advancing while playing and freezing on pause,
 *   - a blocking write returning a SHORT count when interrupted by pause(),
 *   - use-after-release and unavailable-device error codes,
 *   - a real 1 kHz tone reaching the sink (verified host-side from the sink
 *     monitor recording, not from "no error"/PLAYING).
 *
 * Output: one `[AP] ASSERT ... PASS|FAIL` line per check and a final
 * `[AP] DONE failures=N sink=yes|no` line.
 */
public class AudioProbeActivity extends Activity {
	private static final String TAG = "[AP] ";
	private static final int CHANNEL_OUT_STEREO = AudioFormat.CHANNEL_OUT_STEREO; // 12
	private static final int ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT; // 2
	private static final int STREAM_MUSIC = 3;

	private static int failures = 0;
	private static final List<String> checks = new ArrayList<>();
	private static boolean sinkAvailable = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(new TextView(this));
		new Thread(new Runnable() {
			public void run() {
				try {
					runProbe();
				} catch (Throwable t) {
					check("probe_no_exception", false, "uncaught " + t);
					t.printStackTrace();
				}
				System.out.println(TAG + "RESULT failures=" + failures);
				writeResults();
				System.out.println(TAG + "AUDIO_PROBE_DONE failures=" + failures
				                   + " sink=" + (sinkAvailable ? "yes" : "no"));
				System.out.flush();
				// No System.exit(): the runner treats the marker as completion
				// and a self-exiting process as a launcher failure.
			}
		}, "audio-probe").start();
	}

	private static void check(String what, boolean ok, String detail) {
		if (!ok)
			failures++;
		String line = "ASSERT " + what + ": " + detail + " -> " + (ok ? "PASS" : "FAIL");
		checks.add(line);
		System.out.println(TAG + line);
		System.out.flush();
	}

	/** Same JSON convention as the other ATL probes: files/results.json plus a
	 *  final stdout marker, so completion is proven by durable side effects. */
	private void writeResults() {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"status\":\"").append(failures == 0 ? "OK" : "FAIL")
		  .append("\",\"failures\":").append(failures)
		  .append(",\"sink\":\"").append(sinkAvailable ? "yes" : "no")
		  .append("\",\"checks\":[");
		for (int i = 0; i < checks.size(); i++) {
			if (i > 0)
				sb.append(',');
			sb.append('\"').append(checks.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('\"');
		}
		sb.append("]}");
		try {
			File dir = getFilesDir();
			dir.mkdirs();
			FileOutputStream out = new FileOutputStream(new File(dir, "results.json"));
			out.write(sb.toString().getBytes("UTF-8"));
			out.close();
		} catch (Throwable t) {
			System.out.println(TAG + "results_json_write_failed " + t);
		}
	}

	private void runProbe() throws Exception {
		int rate = AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC);
		check("native_output_sample_rate", rate > 0, "rate=" + rate);
		if (rate <= 0)
			rate = 48000;

		// Parameter validation must not depend on a usable device.
		int badChannel = AudioTrack.getMinBufferSize(rate, 0x700, ENCODING_PCM_16BIT);
		check("min_buffer_invalid_channel_config", badChannel == AudioTrack.ERROR_BAD_VALUE,
		      "got=" + badChannel);
		int badRate = AudioTrack.getMinBufferSize(1000, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT);
		check("min_buffer_unsupported_rate", badRate == AudioTrack.ERROR_BAD_VALUE, "got=" + badRate);

		int min = AudioTrack.getMinBufferSize(rate, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT);
		int bufBytes = Math.max(min > 0 ? min : 4096, (rate / 5) * 4); // >= 200 ms of stereo s16

		AudioTrack t = new AudioTrack(STREAM_MUSIC, rate, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT,
		                              bufBytes, AudioTrack.MODE_STREAM);
		boolean sink = t.getState() == AudioTrack.STATE_INITIALIZED;
		sinkAvailable = sink;
		System.out.println(TAG + "SINK available=" + (sink ? "yes" : "no")
		                   + " rate=" + rate + " bufBytes=" + bufBytes);
		System.out.flush();

		if (!sink) {
			// No output device: the correct, honest behaviour is an uninitialized
			// track that refuses writes rather than pretending to play.
			check("uninitialized_state", t.getState() == AudioTrack.STATE_UNINITIALIZED, "state=" + t.getState());
			check("write_on_uninitialized", t.write(new byte[1024], 0, 1024) == AudioTrack.ERROR_INVALID_OPERATION,
			      "got=" + t.write(new byte[1024], 0, 1024));
			t.release();
			return;
		}

		check("track_initialized", true, "state=" + t.getState());
		check("min_buffer_size_positive", min > 0, "min=" + min);

		// ---- error handling on write parameters -------------------------------
		byte[] small = new byte[64];
		check("write_bad_range", t.write(small, 0, 128, AudioTrack.WRITE_BLOCKING) == AudioTrack.ERROR_BAD_VALUE,
		      "got=" + t.write(small, 0, 128, AudioTrack.WRITE_BLOCKING));
		check("write_invalid_mode_byte", t.write(small, 0, 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");
		check("write_invalid_mode_short", t.write(new short[64], 0, 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");
		check("write_invalid_mode_nio",
		      t.write(ByteBuffer.wrap(small), 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");

		// ---- queue while stopped: nothing may be reported as played ----------
		int framesPerChunk = rate / 50;                 // 20 ms
		int chunkBytes = framesPerChunk * 4;            // stereo s16
		byte[] chunk = toneBytes(1000.0, rate, framesPerChunk, 12000);
		int nw = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_NON_BLOCKING);
		check("write_nonblocking_accepts", nw >= 0 && nw <= chunkBytes, "wrote=" + nw);
		check("head_zero_while_stopped", t.getPlaybackHeadPosition() == 0,
		      "head=" + t.getPlaybackHeadPosition());

		// ---- nonblocking short count when the device is full -----------------
		int big = 8 * bufBytes;
		byte[] bigBlocks = new byte[big];
		int shortRet = t.write(bigBlocks, 0, big, AudioTrack.WRITE_NON_BLOCKING);
		check("write_nonblocking_short", shortRet > 0 && shortRet < big, "wrote=" + shortRet + "/" + big);

		// stop discards the queue and resets the head
		t.stop();
		check("stop_resets_head", t.getPlaybackHeadPosition() == 0, "head=" + t.getPlaybackHeadPosition());

		// ---- real tone: blocking writes, head advances, pause freezes it -----
		System.out.println(TAG + "TONE hz=1000 rate=" + rate + " seconds=4 channels=2");
		System.out.flush();
		t.play();
		long writtenBytes = 0;
		int totalChunks = 4 * 50;
		for (int i = 0; i < totalChunks; i++) {
			int r = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_BLOCKING);
			if (r > 0)
				writtenBytes += r;
			else {
				check("tone_blocking_write_full", false, "chunk " + i + " ret=" + r);
				break;
			}
		}
		check("tone_blocking_write_full", writtenBytes == (long) totalChunks * chunkBytes,
		      "bytes=" + writtenBytes + "/" + ((long) totalChunks * chunkBytes));

		Thread.sleep(700);
		int headPlaying = t.getPlaybackHeadPosition();
		check("head_advances_while_playing", headPlaying > 0, "head=" + headPlaying);

		t.pause();
		int headPaused = t.getPlaybackHeadPosition();
		Thread.sleep(300);
		int headPaused2 = t.getPlaybackHeadPosition();
		check("head_frozen_while_paused", headPaused == headPaused2,
		      "head1=" + headPaused + " head2=" + headPaused2);
		t.play();
		Thread.sleep(300);
		int headResumed = t.getPlaybackHeadPosition();
		// AOSP guarantees a monotonic head within a playback run: after an
		// underrun the position must not jump backwards (e.g. to 0).
		check("head_monotonic_after_resume", headResumed >= headPaused2,
		      "was=" + headPaused2 + " now=" + headResumed);

		// ---- blocking write interrupted by pause() returns a short count -----
		final AudioTrack ti = t;
		ti.pause();
		final byte[] interruptBlocks = new byte[20 * bufBytes];
		final int[] interruptRet = new int[1];
		Thread writer = new Thread(new Runnable() {
			public void run() {
				interruptRet[0] = ti.write(interruptBlocks, 0, interruptBlocks.length, AudioTrack.WRITE_BLOCKING);
			}
		}, "audio-interrupt-writer");
		writer.start();
		Thread.sleep(150);
		ti.pause();
		writer.join(3000);
		check("blocking_write_interrupted_short",
		      !writer.isAlive() && interruptRet[0] >= 0 && interruptRet[0] < interruptBlocks.length,
		      "ret=" + interruptRet[0] + "/" + interruptBlocks.length + " alive=" + writer.isAlive());

		// ---- writes must be accepted again after stop() ----------------------
		t.stop();
		int progress = 0;
		for (int i = 0; i < 10; i++) {
			int r = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_NON_BLOCKING);
			if (r > 0)
				progress += r;
			Thread.sleep(20);
		}
		check("nonblocking_progress_after_stop", progress > 0, "accepted=" + progress + " bytes");

		final int[] afterStop = new int[]{Integer.MIN_VALUE};
		Thread stopWriter = new Thread(new Runnable() {
			public void run() {
				afterStop[0] = ti.write(chunk, 0, chunkBytes, AudioTrack.WRITE_BLOCKING);
			}
		}, "audio-after-stop-writer");
		stopWriter.start();
		stopWriter.join(4000);
		boolean stopWriterStuck = stopWriter.isAlive();
		if (stopWriterStuck)
			ti.release(); // unblock so the probe can finish and report the defect
		check("blocking_write_after_stop_completes",
		      !stopWriterStuck && afterStop[0] == chunkBytes,
		      "ret=" + afterStop[0] + "/" + chunkBytes + " stuck=" + stopWriterStuck);
		if (stopWriterStuck) {
			System.out.println(TAG + "ABORT after stuck writer");
			System.out.flush();
			return;
		}

		// ---- shorts path and ByteBuffer (direct + read-only heap) ------------
		// Each path gets a fresh stopped track (empty device buffer) so a
		// non-blocking write has space; retry briefly because a legitimately
		// full device may return 0 first.
		short[] tone = toneShorts(1000.0, rate, framesPerChunk, 12000);
		t.stop();
		int rs = writeRetry(t, tone, 0, tone.length);
		check("write_shorts_count", rs > 0 && rs <= tone.length && rs % 2 == 0,
		      "shorts=" + rs + "/" + tone.length);

		// java.nio.Buffer explicitly: ByteBuffer.flip()/position(int) are only
		// overridden covariantly from Java 9 on, and the runtime core library
		// here still exposes the Java 8 signatures.
		t.stop();
		ByteBuffer direct = ByteBuffer.allocateDirect(chunkBytes);
		direct.put(chunk);
		((java.nio.Buffer) direct).flip();
		int rd = writeRetry(t, direct, chunkBytes);
		check("write_direct_bytebuffer", rd > 0 && rd <= chunkBytes && rd % 4 == 0
		      && ((java.nio.Buffer) direct).position() == rd,
		      "bytes=" + rd + " pos=" + ((java.nio.Buffer) direct).position());

		t.stop();
		ByteBuffer ro = ByteBuffer.wrap(chunk).asReadOnlyBuffer();
		int rr = writeRetry(t, ro, chunkBytes);
		check("write_readonly_heap_bytebuffer", rr > 0 && rr <= chunkBytes && rr % 4 == 0
		      && ((java.nio.Buffer) ro).position() == rr,
		      "bytes=" + rr + " pos=" + ((java.nio.Buffer) ro).position());

		t.stop();
		ByteBuffer heap = ByteBuffer.wrap(chunk);
		int rh = writeRetry(t, heap, chunkBytes);
		check("write_heap_bytebuffer", rh > 0 && rh <= chunkBytes && rh % 4 == 0
		      && ((java.nio.Buffer) heap).position() == rh,
		      "bytes=" + rh + " pos=" + ((java.nio.Buffer) heap).position());

		// ---- flush while stopped is honoured, head reset ---------------------
		t.stop();
		writeRetry(t, chunk, 0, chunkBytes);
		t.flush();
		check("flush_resets_head", t.getPlaybackHeadPosition() == 0, "head=" + t.getPlaybackHeadPosition());

		// ---- release then reuse ----------------------------------------------
		t.release();
		check("released_state_uninitialized", t.getState() == AudioTrack.STATE_UNINITIALIZED, "state=" + t.getState());
		check("write_after_release", t.write(chunk, 0, chunkBytes) == AudioTrack.ERROR_INVALID_OPERATION,
		      "got=" + t.write(chunk, 0, chunkBytes));
		t.release(); // must be harmless
		check("double_release_safe", true, "");
	}

	/** Non-blocking writes may legitimately return 0 while the device is full;
	 *  retry briefly so each code path is really exercised. */
	private static int writeRetry(AudioTrack t, byte[] a, int off, int len) throws Exception {
		for (int i = 0; i < 10; i++) {
			int r = t.write(a, off, len, AudioTrack.WRITE_NON_BLOCKING);
			if (r != 0)
				return r;
			Thread.sleep(20);
		}
		return 0;
	}

	private static int writeRetry(AudioTrack t, short[] a, int off, int len) throws Exception {
		for (int i = 0; i < 10; i++) {
			int r = t.write(a, off, len, AudioTrack.WRITE_NON_BLOCKING);
			if (r != 0)
				return r;
			Thread.sleep(20);
		}
		return 0;
	}

	private static int writeRetry(AudioTrack t, ByteBuffer b, int len) throws Exception {
		for (int i = 0; i < 10; i++) {
			int r = t.write(b, len, AudioTrack.WRITE_NON_BLOCKING);
			if (r != 0)
				return r;
			Thread.sleep(20);
		}
		return 0;
	}

	private static byte[] toneBytes(double hz, int rate, int frames, int amp) {
		short[] s = toneShorts(hz, rate, frames, amp);
		byte[] b = new byte[s.length * 2];
		for (int i = 0; i < s.length; i++) {
			b[2 * i] = (byte) (s[i] & 0xff);
			b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xff);
		}
		return b;
	}

	private static short[] toneShorts(double hz, int rate, int frames, int amp) {
		short[] out = new short[frames * 2];
		for (int i = 0; i < frames; i++) {
			short v = (short) (amp * Math.sin(2.0 * Math.PI * hz * i / rate));
			out[2 * i] = v;
			out[2 * i + 1] = v;
		}
		return out;
	}
}