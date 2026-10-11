package android.content;

import android.os.IBinder;

public interface ServiceConnection {
	public void onServiceConnected(ComponentName name, IBinder service);

	/**
	 * Called when the service process has died/the binding was lost. Default
	 * no-op so existing implementors keep compiling; an ordinary unbindService()
	 * never calls this.
	 */
	default void onServiceDisconnected(ComponentName name) {
	}

	/**
	 * Called instead of onServiceConnected() when the service returned a null
	 * IBinder from onBind(). Default no-op.
	 */
	default void onNullBinding(ComponentName name) {
	}

	/** Called when the binding died but the service may be rebound. Default no-op. */
	default void onBindingDied(ComponentName name) {
	}
}
