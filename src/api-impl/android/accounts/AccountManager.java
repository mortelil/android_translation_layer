package android.accounts;

import android.content.Context;
import android.os.Bundle;

public class AccountManager {

	public static AccountManager get(Context context) {
		return new AccountManager();
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
