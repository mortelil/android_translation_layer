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
 *   - AOSP's ByteBuffer split: a read-only <i>heap</i> buffer is rejected with
 *     {@link java.nio.ReadOnlyBufferException} (position untouched) while a
 *     read-only <i>direct</i> buffer is accepted,
 *   - the playback head position advancing while playing and freezing on pause,
 *   - a blocking write returning a SHORT count when interrupted by pause(),
 *   - mono tracks, non-zero-offset partial writes, release-vs-writer races and
 *     a real mid-stream worker underrun followed by recovery,
 *   - use-after-release and unavailable-device error codes,
 *   - AOSP's 32-bit playback-head wrap (the conversion helper's exact
 *     boundary results: 0x7FFFFFFF -> 2147483647, 0x80000000 -> -2147483648,
 *     0xFFFFFFFF -> -1, 0x100000000 -> 0),
 *   - a real 1 kHz tone reaching the sink (verified host-side from the sink
 *     monitor recording, not from "no error"/PLAYING).
 *
 * Every check that can throw is wrapped so its exception is recorded as a FAIL
 * and the remaining checks still run: no single check may abort the probe.
 *
 * Output: one `[AP] ASSERT ... PASS|FAIL` line per check and a final
 * `[AP] AUDIO_PROBE_DONE failures=N sink=yes|no` line.
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
		checkThemeAttributeReferences();
		checkThemeStylesAcrossAssetManagerCopy();
		checkRotation();
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

	/**
	 * Styled-attribute contract for theme values that are themselves attribute
	 * references (Res_value::TYPE_ATTRIBUTE).  AOSP resolves such a reference
	 * against the theme while the style is applied -- AttributeResolution.cpp
	 * ApplyStyle()/ResolveAttrs() and AssetManager2.cpp Theme::GetAttribute(),
	 * whose chain walk is bounded to kMaxIterations == 20 -- so TypedArray's
	 * getters never receive an unresolved attribute.  A reference the theme
	 * cannot resolve stays absent (TYPE_NULL), which is what the getters
	 * document ("Returns null if the attribute is not defined").
	 *
	 * This reproduces org.videolan.vlc's VideoPlayerActivity onCreate(), which
	 * threw
	 *   java.lang.UnsupportedOperationException: Failed to resolve attribute at index 13
	 *   TypedValue{t=0x2/d=0x7f0404f5 a=-1}   (getDrawable(TypedArray.java:894))
	 * and
	 *   ... at index 5: TypedValue{t=0x2/d=0x101009b a=1} (getColorStateList(TypedArray.java:518))
	 * while inflating android:background="?attr/selectableItemBackgroundBorderless".
	 */
	private void checkThemeAttributeReferences() {
		android.content.res.Resources res = getResources();
		String pkg = getPackageName();
		int attrRef = res.getIdentifier("probeAttrRef", "attr", pkg);
		int attrSelf = res.getIdentifier("probeAttrSelf", "attr", pkg);
		check("theme_attr_ids_visible", attrRef != 0 && attrSelf != 0,
		      "probeAttrRef=0x" + Integer.toHexString(attrRef)
		      + " probeAttrSelf=0x" + Integer.toHexString(attrSelf));

		// (1) Resolvable chain: ?attr/probeAttrRef -> ?attr/probeAttrConcrete
		//     -> @color/probe_chain_color.  Must yield the concrete color.
		try {
			android.content.res.TypedArray a =
				getTheme().obtainStyledAttributes(new int[] { attrRef });
			android.content.res.ColorStateList csl = a.getColorStateList(0);
			int color = csl == null ? 0 : csl.getDefaultColor();
			check("theme_attr_chain_resolves", csl != null && color == 0xffff0000,
			      "getColorStateList(0)=" + (csl == null ? "null" : "0x" + Integer.toHexString(color)));
			a.recycle();
		} catch (Throwable t) {
			fail("theme_attr_chain_resolves", t);
		}

		// (2) Unresolvable chain (the item references itself, exactly like the
		//     framework's own base styles): the value must be absent, never an
		//     unresolved TYPE_ATTRIBUTE for the getters to throw on.
		try {
			android.content.res.TypedArray a =
				getTheme().obtainStyledAttributes(new int[] { attrSelf });
			boolean has = a.hasValue(0);
			android.content.res.ColorStateList csl = a.getColorStateList(0);
			android.graphics.drawable.Drawable d = a.getDrawable(0);
			check("theme_attr_unresolvable_is_absent",
			      !has && csl == null && d == null,
			      "hasValue=" + has + " getColorStateList=" + csl + " getDrawable=" + d);
			a.recycle();
		} catch (Throwable t) {
			fail("theme_attr_unresolvable_is_absent", t);
		}
	}

	/**
	 * Configuration copies share ApkAssets identity (as required by AOSP
	 * AssetManager2 Theme::SetTo). Applied values must survive the copy without
	 * replaying styles with possibly unrelated resource IDs. ThemeKey records
	 * the style history separately, as ResourcesImpl.ThemeImpl does in AOSP
	 * android-17.0.0_r1 / 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d.
	 */
	private void checkThemeStylesAcrossAssetManagerCopy() {
		String pkg = getPackageName();
		int attrRef = getResources().getIdentifier("probeAttrRef", "attr", pkg);
		String what = "theme_styles_survive_assetmanager_copy";
		try {
			android.content.Context copy =
				createConfigurationContext(new android.content.res.Configuration());
			android.content.res.Resources.Theme dst = copy.getResources().newTheme();
			dst.setTo(getTheme());
			android.content.res.TypedArray a = dst.obtainStyledAttributes(new int[] { attrRef });
			android.content.res.ColorStateList csl = a.getColorStateList(0);
			int color = csl == null ? 0 : csl.getDefaultColor();
			check(what, csl != null && color == 0xffff0000,
			      "copied-theme getColorStateList(0)="
			      + (csl == null ? "null" : "0x" + Integer.toHexString(color)));
			a.recycle();
		} catch (Throwable t) {
			fail(what, t);
		}
	}

	private static void check(String what, boolean ok, String detail) {
		if (!ok)
			failures++;
		String line = "ASSERT " + what + ": " + detail + " -> " + (ok ? "PASS" : "FAIL");
		checks.add(line);
		System.out.println(TAG + line);
		System.out.flush();
	}

	/** Record a throwing check as a FAIL so later checks still execute. */
	private static void fail(String what, Throwable t) {
		String msg = t.getMessage();
		check(what, false, "threw " + t.getClass().getName() + (msg != null ? (": " + msg) : ""));
		t.printStackTrace();
	}

	/** Record a not-applicable check (an ATL-only seam that is absent on the
	 *  Android reference) without counting it as a failure. */
	private static void skip(String what, String detail) {
		String line = "ASSERT " + what + ": " + detail + " -> SKIP";
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

	private static int pos(ByteBuffer b) {
		return ((java.nio.Buffer) b).position();
	}

	private static AudioTrack newStereo(int rate, int bufBytes) {
		return new AudioTrack(STREAM_MUSIC, rate, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT,
		                      bufBytes, AudioTrack.MODE_STREAM);
	}

	private static class Rotation extends android.view.animation.RotateAnimation {
		Rotation() { super(0, 180, ABSOLUTE, 2, ABSOLUTE, 3); }
		void sample(float fraction, android.view.animation.Transformation target) {
			super.applyTransformation(fraction, target);
		}
	}

	private void checkRotation() {
		try {
			Rotation animation = new Rotation();
			animation.initialize(10, 10, 20, 20);
			android.view.animation.Transformation t = new android.view.animation.Transformation();
			animation.sample(0.5f, t);
			float[] point = {3, 3};
			t.getMatrix().mapPoints(point);
			check("rotation_retains_pivot_matrix", Math.abs(point[0]-2) < 0.001f
			    && Math.abs(point[1]-4) < 0.001f, "point=" + point[0] + "," + point[1]);
		} catch (Throwable error) { fail("rotation_retains_pivot_matrix", error); }
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

		AudioTrack t = newStereo(rate, bufBytes);
		boolean sink = t.getState() == AudioTrack.STATE_INITIALIZED;
		sinkAvailable = sink;
		System.out.println(TAG + "SINK available=" + (sink ? "yes" : "no")
		                   + " rate=" + rate + " bufBytes=" + bufBytes);
		System.out.flush();

		if (!sink) {
			// No output device: the correct, honest behaviour is an uninitialized
			// track that refuses writes rather than pretending to play.
			check("uninitialized_state", t.getState() == AudioTrack.STATE_UNINITIALIZED, "state=" + t.getState());
			try {
				check("write_on_uninitialized",
				      t.write(new byte[1024], 0, 1024) == AudioTrack.ERROR_INVALID_OPERATION, "");
			} catch (Throwable e) {
				fail("write_on_uninitialized", e);
			}
			t.release();
			return;
		}

		check("track_initialized", true, "state=" + t.getState());
		check("min_buffer_size_positive", min > 0, "min=" + min);

		// ---- error handling on write parameters -------------------------------
		byte[] small = new byte[64];
		try {
			int badRange = t.write(small, 0, 128, AudioTrack.WRITE_BLOCKING);
			check("write_bad_range", badRange == AudioTrack.ERROR_BAD_VALUE, "got=" + badRange);
		} catch (Throwable e) {
			fail("write_bad_range", e);
		}
		try {
			check("write_invalid_mode_byte", t.write(small, 0, 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");
		} catch (Throwable e) {
			fail("write_invalid_mode_byte", e);
		}
		try {
			check("write_invalid_mode_short", t.write(new short[64], 0, 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");
		} catch (Throwable e) {
			fail("write_invalid_mode_short", e);
		}
		try {
			check("write_invalid_mode_nio", t.write(ByteBuffer.wrap(small), 64, 7) == AudioTrack.ERROR_BAD_VALUE, "");
		} catch (Throwable e) {
			fail("write_invalid_mode_nio", e);
		}

		// ---- queue while stopped: nothing may be reported as played ----------
		int framesPerChunk = rate / 50;                 // 20 ms
		int chunkBytes = framesPerChunk * 4;            // stereo s16
		byte[] chunk = toneBytes(1000.0, rate, framesPerChunk, 12000);
		byte[] oddOffsetChunk = new byte[chunk.length + 1];
		System.arraycopy(chunk, 0, oddOffsetChunk, 1, chunk.length);
		try {
			int nw = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_NON_BLOCKING);
			check("write_nonblocking_accepts", nw >= 0 && nw <= chunkBytes, "wrote=" + nw);
		} catch (Throwable e) {
			fail("write_nonblocking_accepts", e);
		}
		try {
			check("head_zero_while_stopped", t.getPlaybackHeadPosition() == 0,
			      "head=" + t.getPlaybackHeadPosition());
		} catch (Throwable e) {
			fail("head_zero_while_stopped", e);
		}

		// ---- nonblocking short count when the device is full -----------------
		try {
			int big = 8 * bufBytes;
			byte[] bigBlocks = new byte[big];
			int shortRet = t.write(bigBlocks, 0, big, AudioTrack.WRITE_NON_BLOCKING);
			check("write_nonblocking_short", shortRet > 0 && shortRet < big, "wrote=" + shortRet + "/" + big);
		} catch (Throwable e) {
			fail("write_nonblocking_short", e);
		}

		// A filled stopped queue must remain silent; the earlier immediate
		// head check could miss ALSA automatically starting after buffer fill.
		Thread.sleep(180);
		check("full_stopped_queue_does_not_render", t.getPlaybackHeadPosition() == 0,
		      "head=" + t.getPlaybackHeadPosition());

		// stop discards the queue and resets the head
		try {
			t.stop();
			check("stop_resets_head", t.getPlaybackHeadPosition() == 0, "head=" + t.getPlaybackHeadPosition());
		} catch (Throwable e) {
			fail("stop_resets_head", e);
		}

		// ---- real tone: blocking writes, head advances, pause freezes it -----
		System.out.println(TAG + "TONE hz=1000 rate=" + rate + " seconds=4 channels=2");
		System.out.flush();
		try {
			t.play();
			long writtenBytes = 0;
			int totalChunks = 4 * 50;
			int broke = -1;
			for (int i = 0; i < totalChunks; i++) {
				int r = t.write(oddOffsetChunk, 1, chunkBytes, AudioTrack.WRITE_BLOCKING);
				if (r > 0)
					writtenBytes += r;
				else {
					broke = i;
					break;
				}
			}
			check("tone_blocking_write_full", writtenBytes == (long) totalChunks * chunkBytes,
			      "bytes=" + writtenBytes + "/" + ((long) totalChunks * chunkBytes)
			      + (broke >= 0 ? (" stoppedAtChunk=" + broke) : ""));
		} catch (Throwable e) {
			fail("tone_blocking_write_full", e);
		}
		try {
			Thread.sleep(700);
			int headPlaying = t.getPlaybackHeadPosition();
			check("head_advances_while_playing", headPlaying > 0, "head=" + headPlaying);
		} catch (Throwable e) {
			fail("head_advances_while_playing", e);
		}
		int lastPausedHead = -1;
		try {
			t.pause();
			int headPaused = t.getPlaybackHeadPosition();
			Thread.sleep(300);
			int headPaused2 = t.getPlaybackHeadPosition();
			lastPausedHead = headPaused2;
			check("head_frozen_while_paused", headPaused == headPaused2,
			      "head1=" + headPaused + " head2=" + headPaused2);
		} catch (Throwable e) {
			fail("head_frozen_while_paused", e);
		}
		try {
			t.play();
			Thread.sleep(300);
			int headResumed = t.getPlaybackHeadPosition();
			// AOSP guarantees a monotonic head within a playback run: after an
			// underrun the position must not jump backwards (e.g. to 0).
			check("head_monotonic_after_resume", lastPausedHead >= 0 && headResumed >= lastPausedHead,
			      "was=" + lastPausedHead + " now=" + headResumed);
		} catch (Throwable e) {
			fail("head_monotonic_after_resume", e);
		}

		// ---- partial-offset writes with a tail-free length, while playing ----
		// (t is playing at this point; a non-zero offset must be honoured and
		// the accepted data must really reach the device.)
		int headBeforePartial = -1;
		try {
			headBeforePartial = t.getPlaybackHeadPosition();
		} catch (Throwable e) {
			fail("write_partial_offset_prep", e);
		}
		try {
			byte[] region = toneBytes(1000.0, rate, 128, 12000); // 512 bytes, stereo
			int rpb = t.write(region, 64, 256, AudioTrack.WRITE_BLOCKING);
			check("write_partial_offset_byte", rpb >= 0 && rpb <= 256 && rpb % 4 == 0,
			      "ret=" + rpb + "/256");
		} catch (Throwable e) {
			fail("write_partial_offset_byte", e);
		}
		try {
			short[] sregion = toneShorts(1000.0, rate, 128, 12000); // 256 shorts, stereo
			int rps = t.write(sregion, 16, 128, AudioTrack.WRITE_BLOCKING);
			check("write_partial_offset_short", rps >= 0 && rps <= 128 && rps % 2 == 0,
			      "ret=" + rps + "/128");
		} catch (Throwable e) {
			fail("write_partial_offset_short", e);
		}
		try {
			// The partial-offset data is queued; now make the device actually run
			// (a full buffer reaches the start threshold, and play() starts an
			// idle stream) and prove the head advances, i.e. the accepted data
			// really reaches the sink.
			int queued = 0;
			int chunksNeeded = (bufBytes / chunkBytes) + 2;
			for (int i = 0; i < chunksNeeded; i++) {
				int r = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_BLOCKING);
				if (r < 0)
					break;
				queued += r;
			}
			t.play();
			Thread.sleep(400);
			int headAfterPartial = t.getPlaybackHeadPosition();
			check("write_partial_offset_lands",
			      queued > 0 && headAfterPartial > headBeforePartial,
			      "queued=" + queued + " head=" + headBeforePartial + "->" + headAfterPartial);
		} catch (Throwable e) {
			fail("write_partial_offset_lands", e);
		}

		// ---- blocking write interrupted by pause() returns a short count -----
		try {
			final AudioTrack ti = t;
			ti.pause();
			final byte[] interruptBlocks = new byte[20 * bufBytes];
			final int[] interruptRet = new int[1];
			final Throwable[] interruptErr = new Throwable[1];
			Thread writer = new Thread(new Runnable() {
				public void run() {
					try {
						interruptRet[0] = ti.write(interruptBlocks, 0, interruptBlocks.length,
						                           AudioTrack.WRITE_BLOCKING);
					} catch (Throwable e) {
						interruptErr[0] = e;
					}
				}
			}, "audio-interrupt-writer");
			writer.start();
			Thread.sleep(150);
			ti.pause();
			writer.join(3000);
			check("blocking_write_interrupted_short",
			      !writer.isAlive() && interruptErr[0] == null
			      && interruptRet[0] >= 0 && interruptRet[0] < interruptBlocks.length,
			      "ret=" + interruptRet[0] + "/" + interruptBlocks.length
			      + " alive=" + writer.isAlive() + " err=" + interruptErr[0]);
		} catch (Throwable e) {
			fail("blocking_write_interrupted_short", e);
		}

		// ---- writes entered while stopped: AOSP contract, not a fixed count --
		// AOSP (android-17.0.0_r1 media/java/android/media/AudioTrack.java:3430-3433)
		// permits 0, a short count or a full count for writes entered while the
		// track is stopped/paused, so assert only: 0 <= ret <= requested,
		// ret % frameSize == 0, position advances by exactly ret, and nothing
		// queued while stopped is reported as played (head stays 0).
		try {
			t.stop();
			int accepted = 0;
			boolean nbOk = true;
			String nbDetail = "";
			for (int i = 0; i < 10; i++) {
				try {
					int r = t.write(chunk, 0, chunkBytes, AudioTrack.WRITE_NON_BLOCKING);
					if (r < 0 || r > chunkBytes || r % 4 != 0) {
						nbOk = false;
						nbDetail += " ret[" + i + "]=" + r;
					} else {
						accepted += r;
					}
				} catch (Throwable e) {
					nbOk = false;
					nbDetail += " ret[" + i + "] threw " + e;
					break;
				}
				Thread.sleep(20);
			}
			int nbHead = t.getPlaybackHeadPosition();
			check("nonblocking_progress_after_stop", nbOk && nbHead == 0,
			      "accepted=" + accepted + " head=" + nbHead + nbDetail);
		} catch (Throwable e) {
			fail("nonblocking_progress_after_stop", e);
		}

		short[] tone = toneShorts(1000.0, rate, framesPerChunk, 12000);
		try {
			t.stop();
			int rs = writeRetry(t, tone, 0, tone.length);
			int rsHead = t.getPlaybackHeadPosition();
			check("write_shorts_count", rs >= 0 && rs <= tone.length && rs % 2 == 0 && rsHead == 0,
			      "shorts=" + rs + "/" + tone.length + " head=" + rsHead);
		} catch (Throwable e) {
			fail("write_shorts_count", e);
		}

		try {
			t.stop();
			ByteBuffer direct = ByteBuffer.allocateDirect(chunkBytes);
			direct.put(chunk);
			((java.nio.Buffer) direct).flip();
			int rd = writeRetry(t, direct, chunkBytes);
			check("write_direct_bytebuffer", rd >= 0 && rd <= chunkBytes && rd % 4 == 0 && pos(direct) == rd,
			      "bytes=" + rd + " pos=" + pos(direct));
		} catch (Throwable e) {
			fail("write_direct_bytebuffer", e);
		}

		try {
			t.stop();
			ByteBuffer heap = ByteBuffer.wrap(chunk);
			int rh = writeRetry(t, heap, chunkBytes);
			check("write_heap_bytebuffer", rh >= 0 && rh <= chunkBytes && rh % 4 == 0 && pos(heap) == rh,
			      "bytes=" + rh + " pos=" + pos(heap));
		} catch (Throwable e) {
			fail("write_heap_bytebuffer", e);
		}

		// ---- read-only ByteBuffers: AOSP's exact split -----------------------
		// A read-only DIRECT buffer is accepted (AOSP native_write_native_bytes);
		// a read-only HEAP buffer is rejected with ReadOnlyBufferException
		// (AOSP NioUtils.unsafeArray -> ByteBuffer.array()).
		try {
			t.play();
			ByteBuffer rod = ByteBuffer.allocateDirect(chunkBytes);
			rod.put(chunk);
			((java.nio.Buffer) rod).flip();
			ByteBuffer rodr = rod.asReadOnlyBuffer();
			int rrd = writeRetry(t, rodr, chunkBytes);
			check("write_readonly_direct_bytebuffer", rrd > 0 && rrd <= chunkBytes && rrd % 4 == 0 && pos(rodr) == rrd,
			      "bytes=" + rrd + " pos=" + pos(rodr));
		} catch (Throwable e) {
			fail("write_readonly_direct_bytebuffer", e);
		}

		try {
			ByteBuffer ro = ByteBuffer.wrap(chunk).asReadOnlyBuffer();
			int roPosBefore = pos(ro);
			boolean threw = false;
			boolean wasReadOnly = false;
			int roRet = Integer.MIN_VALUE;
			try {
				roRet = t.write(ro, chunkBytes, AudioTrack.WRITE_BLOCKING);
			} catch (java.nio.ReadOnlyBufferException e) {
				threw = true;
				wasReadOnly = true;
			} catch (Throwable e) {
				threw = true;
			}
			check("write_readonly_heap_bytebuffer",
			      threw && wasReadOnly && roRet == Integer.MIN_VALUE && pos(ro) == roPosBefore,
			      "threw=" + threw + " readonly=" + wasReadOnly + " ret=" + roRet
			      + " pos=" + pos(ro) + " posBefore=" + roPosBefore);
		} catch (Throwable e) {
			fail("write_readonly_heap_bytebuffer", e);
		}

		// ---- flush while stopped is honoured, head reset ---------------------
		try {
			t.stop();
			writeRetry(t, chunk, 0, chunkBytes);
			t.flush();
			check("flush_resets_head", t.getPlaybackHeadPosition() == 0, "head=" + t.getPlaybackHeadPosition());
		} catch (Throwable e) {
			fail("flush_resets_head", e);
		}

		// ---- release then reuse ----------------------------------------------
		try {
			t.release();
			check("released_state_uninitialized", t.getState() == AudioTrack.STATE_UNINITIALIZED,
			      "state=" + t.getState());
		} catch (Throwable e) {
			fail("released_state_uninitialized", e);
		}
		try {
			int wr = t.write(chunk, 0, chunkBytes);
			check("write_after_release", wr == AudioTrack.ERROR_INVALID_OPERATION, "got=" + wr);
		} catch (Throwable e) {
			fail("write_after_release", e);
		}
		try {
			t.release(); // must be harmless
			check("double_release_safe", true, "");
		} catch (Throwable e) {
			fail("double_release_safe", e);
		}

		// ---- blocking write entered while stopped (dedicated track) ----------
		AudioTrack t2 = null;
		try {
			t2 = newStereo(rate, bufBytes);
			if (t2.getState() != AudioTrack.STATE_INITIALIZED) {
				check("blocking_write_after_stop_completes", false, "track not initialized state=" + t2.getState());
			} else {
				t2.stop();
				final AudioTrack ft = t2;
				final byte[] c = chunk;
				final int cb = chunkBytes;
				final int[] ret = new int[]{Integer.MIN_VALUE};
				final Throwable[] err = new Throwable[1];
				Thread w = new Thread(new Runnable() {
					public void run() {
						try {
							ret[0] = ft.write(c, 0, cb, AudioTrack.WRITE_BLOCKING);
						} catch (Throwable e) {
							err[0] = e;
						}
					}
				}, "audio-after-stop-writer");
				w.start();
				w.join(4000);
				boolean stuck = w.isAlive();
				int head = t2.getPlaybackHeadPosition();
				check("blocking_write_after_stop_completes",
				      !stuck && err[0] == null && ret[0] >= 0 && ret[0] <= cb && ret[0] % 4 == 0 && head == 0,
				      "ret=" + ret[0] + "/" + cb + " stuck=" + stuck + " err=" + err[0] + " head=" + head);
			}
		} catch (Throwable e) {
			fail("blocking_write_after_stop_completes", e);
		}
		if (t2 != null) {
			try {
				t2.release(); // also unblocks a stuck writer; only this track is affected
			} catch (Throwable e) {
				System.out.println(TAG + "t2.release failed " + e);
			}
		}

		// ---- mono track -------------------------------------------------------
		AudioTrack tm = null;
		try {
			int monoMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, ENCODING_PCM_16BIT);
			int monoBuf = Math.max(monoMin > 0 ? monoMin : 2048, (rate / 5) * 2); // >= 200 ms mono s16
			tm = new AudioTrack(STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, ENCODING_PCM_16BIT,
			                    monoBuf, AudioTrack.MODE_STREAM);
			if (tm.getState() != AudioTrack.STATE_INITIALIZED) {
				check("mono_channels", false, "track not initialized state=" + tm.getState() + " min=" + monoMin);
			} else {
				byte[] mchunk = monoToneBytes(1000.0, rate, framesPerChunk, 12000); // 1 ch s16
				tm.play();
				int wrote = 0;
				for (int i = 0; i < 6; i++) {
					int r = tm.write(mchunk, 0, mchunk.length, AudioTrack.WRITE_BLOCKING);
					if (r > 0)
						wrote += r;
				}
				Thread.sleep(400);
				int mhead = tm.getPlaybackHeadPosition();
				tm.stop();
				int mheadStop = tm.getPlaybackHeadPosition();
				check("mono_channels", wrote > 0 && wrote % 2 == 0 && mheadStop == 0,
				      "min=" + monoMin + " buf=" + monoBuf + " wrote=" + wrote + "/" + mchunk.length
				      + " head=" + mhead + " headStop=" + mheadStop);
			}
		} catch (Throwable e) {
			fail("mono_channels", e);
		}
		if (tm != null) {
			try {
				tm.release();
			} catch (Throwable e) {
				System.out.println(TAG + "tm.release failed " + e);
			}
		}

		// ---- release() racing a blocking writer ------------------------------
		try {
			final AudioTrack t4 = newStereo(rate, bufBytes);
			if (t4.getState() != AudioTrack.STATE_INITIALIZED) {
				check("release_races_blocking_write", false, "track not initialized state=" + t4.getState());
				t4.release();
			} else {
				final byte[] race = new byte[16 * bufBytes];
				final int[] ret = new int[]{Integer.MIN_VALUE};
				final Throwable[] err = new Throwable[1];
				Thread w = new Thread(new Runnable() {
					public void run() {
						try {
							ret[0] = t4.write(race, 0, race.length, AudioTrack.WRITE_BLOCKING);
						} catch (Throwable e) {
							err[0] = e;
						}
					}
				}, "audio-release-race-writer");
				w.start();
				Thread.sleep(200);   // let the writer enter the blocking write
				t4.release();        // must unblock it, not crash or hang
				w.join(4000);
				boolean stuck = w.isAlive();
				int state = t4.getState();
				check("release_races_blocking_write",
				      !stuck && err[0] == null
				      && (ret[0] == AudioTrack.ERROR_INVALID_OPERATION
				          || (ret[0] >= 0 && ret[0] <= race.length))
				      && state == AudioTrack.STATE_UNINITIALIZED,
				      "ret=" + ret[0] + " stuck=" + stuck + " err=" + err[0] + " state=" + state);
			}
		} catch (Throwable e) {
			fail("release_races_blocking_write", e);
		}

		// ---- real worker failure (xrun/underrun) mid-stream, then recovery ---
		AudioTrack t5 = null;
		try {
			int obuf = Math.max(min > 0 ? min : 4096, (rate / 10) * 4); // ~100 ms buffer
			t5 = newStereo(rate, obuf);
			if (t5.getState() != AudioTrack.STATE_INITIALIZED) {
				check("output_worker_failure", false, "track not initialized state=" + t5.getState());
			} else {
				t5.play();
				int wrote = 0;
				for (int i = 0; i < 4; i++) {
					int r = t5.write(chunk, 0, chunkBytes, AudioTrack.WRITE_BLOCKING);
					if (r > 0)
						wrote += r;
				}
				int headBefore = t5.getPlaybackHeadPosition();
				Thread.sleep(900); // stop writing until the device runs dry (worker underrun)
				int headDry = t5.getPlaybackHeadPosition();
				int rec = t5.write(chunk, 0, chunkBytes, AudioTrack.WRITE_BLOCKING);
				Thread.sleep(400);
				int headAfter = t5.getPlaybackHeadPosition();
				check("output_worker_failure",
				      rec > 0 && rec <= chunkBytes && rec % 4 == 0
				      && headDry >= headBefore && headAfter >= headDry,
				      "wrote=" + wrote + " head=" + headBefore + "->" + headDry + "->" + headAfter
				      + " rec=" + rec + "/" + chunkBytes + " underruns=" + t5.getUnderrunCount());
			}
		} catch (Throwable e) {
			fail("output_worker_failure", e);
		}
		if (t5 != null) {
			try {
				t5.release();
			} catch (Throwable e) {
				System.out.println(TAG + "t5.release failed " + e);
			}
		}

		// ---- 32-bit playback head wrap: AOSP unsigned-32 reinterpretation ----
		// AOSP's getPlaybackHeadPosition() returns native_get_position(); the
		// javadoc (android-17.0.0_r1 media/java/android/media/AudioTrack.java:
		// 2577-2589) requires the signed int to be reinterpreted as unsigned
		// 32-bit, so it wraps at 2^32 and is reset by stop()/flush() (covered by
		// stop_resets_head and flush_resets_head above). A real wrap needs
		// >2^31 rendered frames (~12 h at 48 kHz), so pin the production
		// conversion helper at its exact boundaries instead. On the Android
		// reference that helper does not exist: record SKIP, never FAIL.
		try {
			java.lang.reflect.Method m = Class.forName("android.media.AudioTrack")
				.getDeclaredMethod("headPositionFromFrames", long.class);
			m.setAccessible(true);
			long[] boundary = {0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL, 0x100000000L};
			int[] expected = {2147483647, -2147483648, -1, 0};
			boolean wrapOk = true;
			StringBuilder wd = new StringBuilder();
			for (int i = 0; i < boundary.length; i++) {
				int got = ((Integer) m.invoke(null, boundary[i])).intValue();
				wd.append(i > 0 ? " " : "").append(Long.toHexString(boundary[i]))
				  .append("->").append(got);
				if (got != expected[i])
					wrapOk = false;
			}
			check("playback_head_wrap_32bit", wrapOk, wd.toString());
		} catch (NoSuchMethodException e) {
			// The Android reference has no ATL seam; not applicable, not a failure.
			skip("playback_head_wrap_32bit",
			     "android.media.AudioTrack.headPositionFromFrames absent (Android reference)");
		} catch (Throwable e) {
			fail("playback_head_wrap_32bit", e);
		}
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

	/** Mono (1 channel) s16 tone, for the CHANNEL_OUT_MONO track. */
	private static byte[] monoToneBytes(double hz, int rate, int frames, int amp) {
		byte[] b = new byte[frames * 2];
		for (int i = 0; i < frames; i++) {
			short v = (short) (amp * Math.sin(2.0 * Math.PI * hz * i / rate));
			b[2 * i] = (byte) (v & 0xff);
			b[2 * i + 1] = (byte) ((v >> 8) & 0xff);
		}
		return b;
	}
}