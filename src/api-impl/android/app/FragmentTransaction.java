package android.app;

/*
 * Adapted from AOSP platform/frameworks/base, tag android-17.0.0_r1
 * (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d):
 * FragmentTransaction.add/remove/commit are the abstract operations realized by
 * BackStackRecord (BackStackRecord.java:408-411, 474-476).
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
public abstract class FragmentTransaction {

	/**
	 * Add a fragment to the activity state, associating it with a tag.
	 * The fragment is only added to the manager once the transaction is
	 * executed (commit / executePendingTransactions).
	 */
	public abstract FragmentTransaction add(Fragment fragment, String tag);

	/**
	 * Remove an existing fragment from the activity state. The removal is
	 * only applied once the transaction is executed.
	 */
	public abstract FragmentTransaction remove(Fragment fragment);

	/**
	 * Commit this transaction. Queues the transaction. Returns -1 because no back-stack entry is added.
	 */
	public abstract int commit();
}
