package android.telephony;

public class SubscriptionManager {
	public static final int INVALID_SUBSCRIPTION_ID = -1;

	public static int getDefaultDataSubscriptionId() {
		// ATL has no subscription backend, so no data subscription is available.
		return INVALID_SUBSCRIPTION_ID;
	}

	public static int getDefaultSmsSubscriptionId() {
		// No SMS subscription is exposed until ATL has a telephony backend.
		return INVALID_SUBSCRIPTION_ID;
	}
}
