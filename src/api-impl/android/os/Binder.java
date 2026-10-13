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

package android.os;

import android.atl.ATLLoadedApp;
import android.content.Context;

public class Binder implements IBinder {

	private String mDescriptor;
	private IInterface mOwner;

	public void attachInterface(IInterface owner, String descriptor) {
		mOwner = owner;
		mDescriptor = descriptor;
	}

	public String getInterfaceDescriptor() {
		return mDescriptor;
	}

	public IInterface getOwner() {
		return mOwner;
	}

	public static void flushPendingCommands() {}

	public static long clearCallingIdentity() { return 0; }

	public static void restoreCallingIdentity(long identityToken) {}

	@Override
	public IInterface queryLocalInterface(String descriptor) {
		return mDescriptor != null && mDescriptor.equals(descriptor) ? mOwner : null;
	}

	@Override
	public boolean transact(int code, Parcel data, Parcel reply, int flags) {
        if (data != null) data.setDataPosition(0);
        boolean result = onTransact(code, data, reply, flags);
        if (reply != null) reply.setDataPosition(0);
        return result;
    }

    // AOSP frameworks/base android-14.0.0_r1 local Binder contract.
    // No cross-process transactions, remote identity, dump or shell commands.
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == 0x5f4e5446 && reply != null) {
            reply.writeString(getInterfaceDescriptor());
            return true;
        }
        return false;
    }

	// ATL currently implements only in-process Binder calls, with no remote transaction identity.
	public static int getCallingUid() { return Process.myUid(); }

	public static int getCallingPid() { return Process.myPid(); }

    // AOSP Binder.java (android-8.1.0_r1): a local Binder cannot die
    // independently of its caller's process. Remote Binder implementations must
    // provide their own transport and death notification behavior.
    @Override public void linkToDeath(DeathRecipient recipient, int flags) {}
    @Override public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }
    @Override public boolean isBinderAlive() { return true; }
    @Override public boolean pingBinder() { return true; }
}
