package android.app;

import android.util.AndroidRuntimeException;

import android.os.Bundle;

public class Fragment {

	Activity activity;
	private boolean created;
	private boolean activityCreated;
	private boolean attached;
	private boolean started;
	private boolean resumed;

	void performCreate(Bundle state) {
		if (!attached) {
			attached = true;
			onAttach((android.content.Context) activity);
		}
		if (!created) {
			created = true;
			onCreate(state);
		}
	}

	void performActivityCreated(Bundle state) {
		performCreate(state);
		if (!activityCreated) {
			activityCreated = true;
			onActivityCreated(state);
		}
	}

	// Headless fragment transitions: catch up on late add, never dispatch an
	// upward event twice, and unwind only states that were actually entered.
	void performStart() {
		if (!started) { started = true; onStart(); }
	}
	void performResume() {
		performStart();
		if (!resumed) { resumed = true; onResume(); }
	}
	void performPause() {
		if (resumed) { resumed = false; onPause(); }
	}
	void performStop() {
		performPause();
		if (started) { started = false; onStop(); }
	}
	void performDestroy() {
		performStop();
		if (created) { created = false; onDestroy(); }
		activityCreated = false;
		if (attached) { attached = false; onDetach(); }
		activity = null;
		mTag = null;
	}
	public void onAttach(android.content.Context context) {
		if (context instanceof Activity) onAttach((Activity) context);
	}
	@Deprecated public void onAttach(Activity activity) {}
	public void onDetach() {}


	/*
	 * Tag lookup adapted from AOSP platform/frameworks/base, tag
	 * android-17.0.0_r1 (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d):
	 * Fragment.getTag (Fragment.java:647-649) and the mTag assignment done by
	 * BackStackRecord.doAddOp (BackStackRecord.java:434-436).
	 *
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
	String mTag;

	/**
	 * Get the tag name of the fragment, if specified.
	 */
	public String getTag() {
		return mTag;
	}

	public void onCreate(Bundle savedInstanceState) {
	}

	public void onActivityCreated(Bundle savedInstanceState) {
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

	public FragmentManager getFragmentManager() {
		return activity == null ? null : activity.getFragmentManager();
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
