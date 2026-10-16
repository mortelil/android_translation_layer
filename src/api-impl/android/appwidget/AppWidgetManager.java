package android.appwidget;

import android.content.ComponentName;
import android.content.Context;

public class AppWidgetManager {

	public static AppWidgetManager getInstance(Context context) {
		return new AppWidgetManager();
	}

	public int[] getAppWidgetIds(ComponentName provider) {
		return new int[0];
	}

	/*
	 * "TRUE if the default launcher supports requestPinAppWidget"
	 * (core/java/android/appwidget/AppWidgetManager.java:1498-1508) — AOSP asks the appwidget
	 * system service, which forwards to the shortcut service
	 * (services/appwidget/java/com/android/server/appwidget/AppWidgetServiceImpl.java:2649-2663
	 * -> services/core/java/com/android/server/pm/ShortcutService.java:2819-2830 ->
	 * ShortcutRequestPinProcessor), i.e. the answer is decided entirely by whether a launcher
	 * that implements pinning exists for the user. ATL installs no launcher package and has no
	 * ShortcutService, so there is no launcher to support the request and the documented answer
	 * is false; nothing is queried and no capability is invented.
	 */
	public boolean isRequestPinAppWidgetSupported() { return false; }

	public void notifyAppWidgetViewDataChanged(int appWidgetId, int viewId) {}

	public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {}
}
