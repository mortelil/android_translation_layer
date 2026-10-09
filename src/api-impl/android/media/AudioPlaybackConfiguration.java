// SPDX-License-Identifier: GPL-3.0-only
package android.media;

/** Immutable playback metadata delivered to AudioPlaybackCallback. */
public final class AudioPlaybackConfiguration {
	private final AudioAttributes attributes;
	private final AudioDeviceInfo device;
	private final int sessionId;
	private final boolean active;

	AudioPlaybackConfiguration(AudioAttributes attributes, AudioDeviceInfo device, int sessionId, boolean active) {
		this.attributes = attributes;
		this.device = device;
		this.sessionId = sessionId;
		this.active = active;
	}
	public AudioAttributes getAudioAttributes() { return attributes; }
	public AudioDeviceInfo getAudioDeviceInfo() { return device; }
	public int getAudioSessionId() { return sessionId; }
	public boolean isActive() { return active; }
}
