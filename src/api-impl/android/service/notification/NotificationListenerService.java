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

package android.service.notification;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/*
 * Reimplementation of android.service.notification.NotificationListenerService.
 *
 * AOSP reference (tag android-17.0.0_r1):
 *   frameworks/base/core/java/android/service/notification/NotificationListenerService.java
 *     public abstract class NotificationListenerService extends Service
 *     public static final String SERVICE_INTERFACE = "android.service.notification.NotificationListenerService";
 *     public IBinder onBind(Intent intent) -- returns the private wrapper the system talks to
 *     public void onListenerConnected() {}        (empty in AOSP)
 *     public void onListenerDisconnected() {}     (empty in AOSP)
 *     public StatusBarNotification[] getActiveNotifications()
 *     public void cancelNotification(String key)
 *     public static void requestRebind(ComponentName componentName)
 *     public void requestUnbind()
 *
 * ATL has no system notification service: there is no NotificationManagerService to bind the
 * listener to, to ask for a rebind, or to read a notification store from.  Consequently a
 * listener is never connected (onListenerConnected/onListenerDisconnected are only invoked by
 * the platform) and getActiveNotifications() reports an empty set -- which is the truthful
 * state, ATL's NotificationManager keeps no posted notifications.
 *
 * The class exists because applications that declare a NotificationListenerService (e.g. KDE
 * Connect's org.kde.kdeconnect.plugins.notifications.NotificationReceiver) are instantiated by
 * the app itself; without the superclass the app dies with
 * NoClassDefFoundError: android/service/notification/NotificationListenerService.
 */
public abstract class NotificationListenerService extends Service {
	public static final String SERVICE_INTERFACE = "android.service.notification.NotificationListenerService";
	public static final String HINT_HOST_INTENT = "android.service.notification.hint.HINT_HOST_INTENT";

	private static final String TAG = "NotificationListenerService";

	public NotificationListenerService() {
		super();
	}

	@Override
	public IBinder onBind(Intent intent) {
		/* AOSP hands the system a wrapper through which notification callbacks arrive; ATL has
		 * no system notification service that would ever bind this component. */
		return null;
	}

	public void onListenerConnected() {
		/* AOSP calls this when the system has connected the listener. */
	}

	public void onListenerDisconnected() {
		/* AOSP calls this when the system has disconnected the listener. */
	}

	public void onNotificationPosted(StatusBarNotification sbn) {
		/* AOSP calls this for every posted notification of a connected listener. */
	}

	public void onNotificationRemoved(StatusBarNotification sbn) {
		/* AOSP calls this for every removed notification of a connected listener. */
	}

	public StatusBarNotification[] getActiveNotifications() {
		/* No system notification store exists in ATL, so the listener has no active
		 * notifications to report. */
		return new StatusBarNotification[0];
	}

	public StatusBarNotification[] getActiveNotifications(String[] keys) {
		return new StatusBarNotification[0];
	}

	public void cancelNotification(String key) {
		/* Nothing can have been posted through this listener, nothing to cancel. */
	}

	public void cancelAllNotifications() {
	}

	public static void requestRebind(ComponentName componentName) {
		Log.d(TAG, "requestRebind(" + (componentName == null ? "null" : componentName.flattenToShortString()) + "): no system notification service in ATL");
	}

	public void requestUnbind() {
		Log.d(TAG, "requestUnbind(): no system notification service in ATL");
	}
}
