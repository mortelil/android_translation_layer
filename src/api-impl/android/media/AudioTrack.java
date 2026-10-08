package android.media;

import android.os.Handler;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class AudioTrack implements AudioRouting {
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

	public static final int ERROR_BAD_VALUE = -2; // basically EINVAL
	public static final int ERROR_INVALID_OPERATION = -3;
	public static final int STATE_UNINITIALIZED = 0;
	public static final int STATE_INITIALIZED = 1;

	public static final int PLAYSTATE_STOPPED = 1;
	public static final int PLAYSTATE_PAUSED = 2;
	public static final int PLAYSTATE_PLAYING = 3;

	int streamType;
	int sampleRateInHz;
	int channelConfig;
	int audioFormat;
	int bufferSizeInBytes;
	int mode;
	private int sessionId;
	private int playbackState = PLAYSTATE_STOPPED;
	private int playbackHeadPosition = 0;
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

		System.out.println("\n\n\nAudioTrack(" + streamType + ", " + sampleRateInHz + ", " + channelConfig + ", " + audioFormat + ", " + bufferSizeInBytes + ", " + mode + "); called\n\n\n\n");
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
		System.out.println("\n\nAudioTrack.nsetPositionNotificationPeriod(" + periodInFrames + "); called\n\n\n\n");
		return 0; // SUCCESS
	}

	public int getPositionNotificationPeriod() {
		return this.frames;
	}

	public void play() {
		if (pcm_handle == 0)
			throw new IllegalStateException("AudioTrack is not initialized");
		System.out.println("calling AudioTrack.play()\n");
		playbackState = PLAYSTATE_PLAYING;
		native_play();
	}

	public void stop() {
		System.out.println("STUB: AudioTrack.stop()\n");
		playbackState = PLAYSTATE_STOPPED;
	}

	public void flush() {
		System.out.println("STUB: AudioTrack.flush()\n");
	}

	public void release() {
		System.out.println("calling AudioTrack.release()\n");
		native_release();
		synchronized (this) { routingListeners.clear(); }
	}

	public int getState() {
		return pcm_handle == 0 ? STATE_UNINITIALIZED : STATE_INITIALIZED;
	}

	public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
		if (pcm_handle == 0)
			return ERROR_INVALID_OPERATION;
		/* sanity check the parameters before calling native_write */
		if ((audioData == null)
		    || (offsetInBytes < 0) || (sizeInBytes < 0)
		    || (offsetInBytes + sizeInBytes < 0)
		    || (offsetInBytes + sizeInBytes > audioData.length)) {
			return ERROR_BAD_VALUE;
		}

		int framesToWrite = sizeInBytes / channels / 2; // 2 means PCM16
		int ret = native_write(audioData, offsetInBytes, framesToWrite, volume);
		if (ret > 0) {
			playbackHeadPosition += ret;
		}
		return ret * channels * 2; // 2 means PCM16
	}

	public int write(ByteBuffer audioData, int sizeInBytes, int writeMode) {
		int ret = write(audioData.array(), audioData.arrayOffset() + audioData.position(), sizeInBytes);
		audioData.position(audioData.position() + ret);
		return ret;
	}

	public int write(short audioData[], int offsetInShorts, int sizeInShorts) {
		if (pcm_handle == 0)
			return ERROR_INVALID_OPERATION;
		/* sanity check the parameters before calling native_write */
		if ((audioData == null)
		    || (offsetInShorts < 0) || (sizeInShorts < 0)
		    || (offsetInShorts + sizeInShorts < 0)
		    || (offsetInShorts + sizeInShorts > audioData.length)) {
			return ERROR_BAD_VALUE;
		}

		int framesToWrite = sizeInShorts / channels;
		int ret = native_write(audioData, offsetInShorts, framesToWrite, volume);
		if (ret > 0) {
			playbackHeadPosition += ret;
		}
		return ret * channels;
	}

	public int getAudioSessionId() {
		return sessionId;
	}

	public int getSampleRate() {
		return sampleRateInHz;
	}

	public int setStereoVolume(float leftVolume, float rightVolume) {
		this.volume = (leftVolume + rightVolume) / 2;
		return 0;
	}

	public int getPlayState() {
		return playbackState;
	}

	public void pause() {
		if (pcm_handle == 0)
			throw new IllegalStateException("AudioTrack is not initialized");
		System.out.println("calling AudioTrack.pause()\n");
		playbackState = PLAYSTATE_PAUSED;
		native_pause();
	}

	public int getPlaybackHeadPosition() {
		if (pcm_handle == 0)
			return 0;
		return playbackHeadPosition - native_getPlaybackHeadPosition();
	}

	public int setVolume(float volume) {
		this.volume = volume;
		return 0;
	}

	public boolean getTimestamp(AudioTimestamp timestamp) {
		return false;
	}

	private native int native_getPlaybackHeadPosition();
	public native void native_play();
	public native void native_pause();
	private native int native_write(byte[] audioData, int offsetInBytes, int framesToWrite, float volume);
	private native int native_write(short[] audioData, int offsetInShorts, int framesToWrite, float volume);
	public native void native_release();

	public static int getNativeOutputSampleRate(int i) {
		return -1;
	}

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
