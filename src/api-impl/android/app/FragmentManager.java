package android.app;

import java.util.ArrayList;
import java.util.List;

/*
 * Adapted from AOSP platform/frameworks/base, tag android-17.0.0_r1
 * (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d):
 * FragmentManager.findFragmentByTag (FragmentManager.java:1847-1866: reverse
 * scan of mAdded, null tag returns null), addFragment (1710-1731: duplicate add
 * throws, append preserves add order), removeFragment (1734-1752) and the
 * pending-action execution in executePendingTransactions / enqueueAction.
 * ATL keeps the added fragments in Activity.fragments and purges a removed
 * fragment outright, so lookup after remove returns null (equivalent to AOSP's
 * makeInactive/burpActive purge of mActive).
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
public class FragmentManager {

	Activity activity;

	private final List<BackStackRecord> mPendingTransactions = new ArrayList<>();

	public FragmentManager(Activity activity) {
		this.activity = activity;
	}

	public Fragment findFragmentByTag(String tag) {
		if (tag == null) {
			return null;
		}
		// Reverse scan of the added fragments: when several fragments share a
		// tag, the newest-added one wins (AOSP FragmentManager.java:1849-1856).
		for (int i = activity.fragments.size() - 1; i >= 0; i--) {
			Fragment f = activity.fragments.get(i);
			if (f != null && tag.equals(f.mTag)) {
				return f;
			}
		}
		return null;
	}

	public FragmentTransaction beginTransaction() {
		return new BackStackRecord(this);
	}

	public boolean executePendingTransactions() {
		return execPendingActions();
	}

	boolean execPendingActions() {
		boolean didSomething = false;
		while (!mPendingTransactions.isEmpty()) {
			BackStackRecord transaction = mPendingTransactions.remove(0);
			transaction.executeOps();
			didSomething = true;
		}
		return didSomething;
	}

	void enqueueAndExecute(BackStackRecord transaction) {
		mPendingTransactions.add(transaction);
		execPendingActions();
	}

	void addFragment(Fragment fragment) {
		if (activity.fragments.contains(fragment)) {
			throw new IllegalStateException("Fragment already added: " + fragment);
		}
		activity.fragments.add(fragment);
	}

	void removeFragment(Fragment fragment) {
		activity.fragments.remove(fragment);
	}
}