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

	private static final ThreadLocal<Long> callingIdentity = new ThreadLocal<>();
	private static long identity() {
		Long id = callingIdentity.get();
		return id == null ? ((long)Process.myUid() << 32) | (Process.myPid() & 0xffffffffL) : id;
	}
	static long atlEnter(int pid) {
		long old = identity();
		callingIdentity.set(((long)Process.myUid() << 32) | (pid & 0xffffffffL));
		return old;
	}
	public static long clearCallingIdentity() { return atlEnter(Process.myPid()); }

	public static void restoreCallingIdentity(long identityToken) { callingIdentity.set(identityToken); }

	@Override
	public IInterface queryLocalInterface(String descriptor) {
		return mDescriptor != null && mDescriptor.equals(descriptor) ? mOwner : null;
	}

	@Override
	public boolean transact(int code, Parcel data, Parcel reply, int flags) {
		if (data != null)
			data.setDataPosition(0);
		boolean result = onTransact(code, data, reply, flags);
		if (reply != null)
			reply.setDataPosition(0);
		return result;
	}

	// AOSP frameworks/base android-14.0.0_r1 local Binder contract.
	// Dump and shell commands remain unsupported.
	protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
		if (code == 0x5f4e5446 && reply != null) {
			reply.writeString(getInterfaceDescriptor());
			return true;
		}
		return false;
	}

	// Transport supplies a connection-scoped caller identity.
	public static int getCallingUid() { return (int)(identity() >>> 32); }

	public static int getCallingPid() { return (int)identity(); }

	@Override
	public boolean equals(Object obj) {
		return this == obj;
	}
}
