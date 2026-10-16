// SPDX-License-Identifier: GPL-3.0-only
// Contract tests for the PowerManager thermal status API. ATL has no thermal
// service, so the registration calls and the status query must fail explicitly
// instead of reporting a fabricated status or silently monitoring nothing.
public class TestThermalStatus {
	public static void run(android.content.Context context) {
		if (android.os.PowerManager.THERMAL_STATUS_INVALID != -1 ||
		    android.os.PowerManager.THERMAL_STATUS_NONE != 0 ||
		    android.os.PowerManager.THERMAL_STATUS_LIGHT != 1 ||
		    android.os.PowerManager.THERMAL_STATUS_MODERATE != 2 ||
		    android.os.PowerManager.THERMAL_STATUS_SEVERE != 3 ||
		    android.os.PowerManager.THERMAL_STATUS_CRITICAL != 4 ||
		    android.os.PowerManager.THERMAL_STATUS_EMERGENCY != 5 ||
		    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN != 6)
			throw new AssertionError("Thermal status constants must keep the public Android values");
		System.out.println("PASS: thermal status constants match the public Android values");

		android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService("power");
		java.util.concurrent.Executor executor = new java.util.concurrent.Executor() {
			public void execute(Runnable command) {
				command.run();
			}
		};
		android.os.PowerManager.OnThermalStatusChangedListener listener = new android.os.PowerManager.OnThermalStatusChangedListener() {
			public void onThermalStatusChanged(int status) {
				throw new AssertionError("No thermal status may be reported without a thermal backend");
			}
		};

		// Argument validation mirrors AOSP PowerManager: listener, then executor.
		try {
			pm.addThermalStatusListener(executor, null);
			throw new AssertionError("Null thermal status listener accepted");
		} catch (NullPointerException expected) {
		}
		try {
			pm.addThermalStatusListener(null, listener);
			throw new AssertionError("Null executor accepted");
		} catch (NullPointerException expected) {
		}
		try {
			pm.removeThermalStatusListener(null);
			throw new AssertionError("Null thermal status listener accepted");
		} catch (NullPointerException expected) {
		}

		// No thermal service exists, so these must not pretend to succeed.
		try {
			pm.addThermalStatusListener(executor, listener);
			throw new AssertionError("Thermal status registration invented a working backend");
		} catch (UnsupportedOperationException expected) {
		}
		try {
			pm.removeThermalStatusListener(listener);
			throw new AssertionError("Thermal status de-registration invented a working backend");
		} catch (UnsupportedOperationException expected) {
		}
		try {
			pm.getCurrentThermalStatus();
			throw new AssertionError("Thermal status invented without a backend");
		} catch (UnsupportedOperationException expected) {
		}
		System.out.println("PASS: thermal status API validates arguments and reports the missing backend");
	}
}
