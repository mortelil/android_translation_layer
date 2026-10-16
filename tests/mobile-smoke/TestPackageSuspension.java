// SPDX-License-Identifier: GPL-3.0-only
// Contract tests for PackageManager.isPackageSuspended() (zero-argument
// caller-package query) and PackageManager.isPackageSuspended(String).
public class TestPackageSuspension {
	public static void run(android.content.Context context) {
		android.content.pm.PackageManager pm = context.getPackageManager();
		String caller = context.getPackageName();
		try {
			// ATL has no suspension mechanism, so an installed package - in
			// particular the calling package - is never suspended.
			if (pm.isPackageSuspended())
				throw new AssertionError("Calling package reported as suspended");
			if (pm.isPackageSuspended(caller))
				throw new AssertionError("Installed package reported as suspended: " + caller);
			System.out.println("PASS: isPackageSuspended reports installed packages as unsuspended");

			// An unknown or invalid package must not be accepted as unsuspended.
			String[] unknown = {null, "", "org.atl.suspension.notinstalled"};
			for (String name : unknown) {
				try {
					boolean suspended = pm.isPackageSuspended(name);
					throw new AssertionError("Unknown package accepted (" + suspended + "): " + name);
				} catch (android.content.pm.PackageManager.NameNotFoundException expected) {
				}
			}
			System.out.println("PASS: isPackageSuspended rejects unknown and invalid package names");
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}
}
