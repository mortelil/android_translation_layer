/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content;

import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;

/*
 * Adapted from AOSP platform/frameworks/base
 *   tag     android-17.0.0_r1
 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
 *   file    core/java/android/content/ComponentCallbacksController.java
 * Apache License 2.0; see this repository's LICENSE.txt.
 * Semantics preserved: registrations guarded by mLock; every dispatch takes a
 * private snapshot (toArray under the lock) and then invokes listeners OUTSIDE
 * the lock, so registering/unregistering during dispatch is safe and a listener
 * added mid-dispatch does not receive the in-flight event.
 * The AOSP Consumer-based helper is inlined into explicit loops (ATL does not
 * rely on java.util.function at this layer).
 */
public class ComponentCallbacksController {
	private List<ComponentCallbacks> mComponentCallbacks;

	private final Object mLock = new Object();

	public void registerCallbacks(ComponentCallbacks callbacks) {
		synchronized (mLock) {
			if (mComponentCallbacks == null) {
				mComponentCallbacks = new ArrayList<ComponentCallbacks>();
			}
			mComponentCallbacks.add(callbacks);
		}
	}

	public void unregisterCallbacks(ComponentCallbacks callbacks) {
		synchronized (mLock) {
			if (mComponentCallbacks == null || mComponentCallbacks.isEmpty()) {
				return;
			}
			mComponentCallbacks.remove(callbacks);
		}
	}

	public void clearCallbacks() {
		synchronized (mLock) {
			if (mComponentCallbacks != null) {
				mComponentCallbacks.clear();
			}
		}
	}

	public void dispatchConfigurationChanged(Configuration newConfig) {
		ComponentCallbacks[] callbacksArray = collectCallbacksSnapshot();
		if (callbacksArray == null) {
			return;
		}
		for (int i = 0; i < callbacksArray.length; i++) {
			callbacksArray[i].onConfigurationChanged(newConfig);
		}
	}

	public void dispatchLowMemory() {
		ComponentCallbacks[] callbacksArray = collectCallbacksSnapshot();
		if (callbacksArray == null) {
			return;
		}
		for (int i = 0; i < callbacksArray.length; i++) {
			callbacksArray[i].onLowMemory();
		}
	}

	public void dispatchTrimMemory(int level) {
		ComponentCallbacks[] callbacksArray = collectCallbacksSnapshot();
		if (callbacksArray == null) {
			return;
		}
		for (int i = 0; i < callbacksArray.length; i++) {
			ComponentCallbacks callbacks = callbacksArray[i];
			if (callbacks instanceof ComponentCallbacks2) {
				((ComponentCallbacks2) callbacks).onTrimMemory(level);
			}
		}
	}

	private ComponentCallbacks[] collectCallbacksSnapshot() {
		synchronized (mLock) {
			if (mComponentCallbacks == null || mComponentCallbacks.isEmpty()) {
				return null;
			}
			ComponentCallbacks[] callbacksArray = new ComponentCallbacks[mComponentCallbacks.size()];
			mComponentCallbacks.toArray(callbacksArray);
			return callbacksArray;
		}
	}
}
