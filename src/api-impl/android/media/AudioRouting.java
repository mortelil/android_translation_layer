// SPDX-License-Identifier: GPL-3.0-only
package android.media;

import android.os.Handler;

/** Routing contract shared by Android audio sources and sinks. */
public interface AudioRouting {
	interface OnRoutingChangedListener {
		void onRoutingChanged(AudioRouting router);
	}
	AudioDeviceInfo getPreferredDevice();
	AudioDeviceInfo getRoutedDevice();
	boolean setPreferredDevice(AudioDeviceInfo deviceInfo);
	void addOnRoutingChangedListener(OnRoutingChangedListener listener, Handler handler);
	void removeOnRoutingChangedListener(OnRoutingChangedListener listener);
}
