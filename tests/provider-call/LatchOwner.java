// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
/**
 * A provider whose install is slow enough that a second access has to wait on
 * the publication latch, and whose onCreate deliberately returns false: AOSP
 * ignores that value ({@code core/java/android/content/ContentProvider.java:2695}
 * calls onCreate and drops the result, inside the {@code mContext == null}
 * once-only guard of attachInfo at the same file:2667-2696), so the provider
 * must be published and usable anyway.
 *
 * <p>Read in the provider's own process:
 * <ul>
 * <li>{@code ATL_LATCH_SLEEP_MS} how long onCreate blocks (default 0).
 * <li>{@code ATL_LATCH_INTERRUPT_PROBE=1} starts a helper thread that resolves
 *     this same authority while the install is running, then interrupts it. The
 *     helper is a different thread from the installer, so it is a real waiter:
 *     it must be released by the interrupt with null, and the record must stay
 *     owned by the install that is still running.
 * </ul>
 */
public class LatchOwner extends ContentProvider {
	private static final Object STATICS = new Object();
	private static int ctors;
	private static int ons;
	private static int attaches;
	/** 0 not run, 1 waiter saw null, 2 waiter saw an instance. */
	private static volatile int helperResult;
	private static volatile boolean helperInterrupted;

	private final int token;
	private volatile boolean onDone;

	public LatchOwner() {
		synchronized (STATICS) { token = ++ctors; }
	}

	public void attachInfo(Context context, android.content.pm.ProviderInfo info) {
		synchronized (STATICS) { attaches++; }
		super.attachInfo(context, info);
	}

	public boolean onCreate() {
		// Evidence for the row harness: what the owner process actually sees.
		System.out.println("ATL_LATCH_SLEEP_MS=" + latchSleep() + " ATL_PROVIDER_READY_TIMEOUT_MS="
		                   + System.getenv("ATL_PROVIDER_READY_TIMEOUT_MS") + " pid=" + android.os.Process.myPid());
		synchronized (STATICS) { ons++; }
		boolean probe = "1".equals(System.getenv("ATL_LATCH_INTERRUPT_PROBE"));
		if (probe) {
			Thread waiter = new Thread(new Runnable() { public void run() {
				ContentProvider same = ContentProvider.atl_get_content_provider("org.atl.latch");
				helperResult = same == null ? 1 : 2;
				helperInterrupted = Thread.currentThread().isInterrupted();
			}}, "latch-waiter");
			waiter.start();
			sleep(400);
			waiter.interrupt();
			try { waiter.join(5000); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		}
		sleep(latchSleep());
		onDone = true;
		// AOSP drops this value; the provider is published either way.
		return false;
	}

	private static long latchSleep() {
		String value = System.getenv("ATL_LATCH_SLEEP_MS");
		if (value == null || value.isEmpty()) return 0L;
		try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return 0L; }
	}

	private static void sleep(long millis) {
		if (millis <= 0) return;
		try { Thread.sleep(millis); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}

	public Bundle call(String method, String arg, Bundle extras) {
		Bundle b = new Bundle();
		synchronized (STATICS) {
			b.putInt("ctors", ctors); b.putInt("ons", ons); b.putInt("attaches", attaches);
		}
		b.putInt("token", token);
		b.putBoolean("onDone", onDone);
		b.putBoolean("onReturned", false);
		b.putBoolean("helperRan", helperResult != 0);
		b.putBoolean("helperNull", helperResult == 1);
		b.putBoolean("helperInterrupted", helperInterrupted);
		b.putInt("pid", android.os.Process.myPid());
		return b;
	}

	public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
	public Uri insert(Uri u, ContentValues v) { return null; }
	public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
	public int delete(Uri u, String s, String[] a) { return 0; }
	public String getType(Uri u) { return null; }
	public ParcelFileDescriptor openFile(Uri u, String m) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
}
