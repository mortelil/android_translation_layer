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

import java.net.InetAddress;
import java.util.HashMap;
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

    /**
     * Listener for service registration events. Mirrors AOSP
     * NsdManager.RegistrationListener (frameworks/base
     * core/java/android/net/nsd/NsdManager.java, tag android-12.0.0_r1:350-359).
     */
    public interface RegistrationListener {
        void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceRegistered(NsdServiceInfo serviceInfo);
        void onServiceUnregistered(NsdServiceInfo serviceInfo);
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
        boolean started;
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

    private static final class RegistrationOp {
        final long handle;
        final RegistrationListener listener;
        final NsdServiceInfo info;
        RegistrationOp(long handle, RegistrationListener listener, NsdServiceInfo info) {
            this.handle = handle;
            this.listener = listener;
            this.info = info;
        }
    }

    private static final Object sLock = new Object();
    private static final AtomicLong sNextHandle = new AtomicLong(1);
    private static final Map<Long, DiscoveryOp> sDiscoveries = new HashMap<Long, DiscoveryOp>();
    private static final Map<Long, ResolveOp> sResolves = new HashMap<Long, ResolveOp>();
    private static final Map<Long, RegistrationOp> sRegistrations = new HashMap<Long, RegistrationOp>();
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
        if (h == null || !h.post(r))
            throw new IllegalStateException("NSD requires a running main looper");
    }

    /**
     * Start discovering services of the given DNS-SD type (e.g.
     * "_ipp._tcp"). The callbacks are delivered on the application main thread.
     */
    public void discoverServices(String serviceType, int protocol, DiscoveryListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (serviceType == null || serviceType.isEmpty()) {
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
        runOnMain(new Runnable() { public void run() {
            DiscoveryOp op;
            synchronized (sLock) { op = sDiscoveries.get(handle); }
            if (op != null) notifyStarted(op);
        }});
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
            nativeStopDiscovery(op.handle);
        }
        postDiscoveryStopped(op.listener, op.serviceType);
    }

    /**
     * Register a service to be announced on the local network. The service is
     * published through the host's DNS-SD stack; {@code listener} is notified
     * when the announcement is visible on the network and is the handle used
     * by {@link #unregisterService}.
     */
    public void registerService(NsdServiceInfo serviceInfo, int protocol, RegistrationListener listener) {
        if (serviceInfo == null) {
            throw new IllegalArgumentException("serviceInfo cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (serviceInfo.getPort() <= 0 || serviceInfo.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid port number");
        }
        if (serviceInfo.getServiceName() == null || serviceInfo.getServiceName().isEmpty()) {
            throw new IllegalArgumentException("serviceInfo must have a service name");
        }
        if (serviceInfo.getServiceType() == null || serviceInfo.getServiceType().isEmpty()) {
            throw new IllegalArgumentException("serviceInfo must have a service type");
        }
        if (protocol != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol " + protocol);
        }

        // Own the registration snapshot: caller mutations must not change callbacks.
        NsdServiceInfo snapshot = new NsdServiceInfo();
        snapshot.setServiceName(serviceInfo.getServiceName());
        snapshot.setServiceType(serviceInfo.getServiceType());
        snapshot.setPort(serviceInfo.getPort());
        for (Map.Entry<String, byte[]> e : serviceInfo.getAttributes().entrySet())
            snapshot.setAttribute(e.getKey(), e.getValue() == null ? null : e.getValue().clone());
        serviceInfo = snapshot;
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        String[] keys = new String[attributes.size()];
        byte[][] values = new byte[attributes.size()][];
        int i = 0;
        for (Map.Entry<String, byte[]> e : attributes.entrySet()) {
            keys[i] = e.getKey();
            values[i] = e.getValue();
            i++;
        }

        final long handle;
        synchronized (sLock) {
            for (RegistrationOp op : sRegistrations.values()) {
                if (op.listener == listener) {
                    throw new IllegalArgumentException("listener already in use");
                }
            }
            handle = sNextHandle.getAndIncrement();
            if (!nativeIsAvailable()
                    || !nativeRegisterService(handle, serviceInfo.getServiceName(),
                            serviceInfo.getServiceType(), serviceInfo.getPort(), keys, values)) {
                postRegistrationFailed(listener, serviceInfo, FAILURE_INTERNAL_ERROR);
                return;
            }
            sRegistrations.put(handle, new RegistrationOp(handle, listener, serviceInfo));
        }
    }

    /**
     * Unregister a service registered through {@link #registerService}. The
     * listener identifies the registration and is notified through
     * {@link RegistrationListener#onServiceUnregistered}.
     */
    public void unregisterService(RegistrationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        final RegistrationOp op;
        synchronized (sLock) {
            op = findRegistrationLocked(listener);
            if (op == null) {
                throw new IllegalArgumentException("listener not registered");
            }
            sRegistrations.remove(op.handle);
            nativeUnregisterService(op.handle);
        }
        postServiceUnregistered(op.listener, op.info);
    }

    /** Resolve a service found via {@link #discoverServices}. */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        if (serviceInfo == null) {
            throw new IllegalArgumentException("serviceInfo cannot be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (serviceInfo.getServiceName() == null || serviceInfo.getServiceName().isEmpty()) {
            throw new IllegalArgumentException("serviceInfo must have a service name");
        }
        if (serviceInfo.getServiceType() == null || serviceInfo.getServiceType().isEmpty())
            throw new IllegalArgumentException("serviceInfo must have a service type");
        final long handle;
        synchronized (sLock) {
            for (ResolveOp op : sResolves.values()) {
                if (op.listener == listener) throw new IllegalArgumentException("listener already in use");
            }
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

    private static RegistrationOp findRegistrationLocked(RegistrationListener listener) {
        for (RegistrationOp op : sRegistrations.values()) {
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

    private static void notifyStarted(DiscoveryOp op) {
        if (!op.started) {
            op.started = true;
            op.listener.onDiscoveryStarted(op.serviceType);
        }
    }

    private static void postDiscoveryStopped(final DiscoveryListener l, final String type) {
        runOnMain(new Runnable() { public void run() { l.onDiscoveryStopped(type); } });
    }

    private static void postResolveFailed(final ResolveListener l, final NsdServiceInfo info, final int code) {
        runOnMain(new Runnable() { public void run() { l.onResolveFailed(info, code); } });
    }

    private static void postRegistrationFailed(final RegistrationListener l, final NsdServiceInfo info, final int code) {
        runOnMain(new Runnable() { public void run() { l.onRegistrationFailed(info, code); } });
    }

    private static void postServiceUnregistered(final RegistrationListener l, final NsdServiceInfo info) {
        runOnMain(new Runnable() { public void run() { l.onServiceUnregistered(info); } });
    }

    // ---- native -> Java callbacks (invoked on the backend poll thread) ------

    private static void onNativeServiceFound(final long handle, final String name,
            final String type, final String domain, final int iface, final int proto) {
        runOnMain(new Runnable() { public void run() {
            DiscoveryOp op;
            synchronized (sLock) { op = sDiscoveries.get(handle); }
            if (op == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(op.serviceType);
            info.mNativeIface = iface;
            info.mNativeProto = proto;
            info.mNativeDomain = domain;
            notifyStarted(op);
            // The start callback may itself stop discovery.
            synchronized (sLock) { if (sDiscoveries.get(handle) != op) return; }
            op.listener.onServiceFound(info);
        }});
    }

    private static void onNativeServiceLost(final long handle, final String name, final String type) {
        runOnMain(new Runnable() { public void run() {
            DiscoveryOp op;
            synchronized (sLock) { op = sDiscoveries.get(handle); }
            if (op == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(op.serviceType);
            notifyStarted(op);
            synchronized (sLock) { if (sDiscoveries.get(handle) != op) return; }
            op.listener.onServiceLost(info);
        }});
    }

    private static void onNativeResolveResult(final long handle, final byte[] address,
            final int port, final byte[] txt, final String hostname, final int error) {
        runOnMain(new Runnable() { public void run() {
            ResolveOp op;
            synchronized (sLock) { op = sResolves.remove(handle); }
            if (op == null) return;
            if (error != 0 || address == null) {
                op.listener.onResolveFailed(op.info, FAILURE_INTERNAL_ERROR);
                return;
            }
            try {
                op.info.setHost(InetAddress.getByAddress(address));
                op.info.setPort(port);
                if (txt != null) parseTxt(txt, op.info);
            } catch (java.net.UnknownHostException | IllegalArgumentException e) {
                op.listener.onResolveFailed(op.info, FAILURE_INTERNAL_ERROR);
                return;
            }
            op.listener.onServiceResolved(op.info);
        }});
    }

    // Called under the backend lock: only enqueue, never acquire sLock here.
    private static void onNativeOperationFailed(final long handle) {
        runOnMain(new Runnable() { public void run() {
            DiscoveryOp discovery;
            ResolveOp resolve;
            RegistrationOp registration;
            synchronized (sLock) {
                discovery = sDiscoveries.remove(handle);
                resolve = sResolves.remove(handle);
                registration = sRegistrations.remove(handle);
            }
            if (discovery != null) discovery.listener.onDiscoveryStopped(discovery.serviceType);
            if (resolve != null) resolve.listener.onResolveFailed(resolve.info, FAILURE_INTERNAL_ERROR);
            if (registration != null) {
                nativeUnregisterService(registration.handle);
                registration.listener.onRegistrationFailed(registration.info, FAILURE_INTERNAL_ERROR);
            }
        }});
    }

    /**
     * The service is now announced under the network-visible name {@code name}
     * (the backend renames the instance when the requested name is taken, so
     * the registered NsdServiceInfo is updated before the callback).
     */
    private static void onNativeServiceRegistered(final long handle, final String name) {
        runOnMain(new Runnable() { public void run() {
            RegistrationOp op;
            synchronized (sLock) { op = sRegistrations.get(handle); }
            if (op == null) return;
            if (name != null && !name.isEmpty()) op.info.setServiceName(name);
            op.listener.onServiceRegistered(op.info);
        }});
    }

    private static void onNativeRegistrationFailed(final long handle) {
        runOnMain(new Runnable() { public void run() {
            RegistrationOp op;
            synchronized (sLock) { op = sRegistrations.remove(handle); }
            if (op == null) return;
            nativeUnregisterService(op.handle);
            op.listener.onRegistrationFailed(op.info, FAILURE_INTERNAL_ERROR);
        }});
    }

    /** DNS-SD TXT wire records: one unsigned length byte followed by raw bytes. */
    private static void parseTxt(byte[] txt, NsdServiceInfo info) {
        for (int pos = 0; pos < txt.length;) {
            int size = txt[pos++] & 0xff;
            if (size > txt.length - pos) throw new IllegalArgumentException("Truncated TXT record");
            int end = pos + size;
            int eq = pos;
            while (eq < end && txt[eq] != '=') eq++;
            if (eq > pos) {
                String key = new String(txt, pos, eq - pos, java.nio.charset.StandardCharsets.US_ASCII);
                byte[] value = eq == end ? null : java.util.Arrays.copyOfRange(txt, eq + 1, end);
                info.setAttribute(key, value);
            }
            pos = end;
        }
    }

    // ---- native backend ----------------------------------------------------

    private static native boolean nativeIsAvailable();
    private static native boolean nativeStartDiscovery(long handle, String serviceType);
    private static native void nativeStopDiscovery(long handle);
    private static native boolean nativeResolve(long handle, String name, String type,
            String domain, int iface, int proto);
    private static native boolean nativeRegisterService(long handle, String name, String type,
            int port, String[] keys, byte[][] values);
    private static native void nativeUnregisterService(long handle);
}
