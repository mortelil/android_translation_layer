package android.app;

import android.util.AndroidRuntimeException;

import android.os.Bundle;

public class Fragment {

	Activity activity;

	public void onCreate(Bundle savedInstanceState) {
	}

	public void onStart() {
	}

	public void onResume() {
	}

	public void onPause() {
	}

	public void onStop() {
	}

	public void onDestroy() {
	}

	public Activity getActivity() {
		return activity;
	}

	// Exception API adapted from AOSP frameworks/base, commit
	// 99b01a65cc4c104933788b3143285ab6bae65827 (android-16.0.0_r1).
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
	@Deprecated
	public static class InstantiationException extends AndroidRuntimeException {
		public InstantiationException(String message, Exception cause) {
			super(message, cause);
		}
	}
}
