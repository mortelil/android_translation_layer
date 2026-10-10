package android.app;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.IBinder;

public abstract class Service extends ContextWrapper {

	private int notification_id;

	public Service() {
		super(null);
	}

	public void onCreate() {
		System.out.println("Service.onCreate() called");
	}

	public void onDestroy() {
		System.out.println("Service.onDestroy() called");
	}

	public abstract IBinder onBind(Intent intent);

	public int onStartCommand(Intent intent, int flags, int startId) {
		System.out.println("Service.onStartCommand(" + intent + ", " + flags + ", " + startId + ") called");
		return 0;
	}

	public void startForeground(int id, Notification notification) {
		System.out.println("startForeground(" + id + ", " + notification + ") called");
		this.notification_id = id;
	}

	public void stopForeground(boolean remove) {
		System.out.println("stopForeground(" + remove + ") called");
		if (remove)
			new NotificationManager().cancel(notification_id);
	}

	public void stopForeground(int remove) {
		stopForeground(remove == 1);
	}

	public Application getApplication() {
		return this.get_atl_loaded_app().getApplication();
	}

	public void stopSelf(int startId) {
		System.out.println("Service.stopSelf(" + startId + ") called");
		ATLLoadedApp app = atl_loaded_app_or_null();
		if (app != null) {
			app.stopServiceFromSelf(this, startId);
		}
	}

	public void stopSelf() {
		// AOSP: stopSelf() == stopSelf(-1) -> stop the current instance.
		stopSelf(-1);
	}

	public boolean stopSelfResult(int startId) {
		System.out.println("Service.stopSelfResult(" + startId + ") called");
		ATLLoadedApp app = atl_loaded_app_or_null();
		return app != null && app.stopServiceFromSelf(this, startId);
	}

	/**
	 * The owning application, or null when this instance was never attached to a
	 * base context (AOSP's stopSelf* then silently does nothing / returns false).
	 */
	private ATLLoadedApp atl_loaded_app_or_null() {
		return this.baseContext == null ? null : this.baseContext.get_atl_loaded_app();
	}

	public void attachBaseContext(Context newBase) {
		super.attachBaseContext(newBase);
		System.out.println("Service.attachBaseContext(" + newBase + ") called");
	}
}
