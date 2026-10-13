/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.networkrequests;

import android.net.*;
import android.os.*;
import java.util.*;

public class Probe extends android.app.Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FakeMonitor manager = new FakeMonitor();
    private final ArrayList<String> events = new ArrayList<>();
    private ConnectivityManager.NetworkCallback source;
    private boolean failed;
    private final ArrayList<String> unmeteredEvents = new ArrayList<>();
    private ConnectivityManager.NetworkCallback unmeteredSource;
    private final ConnectivityManager.NetworkCallback unmetered = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network network) { unmeteredEvents.add("available"); }
        public void onLost(Network network) { unmeteredEvents.add("lost"); }
    };
    public static class FakeMonitor extends ConnectivityManager {
        boolean available = true, metered;
        protected boolean nativeGetNetworkAvailable() { return available; }
        public boolean isActiveNetworkMetered() { return metered; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private final ArrayDeque<Runnable> steps = new ArrayDeque<>();
    private boolean stepRunning;
    private void nextStep() {
        Runnable next = steps.poll();
        stepRunning = next != null;
        if (next != null && !failed) next.run();
    }
    private void later(final long delay, final Runnable action) {
        steps.add(new Runnable() { public void run() {
            handler.postDelayed(new Runnable() { public void run() {
                if (failed) return;
                try { action.run(); } catch (Throwable t) { failed = true; t.printStackTrace(); System.out.println("NETWORK_REQUESTS FAIL " + t); }
                nextStep();
            } }, delay);
        } });
        if (!stepRunning) nextStep();
    }
    private ConnectivityManager.NetworkCallback source(ConnectivityManager.NetworkCallback callback) {
        try {
            java.lang.reflect.Field field = ConnectivityManager.class.getDeclaredField("registrations");
            field.setAccessible(true);
            return (ConnectivityManager.NetworkCallback)((ArrayList<?>)((Map<?,?>)field.get(manager)).get(callback)).get(0);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        private void add(String s) { check(Looper.myLooper() == handler.getLooper(), "requested handler"); events.add(s); }
        public void onAvailable(Network network) { add("available"); }
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            check(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "no invented wifi");
            add(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? "unmetered" : "metered");
            // Caller mutation must not corrupt the registration's remembered state.
            capabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        public void onLost(Network network) { add("lost"); }
        public void onUnavailable() { add("unavailable"); }
    };
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        final NetworkRequest request = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        manager.requestNetwork(request, callback, handler, 700);
        source = source(callback);
        manager.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).build(), unmetered, handler);
        unmeteredSource = source(unmetered);
        later(100, new Runnable() { public void run() {
            check(events.equals(Arrays.asList("available", "unmetered")), "initial callback order " + events);
            source.onAvailable(new Network()); source.onAvailable(new Network());
        } });
        later(200, new Runnable() { public void run() {
            check(events.size() == 2, "duplicates suppressed and callback copy independent");
            manager.metered = true; source.onAvailable(new Network()); unmeteredSource.onAvailable(new Network());
        } });
        later(300, new Runnable() { public void run() {
            check(events.equals(Arrays.asList("available", "unmetered", "metered")), "metering callback " + events);
            check(unmeteredEvents.equals(Arrays.asList("available", "lost")), "capability loss must notify previous match");
            manager.available = false; source.onLost(new Network()); source.onLost(new Network());
            check(manager.getActiveNetwork() == null && manager.getAllNetworks().length == 0, "offline snapshot");
        } });
        later(400, new Runnable() { public void run() {
            check(events.equals(Arrays.asList("available", "unmetered", "metered", "lost")), "loss once " + events);
            // Do not reconnect before the initial request deadline. A request
            // fulfilled once must not become onUnavailable after later loss.
        } });
        later(850, new Runnable() { public void run() {
            check(!events.contains("unavailable"), "fulfilled request never times out after loss");
            manager.available = true; source.onAvailable(new Network());
        } });
        later(950, new Runnable() { public void run() {
            check(events.equals(Arrays.asList("available", "unmetered", "metered", "lost", "available", "metered")), "reconnect " + events);
            manager.unregisterNetworkCallback(callback);
            manager.unregisterNetworkCallback(unmetered);
            source.onLost(new Network());
            final int[] reentrant = {0};
            ConnectivityManager.NetworkCallback self = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network network) { reentrant[0]++; manager.unregisterNetworkCallback(this); }
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { reentrant[0] += 100; }
            };
            manager.registerDefaultNetworkCallback(self, handler);
            ConnectivityManager.NetworkCallback removed = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network network) { throw new AssertionError("callback after unregister"); }
            };
            manager.registerNetworkCallback(request, removed, handler);
            manager.unregisterNetworkCallback(removed);
            final ArrayList<String> timeout = new ArrayList<>();
            final ConnectivityManager.NetworkCallback unavailable = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network network) { timeout.add("incorrectly available"); }
                public void onUnavailable() { timeout.add("unavailable"); }
            };
            manager.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build(), unavailable, handler, 100);
            later(250, new Runnable() { public void run() {
                check(events.size() == 6, "queued callback canceled");
                check(reentrant[0] == 1, "reentrant unregister suppresses later capability callback");
                check(timeout.equals(Arrays.asList("unavailable")), "unsupported transport times out honestly");
                manager.unregisterNetworkCallback(unavailable);
                try {
                    getFilesDir().mkdirs();
                    java.io.OutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "results.json"));
                    out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));out.close();
                } catch (Exception e) { throw new AssertionError(e); }
                System.out.println("NETWORK_REQUESTS PASS");
            } });
        } });
    }
}
