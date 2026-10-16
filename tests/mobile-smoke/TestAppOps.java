// SPDX-License-Identifier: GPL-3.0-only
public class TestAppOps {
	public static void run(android.content.Context context) {
		android.app.AppOpsManager ops = (android.app.AppOpsManager) context.getSystemService("appops");
		String pkg = context.getPackageName();
		int uid = android.os.Process.myUid();
		for (int i = 0; i < 2; ++i) {
			equal(ops.checkOpNoThrow("android:get_usage_stats", uid, pkg), 1);
			equal(ops.checkOpNoThrow("android:system_alert_window", uid, pkg), 1);
			equal(ops.checkOpNoThrow("android:picture_in_picture", uid, pkg), 1);
			equal(ops.checkOpNoThrow("android:camera", uid, pkg), 1);
			equal(ops.checkOpNoThrow("android:read_external_storage", uid, pkg), 0);
			equal(ops.checkOpNoThrow("android:write_external_storage", uid, pkg), 0);
		}
		String[] operations = {"android:coarse_location", "android:fine_location", "android:record_audio"};
		String[] permissions = {"ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "RECORD_AUDIO"};
		for (int i = 0; i < operations.length; ++i) {
			int permission = context.getPackageManager().checkPermission("android.permission." + permissions[i], pkg);
			equal(ops.checkOpNoThrow(operations[i], uid, pkg), permission == 0 ? 0 : 1);
		}
		equal(ops.checkOpNoThrow("android:read_external_storage", uid + 1, pkg), 2);
		equal(ops.checkOpNoThrow("android:read_external_storage", uid, "other.package"), 2);
		equal(ops.checkOpNoThrow("android:read_external_storage", uid, null), 2);
		equal(new android.app.AppOpsManager().checkOpNoThrow("android:read_external_storage", uid, pkg), 2);
		for (String invalid : new String[] {null, "", "android:not_a_real_op", "android.permission.READ_EXTERNAL_STORAGE"}) {
			try {
				ops.checkOpNoThrow(invalid, uid, pkg);
				throw new AssertionError("Invalid op accepted: " + invalid);
			} catch (IllegalArgumentException expected) { }
		}
		// Exercise both policy outcomes independently of the developer's environment.
		final int[] permissionMode = {android.content.pm.PackageManager.PERMISSION_DENIED};
		final int[] permissionCalls = {0};
		final android.content.pm.PackageManager pm = new android.content.pm.PackageManager() {
			@Override public int checkPermission(String permission, String packageName) {
				++permissionCalls[0];
				return permissionMode[0];
			}
		};
		android.content.Context wrapper = new android.content.ContextWrapper(context) {
			@Override public android.content.pm.PackageManager getPackageManager() { return pm; }
		};
		android.app.AppOpsManager policy = new android.app.AppOpsManager(wrapper);
		equal(policy.checkOpNoThrow("android:record_audio", uid, pkg), 1);
		permissionMode[0] = android.content.pm.PackageManager.PERMISSION_GRANTED;
		equal(policy.checkOpNoThrow("android:record_audio", uid, pkg), 0);
		equal(permissionCalls[0], 2);
		equal(policy.checkOpNoThrow("android:get_usage_stats", uid, pkg), 1);
		equal(policy.checkOpNoThrow("android:record_audio", uid, "foreign.package"), 2);
		equal(permissionCalls[0], 2);
		System.out.println("PASS: AppOps query identity, capability policy, invalid names and repeated queries");
	}

	private static void equal(int actual, int expected) {
		if (actual != expected) throw new AssertionError("AppOps expected " + expected + ", got " + actual);
	}
}
