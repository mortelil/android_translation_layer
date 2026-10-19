/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.usage;

import android.content.Context;
import android.os.Handler;
import android.util.Log;


/**
 * Provides access to network usage history and statistics.
 *
 * Port of AOSP core/java/android/app/usage/NetworkStatsManager.java (android-17.0.0_r1, commit
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d; the pinned partial reference tree only carries
 * core/java/android/{app,content,os,util}, so this file is cited from the SDK sources of the same
 * release train,
 * /home/morten/Android/Sdk/sources/android-37.0/android/app/usage/NetworkStatsManager.java).
 *
 * AOSP obtains this object from the system server
 * (SystemServiceRegistry.registerContextAwareService(Context.NETWORK_STATS_SERVICE, ...) ->
 * ConnectivityFrameworkInitializerTiramisu:75 "new NetworkStatsManager(context, service)").
 * ATL has no system server, no netd and therefore no NetworkStatsService binder, and the host
 * kernel keeps no per-UID/per-tag/per-state accounting, so the data queries below cannot collect
 * statistics. They report that honestly with AOSP's own documented failure value (null: "Statistics
 * object or null if permissions are insufficient or error happened during statistics collection",
 * NetworkStatsManager.java:470) instead of inventing zeroed buckets. This mirrors
 * android.net.TrafficStats, which returns its UNSUPPORTED sentinel for the same reason, and the
 * registration-only FrameMetrics listener in android/view/Window.java.
 *
 * Not modelled: AOSP's NetworkTemplate-based overloads and getBinder()/INetworkStatsService, because
 * ATL has neither android.net.NetworkTemplate nor the binder interface.
 */
public class NetworkStatsManager {
	private static final String TAG = "NetworkStatsManager";
	private static final boolean DBG = false;

	public static final int CALLBACK_LIMIT_REACHED = 0;
	public static final int CALLBACK_RELEASED = 1;

	/** Minimum data usage threshold for registering usage callbacks (AOSP:119). */
	public static final long MIN_THRESHOLD_BYTES = 2 * 1_048_576L;

	/* AOSP ConnectivityManager.TYPE_MOBILE / TYPE_WIFI (AOSP ConnectivityManager.java:155,167).
	 * ATL's ConnectivityManager does not declare the TYPE_* constants yet, so the values used by
	 * AOSP's createTemplate (NetworkStatsManager.java:1264-1286) are spelled out here. */
	private static final int TYPE_MOBILE = 0;
	private static final int TYPE_WIFI = 1;

	private final Context mContext;

	public NetworkStatsManager(Context context) {
		mContext = context;
	}

	/**
	 * AOSP's createTemplate() rejects every network type other than TYPE_MOBILE/TYPE_WIFI with
	 * IllegalArgumentException (NetworkStatsManager.java:1264-1286).
	 */
	private static void checkNetworkType(int networkType) {
		if (networkType != TYPE_MOBILE && networkType != TYPE_WIFI)
			throw new IllegalArgumentException("Cannot create template for network type "
					+ networkType + ".");
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:376-419). */
	public NetworkStats.Bucket querySummaryForDevice(int networkType, String subscriberId,
			long startTime, long endTime) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats.Bucket() : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:421-465). */
	public NetworkStats.Bucket querySummaryForUser(int networkType, String subscriberId,
			long startTime, long endTime) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats.Bucket() : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:471-483). */
	public NetworkStats querySummary(int networkType, String subscriberId, long startTime,
			long endTime) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats(startTime, endTime, new NetworkStats.Bucket[0]) : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:701-756). */
	public NetworkStats queryDetailsForUid(int networkType, String subscriberId, long startTime,
			long endTime, int uid) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats(startTime, endTime, new NetworkStats.Bucket[0]) : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:757-805). */
	public NetworkStats queryDetailsForUidTag(int networkType, String subscriberId, long startTime,
			long endTime, int uid, int tag) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats(startTime, endTime, new NetworkStats.Bucket[0]) : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:806-882). */
	public NetworkStats queryDetailsForUidTagState(int networkType, String subscriberId,
			long startTime, long endTime, int uid, int tag, int state) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats(startTime, endTime, new NetworkStats.Bucket[0]) : null;
	}

	/** @return statistics, or null when statistics collection is unavailable (AOSP:956-1000). */
	public NetworkStats queryDetails(int networkType, String subscriberId, long startTime,
			long endTime) {
		checkNetworkType(networkType);
		return collect() ? new NetworkStats(startTime, endTime, new NetworkStats.Bucket[0]) : null;
	}

	/** ATL has no NetworkStatsService to collect from (see the class comment). */
	private static boolean collect() {
		if (DBG)
			Log.d(TAG, "no NetworkStatsService in ATL: reporting no statistics");
		return false;
	}

	/** AOSP NetworkStatsManager.java:1093-1102. */
	public void registerUsageCallback(int networkType, String subscriberId, long thresholdBytes,
			UsageCallback callback) {
		registerUsageCallback(networkType, subscriberId, thresholdBytes, callback, null);
	}

	/**
	 * AOSP NetworkStatsManager.java:1124-1141: the callback is registered against
	 * NetworkStatsService, which ATL does not have, so registration is rejected explicitly rather than silently promising callbacks.
	 */
	public void registerUsageCallback(int networkType, String subscriberId, long thresholdBytes,
			UsageCallback callback, Handler handler) {
		checkNetworkType(networkType);
		if (callback == null)
			throw new NullPointerException("UsageCallback cannot be null");
		throw new UnsupportedOperationException("ATL has no network usage accounting backend");
	}

	/** AOSP NetworkStatsManager.java:1145-1159. */
	public void unregisterUsageCallback(UsageCallback callback) {
		throw new IllegalArgumentException("No usage callback can be registered without an accounting backend");
	}

	/**
	 * Base class for usage callbacks. Should be extended by applications wanting notifications.
	 * AOSP NetworkStatsManager.java:1161-1186; the @SystemApi onThresholdReached(NetworkTemplate)
	 * overload is omitted because ATL has no android.net.NetworkTemplate.
	 */
	public static abstract class UsageCallback {
		/**
		 * Called when data usage has reached the given threshold.
		 */
		public abstract void onThresholdReached(int networkType, String subscriberId);

	}
}
