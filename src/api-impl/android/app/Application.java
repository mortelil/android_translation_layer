/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import android.R;
import android.atl.ATLLoadedApp;
import android.content.ComponentCallbacks;
import android.content.ComponentCallbacks2;
import android.content.ComponentCallbacksController;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageParser;
import android.content.res.Configuration;
import android.os.Bundle;

import java.util.ArrayList;

public class Application extends ContextWrapper implements ComponentCallbacks2 {
	public long native_window;

	/*
	 * ComponentCallbacks/ComponentCallbacks2 registry + dispatch.
	 *
	 * Adapted from AOSP platform/frameworks/base
	 *   tag     android-17.0.0_r1
	 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
	 *   file    core/java/android/app/Application.java
	 *   methods onConfigurationChanged/onLowMemory/onTrimMemory (288-300),
	 *           registerComponentCallbacks/unregisterComponentCallbacks (302-308)
	 * and core/java/android/content/ComponentCallbacksController.java.
	 * Apache License 2.0; see this repository's LICENSE.txt.
	 */
	private final ComponentCallbacksController mCallbacksController =
	    new ComponentCallbacksController();

	/*
	 * ActivityLifecycleCallbacks registry + dispatch.
	 *
	 * Adapted from AOSP platform/frameworks/base
	 *   tag     android-17.0.0_r1
	 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
	 *   file    core/java/android/app/Application.java
	 *   methods registerActivityLifecycleCallbacks (310-314),
	 *           unregisterActivityLifecycleCallbacks (316-320),
	 *           collectActivityLifecycleCallbacks (608-616),
	 *           dispatchActivityCreated/Started/Resumed/Paused/Stopped/
	 *           SaveInstanceState/Destroyed (378-586)
	 * AOSP is licensed under the Apache License, Version 2.0
	 * (http://www.apache.org/licenses/LICENSE-2.0); see this repository's
	 * LICENSE.txt. Behaviour is unchanged: append-only list, synchronized
	 * register/unregister/collect, forward iteration over a private toArray()
	 * snapshot outside the lock, no exception handling in dispatch.
	 */
	private ArrayList<ActivityLifecycleCallbacks> mActivityLifecycleCallbacks =
	    new ArrayList<ActivityLifecycleCallbacks>();

	private String get_app_icon_path() {
		String icon_path = null;
		try {
			icon_path = getString(this.get_atl_loaded_app().pkg.applicationInfo.icon);
		} catch (android.content.res.Resources.NotFoundException e) {
			e.printStackTrace();
		}
		if (icon_path == null) {
			icon_path = getString(R.mipmap.sym_def_app_icon);
		} else if (icon_path.endsWith(".xml")) {
			icon_path = null;
		}
		return icon_path;
	}

	private long get_app_icon_paintable() {
		return getPackageManager().getApplicationIcon(this.get_atl_loaded_app().pkg.applicationInfo).paintable;
	}

	String get_app_label() {
		return getString(this.get_atl_loaded_app().pkg.applicationInfo.labelRes);
	}

	String get_supported_mime_types() {
		StringBuilder mimeTypes = new StringBuilder();
		for (PackageParser.Activity activity : this.get_atl_loaded_app().pkg.activities) {
			if (activity.intents == null)
				continue;
			for (PackageParser.IntentInfo intent : activity.intents) {
				for (int i = 0; i < intent.countDataSchemes(); i++) {
					String scheme = intent.getDataScheme(i);
					// ignore http and https, as there is no way to only handle specific hosts in a .desktop file
					if (!"http".equals(scheme) && !"https".equals(scheme))
						mimeTypes.append("x-scheme-handler/").append(intent.getDataScheme(i)).append(";");
				}
			}
		}
		return (mimeTypes.length() == 0) ? null : mimeTypes.toString();
	}

	public interface ActivityLifecycleCallbacks {
		void onActivityCreated(Activity activity, Bundle savedInstanceState);
		void onActivityStarted(Activity activity);
		void onActivityResumed(Activity activity);
		void onActivityPaused(Activity activity);
		void onActivityStopped(Activity activity);
		void onActivitySaveInstanceState(Activity activity, Bundle outState);
		void onActivityDestroyed(Activity activity);
	}
	/**
	 * Callback interface for use with {@link Application#registerOnProvideAssistDataListener}
	 * and {@link Application#unregisterOnProvideAssistDataListener}.
	 */
	public interface OnProvideAssistDataListener {
		/**
		 * This is called when the user is requesting an assist, to build a full
		 * {@link Intent#ACTION_ASSIST} Intent with all of the context of the current
		 * application.  You can override this method to place into the bundle anything
		 * you would like to appear in the {@link Intent#EXTRA_ASSIST_CONTEXT} part
		 * of the assist Intent.
		 */
		public void onProvideAssistData(Activity activity, Bundle data);
	}

	public Application() {
		super(null);
	}
	/**
	 * Called when the application is starting, before any activity, service,
	 * or receiver objects (excluding content providers) have been created.
	 * Implementations should be as quick as possible (for example using
	 * lazy initialization of state) since the time spent in this function
	 * directly impacts the performance of starting the first activity,
	 * service, or receiver in a process.
	 * If you override this method, be sure to call super.onCreate().
	 */
	public void onCreate() {
	}
	/**
	 * This method is for use in emulated process environments.  It will
	 * never be called on a production Android device, where processes are
	 * removed by simply killing them; no user code (including this callback)
	 * is executed when doing so.
	 */
	public void onTerminate() {
	}
	public void onConfigurationChanged(Configuration newConfig) {
		mCallbacksController.dispatchConfigurationChanged(newConfig);
	}
	public void onLowMemory() {
		mCallbacksController.dispatchLowMemory();
	}
	public void onTrimMemory(int level) {
		mCallbacksController.dispatchTrimMemory(level);
	}
	public void registerComponentCallbacks(ComponentCallbacks callback) {
		mCallbacksController.registerCallbacks(callback);
	}
	public void unregisterComponentCallbacks(ComponentCallbacks callback) {
		mCallbacksController.unregisterCallbacks(callback);
	}
	public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) {
			mActivityLifecycleCallbacks.add(callback);
		}
	}
	public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) {
			mActivityLifecycleCallbacks.remove(callback);
		}
	}
	private Object[] collectActivityLifecycleCallbacks() {
		Object[] callbacks = null;
		synchronized (mActivityLifecycleCallbacks) {
			if (mActivityLifecycleCallbacks.size() > 0) {
				callbacks = mActivityLifecycleCallbacks.toArray();
			}
		}
		return callbacks;
	}
	/* package */ void dispatchActivityCreated(Activity activity, Bundle savedInstanceState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityCreated(activity, savedInstanceState);
			}
		}
	}
	/* package */ void dispatchActivityStarted(Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityStarted(activity);
			}
		}
	}
	/* package */ void dispatchActivityResumed(Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityResumed(activity);
			}
		}
	}
	/* package */ void dispatchActivityPaused(Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityPaused(activity);
			}
		}
	}
	/* package */ void dispatchActivityStopped(Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityStopped(activity);
			}
		}
	}
	/* package */ void dispatchActivitySaveInstanceState(Activity activity, Bundle outState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivitySaveInstanceState(activity, outState);
			}
		}
	}
	/* package */ void dispatchActivityDestroyed(Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) {
			for (int i=0; i<callbacks.length; i++) {
				((ActivityLifecycleCallbacks)callbacks[i]).onActivityDestroyed(activity);
			}
		}
	}
	public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
	}
	public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
	}
	public static String getProcessName() {
		// note: we currently don't set the process name
		return ATLLoadedApp.getPrimaryApplication().pkg.packageName;
	}
}
