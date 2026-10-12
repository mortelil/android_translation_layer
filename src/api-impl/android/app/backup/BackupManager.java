package android.app.backup;

import android.content.Context;

public class BackupManager {
	public BackupManager(Context context) {
	}

	/**
	 * AOSP: notifies the backup system that the named package's data changed.
	 * ATL has no backup service, so this is a no-op like {@link #dataChanged()};
	 * the member exists so desugared/app call sites such as
	 * YouTubeBackupAgent.c(Context) link.
	 */
	public static void dataChanged(String packageName) {}

	public void dataChanged() {}
}
