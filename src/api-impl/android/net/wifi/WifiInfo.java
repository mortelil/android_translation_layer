package android.net.wifi;

/**
 * AOSP android/net/wifi/WifiInfo.java: the link-speed/RSSI fields start at their
 * "unknown" sentinels (LINK_SPEED_UNKNOWN / INVALID_RSSI, set by the no-arg
 * constructor at AOSP WifiInfo.java:520 and reset() :538-543) and are only filled
 * in by the supplicant. ATL has no Wi-Fi backend, so the honest values are the
 * sentinels - the same values AOSP reports on a device with no supplicant data.
 */
public class WifiInfo {
	/** AOSP WifiInfo.java:241. */
	public static final int LINK_SPEED_UNKNOWN = -1;

	/** AOSP WifiInfo.java:155. */
	public static final int INVALID_RSSI = -127;

	/** Android's unavailable/redacted SSID value; ATL has no Wi-Fi identity backend. */
	public String getSSID() { return "<unknown ssid>"; }

	/** AOSP WifiInfo.java:1013 (mRssi is INVALID_RSSI until the supplicant reports one). */
	public int getRssi() {
		return INVALID_RSSI;
	}

	/** AOSP WifiInfo.java:1059 (mLinkSpeed is LINK_SPEED_UNKNOWN until the supplicant reports one). */
	public int getLinkSpeed() {
		return LINK_SPEED_UNKNOWN;
	}

	public String getMacAddress() {
		return "";
	}

	public String getBSSID() {
		return "";
	}

	public int getIpAddress() {
		// No Wi-Fi IPv4 address is currently available from this implementation.
		return 0;
	}
}
