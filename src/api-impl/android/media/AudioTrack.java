package android.media;

import android.os.Handler;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming AudioTrack backed by the ATL ALSA/PulseAudio sink.
 *
 * Public semantics (error codes, blocking/non-blocking writes, play state and
 * playback head position) follow AOSP android-15.0.0_r1
 * frameworks/base media/java/android/media/AudioTrack.java; the native side owns
 * the PCM state (see api-impl-jni/audio/android_media_AudioTrack.c).
 */
public class AudioTrack implements AudioRouting {

	public static final int WRITE_BLOCKING = 0;
	public static final int WRITE_NON_BLOCKING = 1;
	public static final int MODE_STATIC = 0;
	public static final int MODE_STREAM = 1;
	public static final int SUCCESS = 0;
	public static final int ERROR = -1;
	public static final int ERROR_BAD_VALUE = -2; // basically EINVAL
	public static final int ERROR_INVALID_OPERATION = -3;
	public static final int STATE_UNINITIALIZED = 0;
	public static final int STATE_INITIALIZED = 1;

	public static final int PLAYSTATE_STOPPED = 1;
	public static final int PLAYSTATE_PAUSED = 2;
	public static final int PLAYSTATE_PLAYING = 3;

	private final Map<AudioRouting.OnRoutingChangedListener, Handler> routingListeners = new HashMap<>();

	@Override
	public synchronized void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener, Handler handler) {
		if (listener == null || routingListeners.containsKey(listener))
			return;
		routingListeners.put(listener, handler != null ? handler : new Handler(android.os.Looper.getMainLooper()));
	}
	@Override
	public synchronized void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
		routingListeners.remove(listener);
	}
	// ALSA's default PCM is selected by the host audio server. ATL currently has
	// no Android AudioDeviceInfo mapping or per-track device selection for it.
	@Override
	public AudioDeviceInfo getPreferredDevice() { return null; }
	@Override
	public AudioDeviceInfo getRoutedDevice() { return null; }
	@Override
	public boolean setPreferredDevice(AudioDeviceInfo device) { return device == null; }

	public interface OnPlaybackPositionUpdateListener {
		void onMarkerReached(AudioTrack track);
		void onPeriodicNotification(AudioTrack track);
	}

	int streamType;
	int sampleRateInHz;
	int channelConfig;
	int audioFormat;
	int bufferSizeInBytes;
	int mode;
	private int sessionId;
	private int playbackState = PLAYSTATE_STOPPED;
	/** Frames handed to the sink since the last stop()/flush(). */
	private float volume = 1.f;
	private int underrunCount;
	public int getUnderrunCount() { return underrunCount; }

	// for native code's use
	long pcm_handle;
	long params;
	int channels;
	int period_time;
	// mostly
	static int frames;
	OnPlaybackPositionUpdateListener periodic_update_listener;

	native void native_constructor(int streamType, int sampleRateInHz, int num_channels, int audioFormat, int bufferSizeInBytes, int mode);
	public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, int mode) {
		this.streamType = streamType;
		this.sampleRateInHz = sampleRateInHz;
		this.channelConfig = channelConfig;
		this.audioFormat = audioFormat;
		this.bufferSizeInBytes = bufferSizeInBytes;
		this.mode = mode;

		System.out.println("AudioTrack(" + streamType + ", " + sampleRateInHz + ", " + channelConfig + ", " + audioFormat + ", " + bufferSizeInBytes + ", " + mode + ")");
		native_constructor(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode);
	}

	public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, int mode, int sessionId) {
		this(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode);
		this.sessionId = sessionId;
	}

	public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int mode, int sessionId) {
		this(attributes.streamType, format.sampleRate, format.channelMask, format.encoding, bufferSizeInBytes, mode, sessionId);
	}

	public static native int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat);

	public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener) {
		this.periodic_update_listener = listener;
	}

	public int setPositionNotificationPeriod(int periodInFrames) {
		return 0; // SUCCESS
	}

	public int getPositionNotificationPeriod() {
		return this.frames;
	}

	public void play() {
		if (pcm_handle == 0)
			throw new IllegalStateException("AudioTrack is not initialized");
		playbackState = PLAYSTATE_PLAYING;
		native_play();
	}

	public void stop() {
		if (pcm_handle == 0)
			throw new IllegalStateException("AudioTrack is not initialized");
		native_stop();
		playbackState = PLAYSTATE_STOPPED;
	}

	public void pause() {
		if (pcm_handle == 0)
			throw new IllegalStateException("AudioTrack is not initialized");
		native_pause();
		playbackState = PLAYSTATE_PAUSED;
	}

	/** Discards queued PCM; no-op unless stopped or paused, or if uninitialized. */
	public void flush() {
		if (pcm_handle == 0)
			return; // AOSP: no-op if not initialized
		if (playbackState == PLAYSTATE_PLAYING)
			return; // AOSP: no-op while playing
		native_flush();
	}

	public void release() {
		native_release();
		synchronized (this) { routingListeners.clear(); }
	}

	public int getState() {
		return pcm_handle == 0 ? STATE_UNINITIALIZED : STATE_INITIALIZED;
	}

	/** Equivalent to write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING). */
	public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
		return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
	}

	/**
	 * Writes interleaved PCM16 to the sink. Returns the number of bytes written
	 * (possibly a short count in WRITE_NON_BLOCKING mode, when the track is not
	 * playing, or when another thread interrupts the write), or one of
	 * {@link #ERROR_INVALID_OPERATION}/ {@link #ERROR_BAD_VALUE}/ {@link #ERROR}.
	 */
	public int write(byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
		if (pcm_handle == 0 || audioFormat != AudioFormat.ENCODING_PCM_16BIT)
			return ERROR_INVALID_OPERATION;
		if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
			return ERROR_BAD_VALUE;
		if (audioData == null || offsetInBytes < 0 || sizeInBytes < 0
		    || offsetInBytes + sizeInBytes < 0
		    || offsetInBytes + sizeInBytes > audioData.length)
			return ERROR_BAD_VALUE;

		return native_write_bytes(audioData, offsetInBytes, sizeInBytes, writeMode == WRITE_BLOCKING);
	}

	/** Equivalent to write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING). */
	public int write(short[] audioData, int offsetInShorts, int sizeInShorts) {
		return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
	}

	/** Writes interleaved PCM16; see {@link #write(byte[], int, int, int)}. */
	public int write(short[] audioData, int offsetInShorts, int sizeInShorts, int writeMode) {
		if (pcm_handle == 0 || audioFormat != AudioFormat.ENCODING_PCM_16BIT)
			return ERROR_INVALID_OPERATION;
		if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
			return ERROR_BAD_VALUE;
		if (audioData == null || offsetInShorts < 0 || sizeInShorts < 0
		    || offsetInShorts + sizeInShorts < 0
		    || offsetInShorts + sizeInShorts > audioData.length)
			return ERROR_BAD_VALUE;

		return native_write_shorts(audioData, offsetInShorts, sizeInShorts, writeMode == WRITE_BLOCKING);
	}

	/**
	 * Writes interleaved PCM16 from a ByteBuffer in the requested mode.
	 * <p>
	 * Mirrors AOSP's branch exactly (android-17.0.0_r1
	 * media/java/android/media/AudioTrack.java:3772-3781): a <b>direct</b>
	 * buffer, read-only or not, goes through the native direct-buffer path; a
	 * <b>non-direct</b> buffer is handed the backing array obtained from
	 * {@link ByteBuffer#array()}. A read-only <i>heap</i> buffer therefore
	 * throws {@link java.nio.ReadOnlyBufferException} (AOSP reaches it through
	 * {@code NioUtils.unsafeArray}, which is the same {@code array()} call) and
	 * is rejected, while a read-only <i>direct</i> buffer is accepted.
	 */
	public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
		if (pcm_handle == 0 || audioFormat != AudioFormat.ENCODING_PCM_16BIT)
			return ERROR_INVALID_OPERATION;
		if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
			return ERROR_BAD_VALUE;
		if (audioData == null || sizeInBytes < 0 || sizeInBytes > audioData.remaining())
			return ERROR_BAD_VALUE;

		boolean blocking = writeMode == WRITE_BLOCKING;
		int ret;
		if (audioData.isDirect()) {
			ret = native_write_nio(audioData, sizeInBytes, blocking);
		} else {
			// ByteBuffer.array() throws java.nio.ReadOnlyBufferException for a
			// read-only heap buffer. Do not catch or convert it: that is the
			// documented Android behaviour.
			ret = native_write_bytes(audioData.array(),
			                         audioData.arrayOffset() + audioData.position(),
			                         sizeInBytes, blocking);
		}
		if (ret > 0) {
			// java.nio.Buffer explicitly: the covariant ByteBuffer overrides
			// added in Java 9 are not part of the runtime core library here.
			java.nio.Buffer b = audioData;
			b.position(b.position() + ret);
		}
		return ret;
	}

	public int getAudioSessionId() {
		return sessionId;
	}

	public int getSampleRate() {
		return sampleRateInHz;
	}

	public int setStereoVolume(float leftVolume, float rightVolume) {
		return setVolume((leftVolume + rightVolume) / 2);
	}

	public int setVolume(float volume) {
		this.volume = volume;
		native_setVolume(volume);
		return 0;
	}

	public int getPlayState() {
		return playbackState;
	}

	/**
	 * Frames rendered since the last stop()/flush().
	 * <p>
	 * AOSP (android-17.0.0_r1 media/java/android/media/AudioTrack.java:2577-2589)
	 * defines the return value as the hardware frame counter whose signed int
	 * must be {@link #headPositionFromFrames reinterpreted as unsigned 32-bit}:
	 * the next position after 0x7FFFFFFF is (int) 0x80000000, it wraps at 2^32,
	 * and it is reset to zero by stop() and flush().
	 */
	public int getPlaybackHeadPosition() {
		if (pcm_handle == 0)
			return 0;
		return headPositionFromFrames(native_getPlaybackHeadPosition());
	}

	/**
	 * Applies AOSP's 32-bit wrap to the native frame counter: the backend
	 * tracks a continuously advancing 64-bit frame count, but the public
	 * position is its low 32 bits interpreted as unsigned, so
	 * 0x7FFFFFFF -> 2147483647, 0x80000000 -> -2147483648, 0xFFFFFFFF -> -1
	 * and 0x100000000 -> 0. Package-private: the conversion is a production
	 * behaviour that the contract probe pins at the exact boundaries without a
	 * real ~12-hour playback.
	 */
	static int headPositionFromFrames(long frames) {
		return (int) frames;
	}

	public boolean getTimestamp(AudioTimestamp timestamp) {
		return false;
	}

	public static int getNativeOutputSampleRate(int i) {
		return native_getOutputSampleRate(i);
	}

	private static native int native_getOutputSampleRate(int streamType);
	private native long native_getPlaybackHeadPosition();
	private native void native_play();
	private native void native_pause();
	private native void native_stop();
	private native void native_flush();
	private native void native_setVolume(float volume);
	private native int native_write_bytes(byte[] audioData, int offsetInBytes, int sizeInBytes, boolean blocking);
	private native int native_write_shorts(short[] audioData, int offsetInShorts, int sizeInShorts, boolean blocking);
	private native int native_write_nio(ByteBuffer audioData, int sizeInBytes, boolean blocking);
	public native void native_release();

	// nested classes
	public static class Builder {
		private AudioAttributes mAttributes;
		private AudioFormat mFormat;
		private int mBufferSizeInBytes;
		private int mTransferMode;
		private int mSessionId;

		public Builder() {}

		public AudioTrack build() {
			return new AudioTrack(mAttributes, mFormat, mBufferSizeInBytes, mTransferMode, mSessionId);
		}

		public Builder setSessionId(int sessionId) {
			if (sessionId < 0)
				throw new IllegalArgumentException("Invalid audio session ID");
			mSessionId = sessionId;
			return this;
		}

		public Builder setAudioAttributes(AudioAttributes attributes) {
			mAttributes = attributes;
			return this;
		}

		public Builder setAudioFormat(AudioFormat format) {
			mFormat = format;
			return this;
		}

		public Builder setBufferSizeInBytes(int bufferSizeInBytes) {
			mBufferSizeInBytes = bufferSizeInBytes;
			return this;
		}

		public Builder setTransferMode(int mode) {
			mTransferMode = mode;
			return this;
		}

		public Builder setPerformanceMode(int performanceMode) {
			return this;
		}
	}
}
