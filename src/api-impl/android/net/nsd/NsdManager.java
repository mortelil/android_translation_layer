/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.nsd;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network Service Discovery (DNS-SD / mDNS) manager.
 *
 * ATL implementation note: the public surface and callback semantics mirror
 * android.net.nsd.NsdManager from AOSP (frameworks/base, tag android-17.0.0_r1);
 * see also the framework API documentation for NsdManager. The backend is the
 * host's DNS-SD stack (avahi via libavahi-client) reached from inside the ATL
 * runtime; the service type is never hard-coded.
 *
 * Operation generations: every discoverServices()/resolveService() call is
 * tagged with a unique 64-bit handle. Any asynchronous event that arrives for a
 * handle the manager no longer tracks (stopped discovery, cancelled or
 * completed resolve) is dropped, so callbacks from an old discovery can never
 * leak into a stopped or restarted one.
 */
public class NsdManager {
    private static final String TAG = "NsdManager";

    /** DNS-SD protocol. */
    public static final int PROTOCOL_DNS_SD = 0x0001;

    /** Failures are reported to the listener with one of these codes. */
    public static final int FAILURE_INTERNAL_ERROR = 0;
    public static final int FAILURE_ALREADY_ACTIVE = 3;
    public static final int FAILURE_MAX_LIMIT = 4;

    private static final int MAX_DISCOVERIES = 4;

    /** Listener for service discovery events. */
    public interface DiscoveryListener {
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
    }

    /** Listener for service resolution events. */
    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    private static final class DiscoveryOp {
        final long handle;
        final DiscoveryListener listener;
        final String serviceType;
        DiscoveryOp(long handle, DiscoveryListener listener, String serviceType) {
            this.handle = handle;
            this.listener = listener;
            this.serviceType = serviceType;
        }
    }

    private static final class ResolveOp {
        final long handle;
        final ResolveListener listener;
        final NsdServiceInfo info;
        ResolveOp(long handle, ResolveListener listener, NsdServiceInfo info) {
            this.handle = handle;
            this.listener = listener;
            this.info = info;
        }
    }

    private static final Object sLock = new Object();
    private static final AtomicLong sNextHandle = new AtomicLong(1);
    private static final Map<Long, DiscoveryOp> sDiscoveries = new HashMap<Long, DiscoveryOp>();
    private static final Map<Long, ResolveOp> sResolves = new HashMap<Long, ResolveOp>();
    private static volatile Handler sHandler;

    private static Handler handler() {
        Handler h = sHandler;
        if (h == null) {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                h = new Handler(looper);
                sHandler = h;
            }
        }
        return h;
    }

    private static void runOnMain(Runnable r) {
        Handler h = handler();
        if (h != null && Looper.myLooper() != Looper.getMainLooper()) {
            h.post(r);
        } else {
            r.run();
        }
    }

    /**
     * Start discovering services of the given DNS-SD type (e.g.
     * "_ipp._tcp"). The callbacks are delivered on the application main thread.
     */
    public void discoverServices(String serviceType, int protocol, DiscoveryListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (serviceType == null) {
            throw new IllegalArgumentException("serviceType cannot be null");
        }
        if (protocol != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol " + protocol);
        }

        final long handle;
        synchronized (sLock) {
            for (DiscoveryOp op : sDiscoveries.values()) {
                if (op.listener == listener) {
                    throw new IllegalArgumentException("listener already in use");
                }
            }
            if (sDiscoveries.size() >= MAX_DISCOVERIES) {
                postStartFailed(listener, serviceType, FAILURE_MAX_LIMIT);
                return;
            }
            handle = sNextHandle.getAndIncrement();
            if (!nativeIsAvailable() || !nativeStartDiscovery(handle, serviceType)) {
                // Backend missing/unreachable: fail explicitly, never start
                // silently and then go quiet.
                postStartFailed(listener, serviceType, FAILURE_INTERNAL_ERROR);
                return;
            }
            sDiscoveries.put(handle, new DiscoveryOp(handle, listener, serviceType));
        }
        postDiscoveryStarted(listener, serviceType);
    }

    /**
     * Stop service discovery for a listener previously passed to
     * {@link #discoverServices}.
     */
    public void stopServiceDiscovery(DiscoveryListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        final DiscoveryOp op;
        synchronized (sLock) {
            op = findDiscoveryLocked(listener);
            if (op == null) {
                throw new IllegalArgumentException("listener not registered");
            }
            sDiscoveries.remove(op.handle);
        }
        nativeStopDiscovery(op.handle);
        postDiscoveryStopped(op.listener, op.serviceType);
    }

    /** Resolve a service found via {@link #discoverServices}. */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        if (serviceInfo == null) {
            throw new IllegalArgumentException("serviceInfo cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (serviceInfo.getServiceName() == null) {
            throw new IllegalArgumentException("serviceInfo must have a service name");
        }
        final long handle;
        synchronized (sLock) {
            handle = sNextHandle.getAndIncrement();
            if (!nativeIsAvailable()
                    || !nativeResolve(handle, serviceInfo.getServiceName(),
                            serviceInfo.getServiceType(), serviceInfo.mNativeDomain,
                            serviceInfo.mNativeIface, serviceInfo.mNativeProto)) {
                postResolveFailed(listener, serviceInfo, FAILURE_INTERNAL_ERROR);
                return;
            }
            sResolves.put(handle, new ResolveOp(handle, listener, serviceInfo));
        }
    }

    private static DiscoveryOp findDiscoveryLocked(DiscoveryListener listener) {
        for (DiscoveryOp op : sDiscoveries.values()) {
            if (op.listener == listener) {
                return op;
            }
        }
        return null;
    }

    // ---- callback dispatch helpers -----------------------------------------

    private static void postStartFailed(final DiscoveryListener l, final String type, final int code) {
        runOnMain(new Runnable() { public void run() { l.onStartDiscoveryFailed(type, code); } });
    }

    private static void postDiscoveryStarted(final DiscoveryListener l, final String type) {
        runOnMain(new Runnable() { public void run() { l.onDiscoveryStarted(type); } });
    }

    private static void postDiscoveryStopped(final DiscoveryListener l, final String type) {
        runOnMain(new Runnable() { public void run() { l.onDiscoveryStopped(type); } });
    }

    private static void postResolveFailed(final ResolveListener l, final NsdServiceInfo info, final int code) {
        runOnMain(new Runnable() { public void run() { l.onResolveFailed(info, code); } });
    }

    // ---- native -> Java callbacks (invoked on the backend poll thread) ------

    private static void onNativeServiceFound(final long handle, final String name,
            final String type, final String domain, final int iface, final int proto) {
        final DiscoveryOp op;
        synchronized (sLock) {
            op = sDiscoveries.get(handle);
        }
        if (op == null) {
            return; // stale generation: discovery stopped or restarted
        }
        final NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(op.serviceType != null ? op.serviceType : type);
        info.mNativeIface = iface;
        info.mNativeProto = proto;
        info.mNativeDomain = domain;
        runOnMain(new Runnable() { public void run() {
            // Re-check under lock: the discovery may have been stopped between
            // the native event and this main-thread delivery.
            synchronized (sLock) {
                if (sDiscoveries.get(handle) != op) {
                    return;
                }
            }
            op.listener.onServiceFound(info);
        }});
    }

    private static void onNativeServiceLost(final long handle, final String name, final String type) {
        final DiscoveryOp op;
        synchronized (sLock) {
            op = sDiscoveries.get(handle);
        }
        if (op == null) {
            return;
        }
        final NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(op.serviceType != null ? op.serviceType : type);
        runOnMain(new Runnable() { public void run() {
            synchronized (sLock) {
                if (sDiscoveries.get(handle) != op) {
                    return;
                }
            }
            op.listener.onServiceLost(info);
        }});
    }

    private static void onNativeResolveResult(final long handle, final byte[] address,
            final int port, final byte[] txt, final String hostname, final int error) {
        final ResolveOp op;
        synchronized (sLock) {
            op = sResolves.remove(handle);
        }
        if (op == null) {
            return; // cancelled or already completed
        }
        if (error != 0) {
            postResolveFailed(op.listener, op.info, error);
            return;
        }
        if (address != null) {
            try {
                op.info.setHost(InetAddress.getByAddress(address));
            } catch (Exception e) {
                Log.w(TAG, "could not build InetAddress from resolved address", e);
            }
        }
        op.info.setPort(port);
        if (txt != null) {
            parseTxt(txt, op.info);
        }
        runOnMain(new Runnable() { public void run() { op.listener.onServiceResolved(op.info); } });
    }

    /**
     * The backend went away (daemon died / disconnected). Android defines no
     * callback for this, so terminate every active operation explicitly rather
     * than leaving the application waiting forever: discoveries report
     * onDiscoveryStopped and pending resolves report onResolveFailed.
     */
    private static void onNativeBackendFailed() {
        final List<DiscoveryOp> discoveries = new ArrayList<DiscoveryOp>();
        final List<ResolveOp> resolves = new ArrayList<ResolveOp>();
        synchronized (sLock) {
            discoveries.addAll(sDiscoveries.values());
            resolves.addAll(sResolves.values());
            sDiscoveries.clear();
            sResolves.clear();
        }
        for (final DiscoveryOp op : discoveries) {
            postDiscoveryStopped(op.listener, op.serviceType);
        }
        for (final ResolveOp op : resolves) {
            postResolveFailed(op.listener, op.info, FAILURE_INTERNAL_ERROR);
        }
    }

    /** Split the native null-terminated "key=value" TXT blob into attributes. */
    private static void parseTxt(byte[] txt, NsdServiceInfo info) {
        int start = 0;
        for (int i = 0; i <= txt.length; i++) {
            if (i == txt.length || txt[i] == 0) {
                if (i > start) {
                    int eq = -1;
                    for (int j = start; j < i; j++) {
                        if (txt[j] == '=') { eq = j; break; }
                    }
                    String key;
                    byte[] value;
                    if (eq >= 0) {
                        key = new String(txt, start, eq - start, java.nio.charset.StandardCharsets.UTF_8);
                        value = new byte[i - eq - 1];
                        System.arraycopy(txt, eq + 1, value, 0, value.length);
                    } else {
                        key = new String(txt, start, i - start, java.nio.charset.StandardCharsets.UTF_8);
                        value = new byte[0];
                    }
                    info.setAttribute(key, value);
                }
                start = i + 1;
            }
        }
    }

    // ---- native backend ----------------------------------------------------

    private static native boolean nativeIsAvailable();
    private static native boolean nativeStartDiscovery(long handle, String serviceType);
    private static native void nativeStopDiscovery(long handle);
    private static native boolean nativeResolve(long handle, String name, String type,
            String domain, int iface, int proto);
}
