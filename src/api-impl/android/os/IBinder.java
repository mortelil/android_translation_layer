package android.os;

public interface IBinder {

	public interface DeathRecipient { void binderDied(); }

    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
    boolean isBinderAlive();
    boolean pingBinder();

	public IInterface queryLocalInterface(String descriptor);

	public boolean transact(int code, Parcel data, Parcel reply, int flags);

	public String getInterfaceDescriptor();
}
