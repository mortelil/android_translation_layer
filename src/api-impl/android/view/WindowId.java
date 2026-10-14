/*
 * Copyright (C) 2012 The Android Open Source Project
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

/* Ported from AOSP android-37.0 android/view/WindowId.java; only the Parcelable
 * plumbing, the focus-observer API and the IWindowId IPC token were adapted: ATL has
 * no IWindowId binder, so the window identity is carried as a plain IBinder, keeping
 * the token-identity semantics of AOSP's equals()/hashCode(). */
package android.view;

import android.os.IBinder;

/**
 * Safe identifier for a window. Two {@link WindowId}s are equal when they carry the
 * same window token, matching AOSP's comparison of the two tokens' binders.
 */
public class WindowId {
	private final IBinder mToken;

	public WindowId(IBinder token) {
		mToken = token;
	}

	@Override
	public boolean equals(Object otherObj) {
		if (otherObj instanceof WindowId) {
			return mToken.equals(((WindowId) otherObj).mToken);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return mToken.hashCode();
	}

	@Override
	public String toString() {
		return "WindowId{" + mToken + "}";
	}
}
