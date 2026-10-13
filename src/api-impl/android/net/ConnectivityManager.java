package android.net;

import android.os.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;

class ProxyInfo {}

public class ConnectivityManager {
	private final IdentityHashMap<NetworkCallback, ArrayList<Registration>> registrations = new IdentityHashMap<>();
	private final IdentityHashMap<OnNetworkActiveListener, NetworkCallback> activeListeners = new IdentityHashMap<>();

	public interface OnNetworkActiveListener {
		void onNetworkActive();
	}

	public boolean isDefaultNetworkActive() { return nativeGetNetworkAvailable(); }

	public synchronized void addDefaultNetworkActiveListener(final OnNetworkActiveListener listener) {
		if (listener == null)
			throw new NullPointerException();
		if (activeListeners.containsKey(listener))
			return;
		NetworkCallback callback = new NetworkCallback() {
			@Override
			public void onAvailable(Network network) { listener.onNetworkActive(); }
		};
		registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
		activeListeners.put(listener, callback);
	}

	public synchronized void removeDefaultNetworkActiveListener(OnNetworkActiveListener listener) {
		NetworkCallback callback = activeListeners.remove(listener);
		if (callback == null)
			throw new IllegalArgumentException("Listener is not registered");
		unregisterNetworkCallback(callback);
	}

	private static final Network HOST_NETWORK = new Network();

	public static class NetworkCallback {
		private volatile boolean automaticallyReleased;
		public void onAvailable(Network network) {}
		public void onLost(Network network) {}
		public void onUnavailable() {}
		public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {}
		public void onLinkPropertiesChanged(Network network, LinkProperties properties) {}
	}

	public NetworkInfo getNetworkInfo(int networkType) {
		return new NetworkInfo(nativeGetNetworkAvailable());
	}

	public NetworkInfo getNetworkInfo(Network network) {
		return network == null ? null : getActiveNetworkInfo();
	}

	public NetworkInfo getActiveNetworkInfo() {
		return new NetworkInfo(nativeGetNetworkAvailable());
	}

	private final class Registration extends NetworkCallback {
		final NetworkCapabilities requested;
		final NetworkCallback callback;
		final Handler handler;
		volatile boolean active = true;
		long nativeId;
		NetworkCapabilities last;
		boolean fulfilled;
		final Runnable timeout = new Runnable() {
			@Override public void run() {
				if (!active || fulfilled) return;
				if (release(Registration.this, true)) callback.onUnavailable();
			}
		};

		Registration(NetworkRequest request, NetworkCallback callback, Handler handler) {
			this.requested = copyCapabilities(request.networkCapabilities);
			this.callback = callback;
			this.handler = handler;
		}

		@Override public void onAvailable(Network ignored) { postState(hostCapabilities()); }
		@Override public void onLost(Network ignored) { postState(null); }

		void postState(final NetworkCapabilities capabilities) {
			handler.post(new Runnable() {
				@Override public void run() { update(capabilities); }
			});
		}

		void update(NetworkCapabilities capabilities) {
			if (!active) return;
			if (capabilities == null || !matches(requested, capabilities)) {
				if (last != null) {
					last = null;
					callback.onLost(HOST_NETWORK);
				}
				return;
			}
			boolean first = last == null;
			boolean changed = first || !sameCapabilities(last, capabilities);
			last = capabilities;
			fulfilled = true;
			handler.removeCallbacks(timeout);
			if (first) callback.onAvailable(HOST_NETWORK);
			// A callback may unregister itself in onAvailable.
			if (active && changed) callback.onCapabilitiesChanged(HOST_NETWORK, copyCapabilities(capabilities));
		}
	}

	private static NetworkCapabilities copyCapabilities(NetworkCapabilities source) {
		NetworkCapabilities copy = new NetworkCapabilities();
		for (int i = 0; i <= NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH; i++)
			if (source.hasCapability(i)) copy.addCapability(i); else copy.removeCapability(i);
		for (int i = 0; i <= NetworkCapabilities.TRANSPORT_THREAD; i++)
			if (source.hasTransport(i)) copy.addTransportType(i);
		return copy;
	}

	private static boolean sameCapabilities(NetworkCapabilities a, NetworkCapabilities b) {
		for (int i = 0; i <= NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH; i++)
			if (a.hasCapability(i) != b.hasCapability(i)) return false;
		for (int i = 0; i <= NetworkCapabilities.TRANSPORT_THREAD; i++)
			if (a.hasTransport(i) != b.hasTransport(i)) return false;
		return true;
	}

	private static boolean matches(NetworkCapabilities request, NetworkCapabilities available) {
		for (int i = 0; i <= NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH; i++)
			if (request.hasCapability(i) && !available.hasCapability(i)) return false;
		boolean transportRequested = false, transportMatches = false;
		for (int i = 0; i <= NetworkCapabilities.TRANSPORT_THREAD; i++) {
			transportRequested |= request.hasTransport(i);
			transportMatches |= request.hasTransport(i) && available.hasTransport(i);
		}
		return !transportRequested || transportMatches;
	}

	private synchronized void register(NetworkRequest request, NetworkCallback callback, Handler handler, int timeoutMs) {
		if (request == null || callback == null || handler == null) throw new NullPointerException();
		Registration registration = new Registration(request, callback, handler);
		ArrayList<Registration> list = registrations.get(callback);
		if (list == null) {
			list = new ArrayList<>();
			registrations.put(callback, list);
		}
		list.add(registration);
		callback.automaticallyReleased = false;
		registration.nativeId = nativeRegisterNetworkCallback(request, registration);
		registration.postState(nativeGetNetworkAvailable() ? hostCapabilities() : null);
		if (timeoutMs > 0) handler.postDelayed(registration.timeout, timeoutMs);
	}

	private synchronized boolean release(Registration registration, boolean timedOut) {
		if (!registration.active) return false;
		registration.active = false;
		registration.handler.removeCallbacks(registration.timeout);
		nativeUnregisterNetworkCallback(registration.nativeId);
		ArrayList<Registration> list = registrations.get(registration.callback);
		if (list != null) {
			list.remove(registration);
			if (list.isEmpty()) registrations.remove(registration.callback);
		}
		if (timedOut) registration.callback.automaticallyReleased = true;
		return true;
	}

	public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {
		registerNetworkCallback(request, callback, new Handler(android.os.Looper.getMainLooper()));
	}

	public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback, Handler handler) {
		register(request, callback, handler, 0);
	}

	public void requestNetwork(NetworkRequest request, NetworkCallback callback) {
		requestNetwork(request, callback, new Handler(android.os.Looper.getMainLooper()));
	}

	public void requestNetwork(NetworkRequest request, NetworkCallback callback, Handler handler) {
		register(request, callback, handler, 0);
	}

	public void requestNetwork(NetworkRequest request, NetworkCallback callback, int timeoutMs) {
		requestNetwork(request, callback, new Handler(android.os.Looper.getMainLooper()), timeoutMs);
	}

	public void requestNetwork(NetworkRequest request, NetworkCallback callback, Handler handler, int timeoutMs) {
		if (timeoutMs <= 0) throw new IllegalArgumentException("timeout must be positive");
		register(request, callback, handler, timeoutMs);
	}

	public synchronized void unregisterNetworkCallback(NetworkCallback callback) {
		if (callback == null) throw new NullPointerException();
		ArrayList<Registration> list = registrations.get(callback);
		if (list == null) {
			if (callback.automaticallyReleased) return;
			throw new IllegalArgumentException("Callback is not registered");
		}
		for (Registration registration : new ArrayList<>(list)) release(registration, false);
	}

	private native long nativeRegisterNetworkCallback(NetworkRequest request, NetworkCallback callback);
	private native void nativeUnregisterNetworkCallback(long registration);

	public native boolean isActiveNetworkMetered();

	protected native boolean nativeGetNetworkAvailable();

	public NetworkInfo[] getAllNetworkInfo() {
		return new NetworkInfo[] {getActiveNetworkInfo()};
	}

	public Network getActiveNetwork() {
		return nativeGetNetworkAvailable() ? HOST_NETWORK : null;
	}

	public Network[] getAllNetworks() {
		Network network = getActiveNetwork();
		return network == null ? new Network[0] : new Network[] {network};
	}

	private NetworkCapabilities hostCapabilities() {
		// One virtual host-default network. GNetworkMonitor reports availability
		// and metering, not the physical transport or validation status.
		NetworkCapabilities capabilities = new NetworkCapabilities();
		capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
		if (!isActiveNetworkMetered()) capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
		return capabilities;
	}

	public NetworkCapabilities getNetworkCapabilities(Network network) {
		return network == null || !nativeGetNetworkAvailable() ? null : hostCapabilities();
	}

	public void registerDefaultNetworkCallback(NetworkCallback callback, Handler handler) {
		registerNetworkCallback(new NetworkRequest.Builder().build(), callback, handler);
	}

	public void registerDefaultNetworkCallback(NetworkCallback callback) {
		registerDefaultNetworkCallback(callback, new Handler(android.os.Looper.getMainLooper()));
	}

	public ProxyInfo getDefaultProxy() { return null; }

	public LinkProperties getLinkProperties(Network network) { return null; }
}
