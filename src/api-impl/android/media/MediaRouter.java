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

package android.media;

import android.content.Context;
import android.view.Display;
import java.util.ArrayList;

public class MediaRouter {
	public static final int ROUTE_TYPE_LIVE_AUDIO = 1 << 0;
	public static final int ROUTE_TYPE_LIVE_VIDEO = 1 << 1;
	public static final int ROUTE_TYPE_REMOTE_DISPLAY = 1 << 2;
	public static final int ROUTE_TYPE_USER = 1 << 23;

	public static final int CALLBACK_FLAG_PERFORM_ACTIVE_SCAN = 1 << 0;
	public static final int CALLBACK_FLAG_UNFILTERED_EVENTS = 1 << 1;
	public static final int CALLBACK_FLAG_REQUEST_DISCOVERY = 1 << 2;
	public static final int CALLBACK_FLAG_PASSIVE_DISCOVERY = 1 << 3;

	static final int ROUTE_TYPE_ANY = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO
			| ROUTE_TYPE_REMOTE_DISPLAY | ROUTE_TYPE_USER;

	/**
	 * The system audio/video route that ATL always exposes. ATL has no media route providers,
	 * so this is the only route that is not created by the application itself.
	 */
	private final RouteInfo mDefaultRoute;

	private final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();

	private final ArrayList<CallbackInfo> mCallbacks = new ArrayList<CallbackInfo>();

	private RouteInfo mSelectedRoute;

	public MediaRouter() {
		mDefaultRoute = new RouteInfo();
		mDefaultRoute.mName = "Default";
		mDefaultRoute.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO;
		mDefaultRoute.mPlaybackType = RouteInfo.PLAYBACK_TYPE_LOCAL;
		mDefaultRoute.mVolumeHandling = RouteInfo.PLAYBACK_VOLUME_FIXED;
		mRoutes.add(mDefaultRoute);
		mSelectedRoute = mDefaultRoute;
	}

	public RouteInfo getDefaultRoute() {
		return mDefaultRoute;
	}

	public RouteInfo getSelectedRoute() {
		return getSelectedRoute(ROUTE_TYPE_ANY);
	}

	public RouteInfo getSelectedRoute(int type) {
		if (mSelectedRoute != null && (mSelectedRoute.mSupportedTypes & type) != 0) {
			return mSelectedRoute;
		} else if (type == ROUTE_TYPE_USER) {
			return null;
		}
		return mDefaultRoute;
	}

	public void addCallback(int types, Callback cb) {
		addCallback(types, cb, 0);
	}

	public void addCallback(int types, Callback cb, int flags) {
		for (int i = 0; i < mCallbacks.size(); i++) {
			CallbackInfo info = mCallbacks.get(i);
			if (info.cb == cb) {
				info.type |= types;
				info.flags |= flags;
				return;
			}
		}
		mCallbacks.add(new CallbackInfo(cb, types, flags, this));
	}

	public void removeCallback(Callback cb) {
		for (int i = 0; i < mCallbacks.size(); i++) {
			if (mCallbacks.get(i).cb == cb) {
				mCallbacks.remove(i);
				return;
			}
		}
	}

	public void selectRoute(int types, RouteInfo route) {
		if (route == null) {
			throw new IllegalArgumentException("route cannot be null");
		}
		RouteInfo oldRoute = mSelectedRoute;
		if (oldRoute == route) {
			return;
		}
		if (!route.matchesTypes(types)) {
			return;
		}
		mSelectedRoute = route;
		if (oldRoute != null) {
			dispatchRouteUnselected(types & oldRoute.getSupportedTypes(), oldRoute);
		}
		dispatchRouteSelected(types & route.getSupportedTypes(), route);
	}

	public void addUserRoute(UserRouteInfo info) {
		if (info == null) throw new IllegalArgumentException("route cannot be null");
		if (mRoutes.contains(info)) return;
		mRoutes.add(info);
		dispatchRouteAdded(info);
	}

	public void removeUserRoute(UserRouteInfo info) {
		if (mRoutes.remove(info)) {
			if (info == mSelectedRoute) {
				// Removing the currently selected route? Select the default before we remove it.
				selectRoute(ROUTE_TYPE_ANY, mDefaultRoute);
			}
			dispatchRouteRemoved(info);
		}
	}

	private void dispatchRouteAdded(RouteInfo info) {
		for (CallbackInfo cbi : new ArrayList<CallbackInfo>(mCallbacks)) {
			if (cbi.filterRouteEvent(info)) {
				cbi.cb.onRouteAdded(cbi.router, info);
			}
		}
	}

	private void dispatchRouteRemoved(RouteInfo info) {
		for (CallbackInfo cbi : new ArrayList<CallbackInfo>(mCallbacks)) {
			if (cbi.filterRouteEvent(info)) {
				cbi.cb.onRouteRemoved(cbi.router, info);
			}
		}
	}

	private void dispatchRouteSelected(int type, RouteInfo info) {
		for (CallbackInfo cbi : new ArrayList<CallbackInfo>(mCallbacks)) {
			if (cbi.filterRouteEvent(info)) {
				cbi.cb.onRouteSelected(cbi.router, type, info);
			}
		}
	}

	private void dispatchRouteUnselected(int type, RouteInfo info) {
		for (CallbackInfo cbi : new ArrayList<CallbackInfo>(mCallbacks)) {
			if (cbi.filterRouteEvent(info)) {
				cbi.cb.onRouteUnselected(cbi.router, type, info);
			}
		}
	}

	static class CallbackInfo {
		public int type;
		public int flags;
		public final Callback cb;
		public final MediaRouter router;

		public CallbackInfo(Callback cb, int type, int flags, MediaRouter router) {
			this.cb = cb;
			this.type = type;
			this.flags = flags;
			this.router = router;
		}

		public boolean filterRouteEvent(RouteInfo route) {
			return filterRouteEvent(route.mSupportedTypes);
		}

		public boolean filterRouteEvent(int supportedTypes) {
			return (flags & CALLBACK_FLAG_UNFILTERED_EVENTS) != 0
					|| (type & supportedTypes) != 0;
		}
	}

	public int getRouteCount() {
		return mRoutes.size();
	}

	public RouteInfo getRouteAt(int index) {
		if (index < mRoutes.size()) {
			return mRoutes.get(index);
		}
		throw new IndexOutOfBoundsException("index=" + index + " count=" + mRoutes.size());
	}

	public UserRouteInfo createUserRoute(RouteCategory category) {
		return new UserRouteInfo(category);
	}

	public RouteCategory createRouteCategory(CharSequence name, boolean isGroupable) {
		return new RouteCategory(name, isGroupable);
	}

	public static class RouteInfo {
		CharSequence mName;
		CharSequence mDescription;
		int mSupportedTypes;
		int mDeviceType;
		int mPlaybackType = PLAYBACK_TYPE_LOCAL;
		int mVolumeMax = DEFAULT_PLAYBACK_MAX_VOLUME;
		int mVolume = DEFAULT_PLAYBACK_VOLUME;
		int mVolumeHandling = PLAYBACK_VOLUME_VARIABLE;
		int mPlaybackStream = android.media.AudioManager.STREAM_MUSIC;
		Object mTag;

		public static final int PLAYBACK_TYPE_LOCAL = 0;
		public static final int PLAYBACK_TYPE_REMOTE = 1;
		public static final int PLAYBACK_VOLUME_FIXED = 0;
		public static final int PLAYBACK_VOLUME_VARIABLE = 1;

		public static final int DEVICE_TYPE_UNKNOWN = 0;
		public static final int DEVICE_TYPE_TV = 1;
		public static final int DEVICE_TYPE_SPEAKER = 2;
		public static final int DEVICE_TYPE_BLUETOOTH = 3;

		private static final int DEFAULT_PLAYBACK_MAX_VOLUME = 15;
		private static final int DEFAULT_PLAYBACK_VOLUME = DEFAULT_PLAYBACK_MAX_VOLUME;

		public CharSequence getName() {
			return mName;
		}

		public CharSequence getName(Context context) {
			return mName;
		}

		public CharSequence getDescription() {
			return mDescription;
		}

		public int getSupportedTypes() {
			return mSupportedTypes;
		}

		public boolean matchesTypes(int types) {
			return (mSupportedTypes & types) != 0;
		}

		public int getDeviceType() {
			return mDeviceType;
		}

		public int getPlaybackType() {
			return mPlaybackType;
		}

		public int getPlaybackStream() {
			return mPlaybackStream;
		}

		public int getVolumeMax() {
			return mVolumeMax;
		}

		public int getVolumeHandling() {
			return mVolumeHandling;
		}

		public int getVolume() {
			return mVolume;
		}

		public Object getTag() {
			return mTag;
		}

		public boolean isConnecting() {
			return false;
		}

		public boolean isEnabled() {
			return true;
		}

		public Display getPresentationDisplay() {
			return null;
		}

		public void requestSetVolume(int volume) {
			setVolumeInternal(volume);
		}

		public void requestUpdateVolume(int direction) {
			if (direction != 0) {
				setVolumeInternal(mVolume + direction);
			}
		}

		void setVolumeInternal(int volume) {
			if (mVolumeHandling == PLAYBACK_VOLUME_VARIABLE) {
				mVolume = Math.max(0, Math.min(mVolumeMax, volume));
			}
		}
	}

	public static class UserRouteInfo extends RouteInfo {
		private VolumeCallback mVolumeCallback;

		UserRouteInfo(RouteCategory category) {
			mSupportedTypes = ROUTE_TYPE_USER;
			mPlaybackType = PLAYBACK_TYPE_REMOTE;
			mVolumeHandling = PLAYBACK_VOLUME_FIXED;
		}

		public void setName(CharSequence name) {
			mName = name;
		}

		public void setDescription(CharSequence description) {
			mDescription = description;
		}

		public void setSupportedTypes(int supportedTypes) {
			mSupportedTypes = supportedTypes;
		}

		public void setPlaybackType(int type) {
			mPlaybackType = type;
		}

		public void setPlaybackStream(int stream) {
			mPlaybackStream = stream;
		}

		public void setVolumeMax(int volumeMax) {
			mVolumeMax = volumeMax;
		}

		public void setVolumeHandling(int volumeHandling) {
			mVolumeHandling = volumeHandling;
		}

		public void setVolume(int volume) {
			setVolumeInternal(volume);
		}

		public void setTag(Object tag) {
			mTag = tag;
		}

		public void setVolumeCallback(VolumeCallback vcb) {
			mVolumeCallback = vcb;
		}

		@Override
		public void requestSetVolume(int volume) {
			if (mVolumeCallback != null) {
				mVolumeCallback.onVolumeSetRequest(this, volume);
			}
		}

		@Override
		public void requestUpdateVolume(int direction) {
			if (mVolumeCallback != null) {
				mVolumeCallback.onVolumeUpdateRequest(this, direction);
			}
		}
	}

	public static class RouteGroup extends RouteInfo {
		private final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();

		RouteGroup(RouteCategory category) {
			mSupportedTypes = ROUTE_TYPE_USER;
			mVolumeHandling = PLAYBACK_VOLUME_FIXED;
		}

		public int getRouteCount() {
			return mRoutes.size();
		}

		public RouteInfo getRouteAt(int index) {
			return mRoutes.get(index);
		}
	}

	public static class RouteCategory {
		private final CharSequence mName;
		private final boolean mGroupable;

		RouteCategory(CharSequence name, boolean groupable) {
			mName = name;
			mGroupable = groupable;
		}

		public CharSequence getName() {
			return mName;
		}
	}

	public static abstract class Callback {
		public abstract void onRouteSelected(MediaRouter router, int type, RouteInfo info);

		public abstract void onRouteUnselected(MediaRouter router, int type, RouteInfo info);

		public abstract void onRouteAdded(MediaRouter router, RouteInfo info);

		public abstract void onRouteRemoved(MediaRouter router, RouteInfo info);

		public abstract void onRouteChanged(MediaRouter router, RouteInfo info);

		public abstract void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group, int index);

		public abstract void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group);

		public abstract void onRouteVolumeChanged(MediaRouter router, RouteInfo info);

		public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
		}
	}

	/**
	 * A callback that does nothing on every event. Applications subclass it and
	 * override only the events they care about.
	 */
	public static class SimpleCallback extends Callback {
		@Override
		public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
		}

		@Override
		public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
		}

		@Override
		public void onRouteAdded(MediaRouter router, RouteInfo info) {
		}

		@Override
		public void onRouteRemoved(MediaRouter router, RouteInfo info) {
		}

		@Override
		public void onRouteChanged(MediaRouter router, RouteInfo info) {
		}

		@Override
		public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group, int index) {
		}

		@Override
		public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
		}

		@Override
		public void onRouteVolumeChanged(MediaRouter router, RouteInfo info) {
		}

		@Override
		public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
		}
	}

	public static abstract class VolumeCallback {
		public abstract void onVolumeUpdateRequest(RouteInfo info, int direction);

		public abstract void onVolumeSetRequest(RouteInfo info, int volume);
	}
}