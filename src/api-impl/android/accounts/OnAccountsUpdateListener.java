// SPDX-License-Identifier: GPL-3.0-only
package android.accounts;
/** Receives the current accounts visible to this application. */
public interface OnAccountsUpdateListener {
    void onAccountsUpdated(Account[] accounts);
}
