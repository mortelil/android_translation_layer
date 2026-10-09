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
	public boolean transact(int code, Parcel data, Parcel reply, int flags) { return false; }

	// ATL currently implements only in-process Binder calls, with no remote transaction identity.
	public static int getCallingUid() { return Process.myUid(); }

	public static int getCallingPid() { return Process.myPid(); }

	@Override
	public boolean equals(Object obj) {
		return obj instanceof Binder;
	}
}
