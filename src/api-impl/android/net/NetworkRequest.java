package android.net;

public class NetworkRequest {
	public final NetworkCapabilities networkCapabilities;

	private NetworkRequest(NetworkCapabilities capabilities) {
		networkCapabilities = new NetworkCapabilities();
		for (int i = 0; i <= NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH; i++) {
			if (capabilities.hasCapability(i))
				networkCapabilities.addCapability(i);
			else
				networkCapabilities.removeCapability(i);
		}
		for (int i = 0; i <= NetworkCapabilities.TRANSPORT_THREAD; i++)
			if (capabilities.hasTransport(i))
				networkCapabilities.addTransportType(i);
	}

	public static class Builder {
		private final NetworkCapabilities capabilities = new NetworkCapabilities();

		public NetworkRequest build() {
			return new NetworkRequest(capabilities);
		}

		public Builder addCapability(int capability) {
			capabilities.addCapability(capability);
			return this;
		}

		public Builder removeCapability(int capability) {
			capabilities.removeCapability(capability);
			return this;
		}
	}
}
