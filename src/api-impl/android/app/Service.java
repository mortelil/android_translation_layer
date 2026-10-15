package android.app;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.IBinder;

public abstract class Service extends ContextWrapper {

	// onStartCommand() return values and start flags; values from AOSP
	// frameworks-base core/java/android/app/Service.java
	// (android-17.0.0_r1, 94b4c163) lines 388-483.
	/** Compatibility return: like START_STICKY, but onStartCommand() is not re-called on restart. */
	public static final int START_STICKY_COMPATIBILITY = 0;
	/** Restart the service after its process is killed, delivering a null intent. */
	public static final int START_STICKY = 1;
	/** Do not restart the service after its process is killed unless start requests are pending. */
	public static final int START_NOT_STICKY = 2;
	/** Restart the service after its process is killed, redelivering the last intent. */
	public static final int START_REDELIVER_INTENT = 3;
	/** Internal result for onTaskRemoved() completion; not a regular onStartCommand() result. */
	public static final int START_TASK_REMOVED_COMPLETE = 1000;
	/** onStartCommand() flag: the intent is a redelivery of a START_REDELIVER_INTENT start. */
	public static final int START_FLAG_REDELIVERY = 0x0001;
	/** onStartCommand() flag: the start is a retry of an earlier unacknowledged delivery. */
	public static final int START_FLAG_RETRY = 0x0002;

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

	/**
	 * Called when the last bound client unbinds. Returning true would allow
	 * onRebind() while the service stays alive; ATL destroys a purely bound
	 * service on the last unbind regardless, matching the reference behaviour.
	 */
	public boolean onUnbind(Intent intent) {
		return false;
	}

	public void onRebind(Intent intent) {
	}

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
