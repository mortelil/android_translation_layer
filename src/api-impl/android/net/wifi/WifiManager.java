package android.net.wifi;

public class WifiManager {

	public static final int WIFI_STATE_DISABLED = 1;
	public static final int WIFI_STATE_DISABLING = 0;
	public static final int WIFI_STATE_ENABLED = 3;
	public static final int WIFI_STATE_ENABLING = 2;
	public static final int WIFI_STATE_UNKNOWN = 4;

	public class WifiLock {

		public void setReferenceCounted(boolean referenceCounted) {}

		public void release() {}

		public void acquire() {}

		public boolean isHeld() { return false; }
	}

	/** Linux delivers multicast to subscribed sockets without Android's Wi-Fi filter. */
	public class MulticastLock {
		private final String tag;
		private boolean referenceCounted = true;
		private int references;
		private boolean held;

		private MulticastLock(String tag) { this.tag = tag; }
		public synchronized void setReferenceCounted(boolean value) { referenceCounted = value; }
		public synchronized void acquire() {
			if (referenceCounted)
				references++;
			held = true;
		}
		public synchronized void release() {
			if (referenceCounted) {
				if (references == 0)
					throw new RuntimeException("MulticastLock under-locked: " + tag);
				if (--references != 0)
					return;
			}
			held = false;
		}
		public synchronized boolean isHeld() { return held; }
	}

	public MulticastLock createMulticastLock(String tag) {
		return new MulticastLock(tag);
	}

	public WifiLock createWifiLock(int lockType, String tag) {
		return new WifiLock();
	}

	public WifiLock createWifiLock(String tag) {
		return new WifiLock();
	}

	public WifiInfo getConnectionInfo() {
		return new WifiInfo();
	}

	public int getWifiState() {
		return WIFI_STATE_UNKNOWN;
	}

	public boolean isWifiEnabled() {
		return false;
	}
}
