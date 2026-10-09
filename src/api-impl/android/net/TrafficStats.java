package android.net;

public class TrafficStats {
	public static final int UNSUPPORTED = -1;

	// ATL has no persistent network accounting service. Interface snapshots cannot
	// supply Android's monotonic since-boot totals across interface removal/reset.
	public static long getTotalRxBytes() { return UNSUPPORTED; }
	public static long getTotalTxBytes() { return UNSUPPORTED; }
	public static long getTotalRxPackets() { return UNSUPPORTED; }
	public static long getTotalTxPackets() { return UNSUPPORTED; }

	public static void setThreadStatsTag(int dummy) {}

	public static int getThreadStatsTag() {
		return 0;
	}

	public static void clearThreadStatsTag() {}

	public static long getUidRxBytes(int uid) {
		return -1;
	}

	public static long getUidTxBytes(int uid) {
		return -1;
	}
}
