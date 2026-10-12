package android.os;

public interface IBinder {

	int FIRST_CALL_TRANSACTION = 1;
	int LAST_CALL_TRANSACTION = 0x00ffffff;
	int INTERFACE_TRANSACTION = 0x5f4e5446;
	int FLAG_ONEWAY = 1;
	public interface DeathRecipient {
		void binderDied();
	}
	default boolean pingBinder() { return true; }
	default boolean isBinderAlive() { return true; }
	default void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {}
	default boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }

	public IInterface queryLocalInterface(String descriptor);

	public boolean transact(int code, Parcel data, Parcel reply, int flags);

	public String getInterfaceDescriptor();
}
