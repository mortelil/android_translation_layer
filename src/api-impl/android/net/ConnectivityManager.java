package android.net;

import android.os.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;

class ProxyInfo {}

public class ConnectivityManager {
	private final IdentityHashMap<NetworkCallback, ArrayList<Long>> registrations = new IdentityHashMap<>();
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

	public static class NetworkCallback {
		public void onAvailable(Network network) {}
		public void onLost(Network network) {}
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

	public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) {
		registerNetworkCallback(request, callback, new Handler(android.os.Looper.getMainLooper()));
	}

	public synchronized void registerNetworkCallback(NetworkRequest request, final NetworkCallback callback, final Handler handler) {
		if (request == null || callback == null || handler == null)
			throw new NullPointerException();
		NetworkCallback dispatched = new NetworkCallback() {
			@Override
			public void onAvailable(final Network network) {
				handler.post(new Runnable() { public void run() { callback.onAvailable(network); } });
			}
			@Override
			public void onLost(final Network network) {
				handler.post(new Runnable() { public void run() { callback.onLost(network); } });
			}
		};
		long registration = nativeRegisterNetworkCallback(request, dispatched);
		ArrayList<Long> ids = registrations.get(callback);
		if (ids == null) {
			ids = new ArrayList<>();
			registrations.put(callback, ids);
		}
		ids.add(registration);
	}

	public synchronized void unregisterNetworkCallback(NetworkCallback callback) {
		ArrayList<Long> ids = registrations.remove(callback);
		if (ids == null)
			throw new IllegalArgumentException("Callback is not registered");
		for (Long id : ids)
			nativeUnregisterNetworkCallback(id);
	}

	private native long nativeRegisterNetworkCallback(NetworkRequest request, NetworkCallback callback);
	private native void nativeUnregisterNetworkCallback(long registration);

	public native boolean isActiveNetworkMetered();

	protected native boolean nativeGetNetworkAvailable();

	public NetworkInfo[] getAllNetworkInfo() {
		return new NetworkInfo[] {getActiveNetworkInfo()};
	}

	public Network getActiveNetwork() {
		return new Network();
	}

	public Network[] getAllNetworks() {
		return new Network[] {getActiveNetwork()};
	}

	public NetworkCapabilities getNetworkCapabilities(Network network) {
		// as in getActiveNetworkInfo, a null network means there is none to report on
		if (network == null || !nativeGetNetworkAvailable())
			return null;

		NetworkCapabilities capabilities = new NetworkCapabilities();
		capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

		if (!isActiveNetworkMetered())
			capabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

		capabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

		return capabilities;
	}

	public void registerDefaultNetworkCallback(NetworkCallback cb, Handler hdl) {}

	public void registerDefaultNetworkCallback(NetworkCallback cb) {}

	public ProxyInfo getDefaultProxy() { return null; }

	public LinkProperties getLinkProperties(Network network) { return null; }
}
