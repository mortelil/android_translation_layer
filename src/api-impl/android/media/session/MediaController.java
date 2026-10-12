/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.session;

import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.view.KeyEvent;
import java.util.List;

public class MediaController {

	public MediaController(Context context, MediaSession.Token token) {}

	public MediaMetadata getMetadata() {
		return new MediaMetadata();
	}

	public CharSequence getQueueTitle() {
		return null;
	}

	public PlaybackState getPlaybackState() {
		return null;
	}

	public PendingIntent getSessionActivity() {
		return null;
	}

	public TransportControls getTransportControls() {
		return new TransportControls();
	}

	public boolean dispatchMediaButtonEvent(KeyEvent keyEvent) {
		return false;
	}

	public void sendCommand(String command, Bundle args, ResultReceiver cb) {
	}

	public void registerCallback(Callback callback) {
		registerCallback(callback, null);
	}

	public void registerCallback(Callback callback, Handler handler) {
		// ATL has no media session backend, so there are no playback events to deliver.
	}

	public void unregisterCallback(Callback callback) {
	}

	/**
	 * Callback for the media controller. The methods are empty because ATL has no media
	 * session backend to report events from.
	 */
	public abstract static class Callback {
		public void onSessionDestroyed() {
		}

		public void onSessionEvent(String event, Bundle extras) {
		}

		public void onPlaybackStateChanged(PlaybackState state) {
		}

		public void onMetadataChanged(MediaMetadata metadata) {
		}

		public void onQueueChanged(List<MediaSession.QueueItem> queue) {
		}

		public void onQueueTitleChanged(CharSequence title) {
		}

		public void onExtrasChanged(Bundle extras) {
		}

		public void onAudioInfoChanged(PlaybackInfo info) {
		}
	}

	/**
	 * Information about the current playback volume handling of the session.
	 */
	public static final class PlaybackInfo {
		public static final int PLAYBACK_TYPE_LOCAL = 1;
		public static final int PLAYBACK_TYPE_REMOTE = 2;

		private final int mPlaybackType;
		private final int mVolumeControl;
		private final int mMaxVolume;
		private final int mCurrentVolume;
		private final AudioAttributes mAudioAttrs;
		private final String mVolumeControlId;

		public PlaybackInfo(int playbackType, int volumeControl, int maxVolume,
				int currentVolume, AudioAttributes audioAttrs, String volumeControlId) {
			mPlaybackType = playbackType;
			mVolumeControl = volumeControl;
			mMaxVolume = maxVolume;
			mCurrentVolume = currentVolume;
			mAudioAttrs = audioAttrs;
			mVolumeControlId = volumeControlId;
		}

		public int getPlaybackType() {
			return mPlaybackType;
		}

		public int getVolumeControl() {
			return mVolumeControl;
		}

		public int getMaxVolume() {
			return mMaxVolume;
		}

		public int getCurrentVolume() {
			return mCurrentVolume;
		}

		public AudioAttributes getAudioAttributes() {
			return mAudioAttrs;
		}

		public String getVolumeControlId() {
			return mVolumeControlId;
		}
	}

	public final class TransportControls {
		public void play() {
		}

		public void pause() {
		}

		public void stop() {
		}
	}
}