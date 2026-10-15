package android.bluetooth;

public class BluetoothAdapter {

	/** No Bluetooth backend is currently exposed by ATL. */
	public boolean isEnabled() {
		return false;
	}

	public static BluetoothAdapter getDefaultAdapter() {
		return null;
	}
}
