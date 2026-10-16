package android.net.wifi;

public class WifiInfo {
	/** Android's unavailable/redacted SSID value; ATL has no Wi-Fi identity backend. */
	public String getSSID() { return "<unknown ssid>"; }

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
