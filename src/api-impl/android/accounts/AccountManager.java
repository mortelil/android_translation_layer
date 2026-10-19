package android.accounts;

import android.content.Context;
import android.os.Bundle;

public class AccountManager {
	private static final AccountManager INSTANCE = new AccountManager();
	private final java.util.Map<OnAccountsUpdateListener, android.os.Handler> listeners = new java.util.HashMap<>();

	/** Listener support for ATL's currently empty account store. No login or
	 * account persistence is implied. Callbacks are delivered on the handler. */
	public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
	        android.os.Handler handler, boolean updateImmediately) {
		addOnAccountsUpdatedListener(listener, handler, updateImmediately, null);
	}

	public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
	        android.os.Handler handler, boolean updateImmediately, String[] accountTypes) {
		if (listener == null) throw new IllegalArgumentException("listener is null");
		android.os.Handler target = handler != null ? handler : new android.os.Handler(android.os.Looper.getMainLooper());
		synchronized (listeners) {
			if (listeners.containsKey(listener)) throw new IllegalStateException("listener already registered");
			listeners.put(listener, target);
		}
		if (updateImmediately) target.post(new Runnable() {
			@Override public void run() { listener.onAccountsUpdated(getAccounts()); }
		});
	}

	public void removeOnAccountsUpdatedListener(OnAccountsUpdateListener listener) {
		if (listener == null) throw new IllegalArgumentException("listener is null");
		synchronized (listeners) { listeners.remove(listener); }
	}


	public static AccountManager get(Context context) {
		return INSTANCE;
	}

	public Account[] getAccountsByType(String type) {
		return new Account[0];
	}

	/** Package-scoped view of the account store. ATL's current store is empty;
	 * do not invent an account or claim that login/account persistence works.
	 */
	public Account[] getAccountsByTypeForPackage(String type, String packageName) {
		if (packageName == null) throw new IllegalArgumentException("packageName is null");
		Account[] accounts = getAccountsByType(type);
		if (accounts.length != 0)
			throw new UnsupportedOperationException("Account visibility policy is not implemented");
		return accounts;
	}

	public Account[] getAccounts() {
		return new Account[0];
	}

	public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
		System.out.println("addAccountExplicitly(" + account + ", " + password + ", " + extras + ") called");
		return false;
	}
}
