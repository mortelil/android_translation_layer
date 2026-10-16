package android.os;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class PowerManager {
	public final class WakeLock {
		private boolean held;
		private boolean refCounted = true;
		private int count;

		public void setReferenceCounted(boolean referenceCounted) {
			refCounted = referenceCounted;
		}

		public void acquire() {
			if (refCounted) {
				count++;
			}
			held = true;
		}

		public void release() {
			if (refCounted) {
				count--;
				if (count > 0)
					return;
			}
			held = false;
		}

		public boolean isHeld() {
			return held;
		}

		public void acquire(long timeout) {
			acquire();
			new Thread(new Runnable() {
				public void run() {
					try {
						Thread.sleep(timeout);
					} catch (InterruptedException e) {
					}
					release();
				}
			}).start();
		}
	}

	public WakeLock newWakeLock(int levelAndFlags, String tag) {
		return new WakeLock();
	}

	public void goToSleep(long time) {
		// This requires DEVICE_POWER permission which is only possible for system processes. Any normal application gets a SecurityException.
		throw new SecurityException();
	}

	public void userActivity(long dummy, boolean dummy2) {}

	public static final int FULL_WAKE_LOCK = 0x1a;

	public boolean isPowerSaveMode() {
		return false;
	}

	public boolean isScreenOn() {
		return true;
	}

	public boolean isIgnoringBatteryOptimizations(String packageName) {
		return true;
	}

	public boolean isDeviceIdleMode() {
		return false;
	}

	public boolean isInteractive() {
		return true;
	}

	public boolean isWakeLockLevelSupported(int level) {
		return false;
	}

	/*
	 * Thermal status reporting.
	 *
	 * ATL has no thermal service: it reads no thermal data from the host and has no
	 * throttling policy, so it cannot observe a status and cannot deliver status
	 * changes. The constants and the listener type below are real API surface, but
	 * the two registration calls and the status query fail explicitly instead of
	 * accepting a registration that would never be honoured (AOSP PowerManager
	 * likewise throws when the thermal service refuses the registration).
	 *
	 * Values and signatures follow AOSP PowerManager
	 * (frameworks/base/core/java/android/os/PowerManager.java, tag android-17.0.0_r1,
	 * commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d).
	 */

	/**
	 * Invalid thermal status.
	 * @hide
	 */
	public static final int THERMAL_STATUS_INVALID = -1;

	/**
	 * Thermal status code: Not under throttling.
	 */
	public static final int THERMAL_STATUS_NONE = 0;

	/**
	 * Thermal status code: Light throttling where UX is not impacted.
	 */
	public static final int THERMAL_STATUS_LIGHT = 1;

	/**
	 * Thermal status code: Moderate throttling where UX is not largely impacted.
	 */
	public static final int THERMAL_STATUS_MODERATE = 2;

	/**
	 * Thermal status code: Severe throttling where UX is largely impacted.
	 */
	public static final int THERMAL_STATUS_SEVERE = 3;

	/**
	 * Thermal status code: Platform has done everything to reduce power.
	 */
	public static final int THERMAL_STATUS_CRITICAL = 4;

	/**
	 * Thermal status code: Key components in platform are shutting down due to
	 * thermal condition. Device functionalities will be limited.
	 */
	public static final int THERMAL_STATUS_EMERGENCY = 5;

	/**
	 * Thermal status code: Need shutdown immediately.
	 */
	public static final int THERMAL_STATUS_SHUTDOWN = 6;

	/**
	 * Listener passed to
	 * {@link PowerManager#addThermalStatusListener} and
	 * {@link PowerManager#removeThermalStatusListener}
	 * to notify caller of thermal status has changed.
	 */
	public interface OnThermalStatusChangedListener {
		/**
		 * Called when overall thermal throttling status changed.
		 * @param status the status
		 */
		void onThermalStatusChanged(int status);
	}

	/**
	 * This function adds a listener for thermal status change.
	 *
	 * @param executor {@link Executor} to handle listener callback.
	 * @param listener listener to be added.
	 */
	public void addThermalStatusListener(Executor executor, OnThermalStatusChangedListener listener) {
		Objects.requireNonNull(listener, "Thermal status listener cannot be null");
		Objects.requireNonNull(executor, "Executor cannot be null");
		throw new UnsupportedOperationException("ATL has no thermal service: thermal status cannot be monitored");
	}

	/**
	 * This function removes a listener for thermal status change.
	 *
	 * @param listener listener to be removed
	 */
	public void removeThermalStatusListener(OnThermalStatusChangedListener listener) {
		Objects.requireNonNull(listener, "Thermal status listener cannot be null");
		throw new UnsupportedOperationException("ATL has no thermal service: no thermal status listener can be registered");
	}

	/**
	 * This function returns the current thermal status of the device.
	 *
	 * @return thermal status as int
	 */
	public int getCurrentThermalStatus() {
		throw new UnsupportedOperationException("ATL has no thermal service: no thermal status is available");
	}
}
