package android.app;

import java.util.ArrayList;

/*
 * Adapted from AOSP platform/frameworks/base, tag android-17.0.0_r1
 * (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d):
 * BackStackRecord.doAddOp (BackStackRecord.java:423-442), add(Fragment,String)
 * (408-411), remove(Fragment) (474-476) and the OP_ADD/OP_REMOVE op codes
 * (BackStackRecord.java:180-182). AOSP's executeOps()/executePopOps() are
 * collapsed into executeOps() because the ATL manager keeps no back stack.
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
final class BackStackRecord extends FragmentTransaction {

	static final int OP_ADD = 1;
	static final int OP_REMOVE = 3;

	private final FragmentManager mManager;
	private final ArrayList<Op> mOps = new ArrayList<>();

	BackStackRecord(FragmentManager manager) {
		mManager = manager;
	}

	@Override
	public FragmentTransaction add(Fragment fragment, String tag) {
		doAddOp(fragment, tag, OP_ADD);
		return this;
	}

	@Override
	public FragmentTransaction remove(Fragment fragment) {
		addOp(fragment, OP_REMOVE);
		return this;
	}

	@Override
	public int commit() {
		mManager.enqueueAndExecute(this);
		return 0;
	}

	private void doAddOp(Fragment fragment, String tag, int opcmd) {
		// The tag is assigned when the op is recorded (AOSP sets mTag in
		// doAddOp, before execution), so getTag() is readable immediately.
		fragment.activity = mManager.activity;
		if (tag != null) {
			if (fragment.mTag != null && !tag.equals(fragment.mTag)) {
				throw new IllegalStateException("Can't change tag of fragment "
						+ fragment + ": was " + fragment.mTag + " now " + tag);
			}
			fragment.mTag = tag;
		}
		addOp(fragment, opcmd);
	}

	private void addOp(Fragment fragment, int opcmd) {
		mOps.add(new Op(opcmd, fragment));
	}

	void executeOps() {
		for (Op op : mOps) {
			switch (op.cmd) {
			case OP_ADD:
				mManager.addFragment(op.fragment);
				break;
			case OP_REMOVE:
				mManager.removeFragment(op.fragment);
				break;
			}
		}
		mOps.clear();
	}

	private static final class Op {
		final int cmd;
		final Fragment fragment;

		Op(int cmd, Fragment fragment) {
			this.cmd = cmd;
			this.fragment = fragment;
		}
	}
}
