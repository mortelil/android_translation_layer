/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.nsd;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Log;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ATL android.net.nsd.NsdManager acceptance probe.
 *
 * Exercises, against a synthetic `_home-assistant._tcp` service advertised on
 * the host's avahi daemon (tests/nsd/nsd_fixture.c):
 *   A. discover + resolve: name, host, port and TXT attributes;
 *   B. operation generations: after stopServiceDiscovery + a fresh discovery, a
 *      late event carrying the OLD operation handle must NOT reach the live
 *      listener, while an event carrying the live handle MUST;
 *   C. a second discoverServices() with an already-active listener must throw
 *      IllegalArgumentException, and stopping an unregistered listener too;
 *   D. the MAX_DISCOVERIES limit reports FAILURE_MAX_LIMIT instead of starting;
 *   E. when the DNS-SD backend is unavailable the start FAILS
 *      (onStartDiscoveryFailed) and onDiscoveryStarted is never delivered.
 *
 * Callbacks are delivered on the app main looper, so all sequencing happens on
 * a worker thread; blocking the main thread here would deadlock the probe.
 *
 * Emits sentinel-wrapped JSON (NSDPROBE_JSON_BEGIN/_END), persists it to
 * files/results.json, then prints NSDPROBE_DONE.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "NSDPROBE";
    private static final String TASK_ID = "NSD-PROBE";
    private static final String SERVICE_TYPE = "_home-assistant._tcp";
    private static final String BEGIN = "NSDPROBE_JSON_BEGIN";
    private static final String END = "NSDPROBE_JSON_END";
    private static final String DONE = "NSDPROBE_DONE";

    private final String runId = "nsd-probe-" + Long.toHexString(System.currentTimeMillis());
    private final List<String> exceptions =
            Collections.synchronizedList(new ArrayList<String>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(new Runnable() {
            public void run() {
                String json;
                try {
                    json = runProbe();
                } catch (Throwable t) {
                    Log.e(TAG, "probe failed", t);
                    json = "{\"task_id\":" + js(TASK_ID) + ",\"run_id\":" + js(runId)
                            + ",\"status\":\"ERROR\",\"error\":"
                            + js(t.getClass().getName() + ": " + t.getMessage()) + "}";
                }
                System.out.println(BEGIN);
                System.out.println(json);
                System.out.println(END);
                writeResults(json);
                System.out.println(DONE);
            }
        }, "nsd-probe").start();
    }

    // ---- probe ---------------------------------------------------------------

    private String runProbe() throws Exception {
        NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        NsdManager nsd2 = getSystemService(NsdManager.class);

        // ---- A: discover + resolve -------------------------------------------
        Rec r1 = new Rec();
        CountDownLatch started1 = new CountDownLatch(1);
        RecListener l1 = new RecListener(r1, started1);
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l1);
        started1.await(20, TimeUnit.SECONDS);

        boolean foundOk = r1.found.await(30, TimeUnit.SECONDS);
        NsdServiceInfo resolved = null;
        boolean resolveOk = false;
        int resolveError = -1;
        String resolveName = null, resolveHost = null, resolveAttrError = null;
        int resolvePort = -1;
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        List<String> attrKeys = new ArrayList<String>();
        NsdServiceInfo info = foundOk ? l1.takeFoundInfo() : null;
        if (info != null) {
            ResolveRec rr = new ResolveRec();
            nsd.resolveService(info, rr.listener());
            rr.done.await(20, TimeUnit.SECONDS);
            resolveOk = rr.resolved;
            resolveError = rr.errorCode;
            if (rr.resolved) {
                resolved = rr.info;
                resolveName = rr.info.getServiceName();
                resolvePort = rr.info.getPort();
                InetAddress host = rr.info.getHost();
                resolveHost = (host == null) ? null : host.getHostAddress();
                for (Map.Entry<String, byte[]> e : rr.info.getAttributes().entrySet()) {
                    attrKeys.add(e.getKey());
                    attrs.put(e.getKey(), e.getValue() == null ? null
                            : new String(e.getValue(), StandardCharsets.UTF_8));
                }
            }
        }

        // Stress the backend callback/control overlap while the original browser
        // remains live. Resolve each result and restart a separate browser.
        if (info != null) {
            java.lang.reflect.Field lf = NsdManager.class.getDeclaredField("sLock");
            lf.setAccessible(true);
            ResolveRec duplicate = new ResolveRec();
            NsdManager.ResolveListener shared = duplicate.listener();
            synchronized (lf.get(null)) {
                nsd.resolveService(info, shared);
                try {nsd.resolveService(info, shared);throw new AssertionError("ResolveListener reuse accepted");}
                catch (IllegalArgumentException expected) {}
            }
            if (!duplicate.done.await(5,TimeUnit.SECONDS) || !duplicate.resolved)
                throw new AssertionError("original resolve damaged by duplicate rejection");
            for (int round = 0; round < 20; round++) {
                Rec stress = new Rec();
                RecListener sl = new RecListener(stress, new CountDownLatch(1));
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, sl);
                ResolveRec rr = new ResolveRec();
                nsd.resolveService(info, rr.listener());
                if (!rr.done.await(5, TimeUnit.SECONDS) || !rr.resolved)
                    throw new AssertionError("concurrent NSD resolve failed at " + round);
                nsd.stopServiceDiscovery(sl);
            }
        }

        // ---- B: operation generations ----------------------------------------
        Long oldHandle = handleFor(nsd, l1);
        CountDownLatch stopped1 = new CountDownLatch(1);
        l1.stopped = stopped1;
        nsd.stopServiceDiscovery(l1);
        stopped1.await(10, TimeUnit.SECONDS);

        Rec r2 = new Rec();
        CountDownLatch started2 = new CountDownLatch(1);
        RecListener l2 = new RecListener(r2, started2);
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l2);
        started2.await(20, TimeUnit.SECONDS);
        Long newHandle = handleFor(nsd, l2);

        boolean staleDelivered = false, liveDelivered = false;
        if (oldHandle != null && newHandle != null) {
            injectServiceFound(oldHandle.longValue(), "STALE-MUST-NOT-ARRIVE");
            Thread.sleep(3000);
            staleDelivered = r2.hasFound("STALE-MUST-NOT-ARRIVE");
            injectServiceFound(newHandle.longValue(), "LIVE-INJECTED");
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !r2.hasFound("LIVE-INJECTED"))
                Thread.sleep(100);
            liveDelivered = r2.hasFound("LIVE-INJECTED");
        }

        // ---- C: listener reuse ----------------------------------------------
        boolean reuseThrew = false;
        String reuseException = null;
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l2);
        } catch (IllegalArgumentException e) {
            reuseThrew = true;
            reuseException = e.getClass().getName() + ": " + e.getMessage();
        }

        Rec rUnused = new Rec();
        boolean stopUnregisteredThrew = false;
        try {
            nsd.stopServiceDiscovery(new RecListener(rUnused, new CountDownLatch(1)));
        } catch (IllegalArgumentException e) {
            stopUnregisteredThrew = true;
        }

        // ---- D: max discoveries ---------------------------------------------
        Rec[] extra = new Rec[3];
        RecListener[] extraL = new RecListener[3];
        CountDownLatch[] extraStarted = new CountDownLatch[3];
        for (int i = 0; i < 3; i++) {
            extra[i] = new Rec();
            extraStarted[i] = new CountDownLatch(1);
            extraL[i] = new RecListener(extra[i], extraStarted[i]);
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, extraL[i]);
            extraStarted[i].await(20, TimeUnit.SECONDS);
        }
        Rec rOver = new Rec();
        CountDownLatch overStarted = new CountDownLatch(1);
        RecListener overL = new RecListener(rOver, overStarted);
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, overL);
        overStarted.await(10, TimeUnit.SECONDS);

        // ---- E: cleanup ------------------------------------------------------
        List<String> stoppedTypes = new ArrayList<String>();
        for (int i = 0; i < 3; i++)
            extraL[i].stopped = new CountDownLatch(1);
        l2.stopped = new CountDownLatch(1);
        nsd.stopServiceDiscovery(l2);
        l2.stopped.await(10, TimeUnit.SECONDS);
        stoppedTypes.add(l2.rec.serviceType == null ? "" : l2.rec.serviceType);
        for (int i = 0; i < 3; i++) {
            nsd.stopServiceDiscovery(extraL[i]);
            extraL[i].stopped.await(10, TimeUnit.SECONDS);
        }

        // ---- JSON ------------------------------------------------------------
        StringBuilder sb = new StringBuilder();
        sb.append("{\"task_id\":").append(js(TASK_ID));
        sb.append(",\"run_id\":").append(js(runId));
        sb.append(",\"status\":\"").append(exceptions.isEmpty() ? "OK" : "ERROR").append('"');
        sb.append(",\"exceptions\":[");
        for (int i = 0; i < exceptions.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(js(exceptions.get(i)));
        }
        sb.append(']');

        sb.append(",\"context\":{\"serviceName\":").append(js(Context.NSD_SERVICE));
        sb.append(",\"byClassSameInstance\":").append(nsd == nsd2).append('}');

        sb.append(",\"discovery\":{");
        sb.append("\"startFailed\":").append(r1.startFailed);
        sb.append(",\"startErrorCode\":").append(r1.startErrorCode);
        sb.append(",\"started\":").append(r1.started);
        sb.append(",\"found\":[").append(jsList(r1.foundNames())).append(']');
        sb.append('}');

        sb.append(",\"resolve\":{\"ok\":").append(resolveOk);
        sb.append(",\"errorCode\":").append(resolveError);
        sb.append(",\"name\":").append(js(resolveName));
        sb.append(",\"host\":").append(js(resolveHost));
        sb.append(",\"port\":").append(resolvePort);
        sb.append(",\"attributeKeys\":[").append(jsList(attrKeys)).append(']');
        sb.append(",\"attributes\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(js(e.getKey())).append(':').append(js(e.getValue()));
        }
        sb.append("}}");

        sb.append(",\"generations\":{\"oldHandle\":").append(oldHandle);
        sb.append(",\"newHandle\":").append(newHandle);
        sb.append(",\"staleDelivered\":").append(staleDelivered);
        sb.append(",\"liveDelivered\":").append(liveDelivered);
        sb.append('}');

        sb.append(",\"listenerReuse\":{\"threw\":").append(reuseThrew);
        sb.append(",\"exception\":").append(js(reuseException));
        sb.append(",\"stopUnregisteredThrew\":").append(stopUnregisteredThrew);
        sb.append('}');

        sb.append(",\"maxLimit\":{\"extraStarted\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(',');
            sb.append(extra[i].started);
        }
        sb.append("],\"limitStartFailed\":").append(rOver.startFailed);
        sb.append(",\"limitErrorCode\":").append(rOver.startErrorCode);
        sb.append(",\"limitStartedDespiteCap\":").append(rOver.started);
        sb.append('}');

        sb.append(",\"cleanup\":{\"stopped\":").append(stoppedTypes.size());
        sb.append(",\"finalActive\":").append(activeCount(nsd));
        sb.append("}}");
        return sb.toString();
    }

    // ---- listeners -----------------------------------------------------------

    private static final class Rec {
        boolean startFailed, started;
        volatile String serviceType;
        int startErrorCode = -1;
        final CountDownLatch found = new CountDownLatch(1);
        final List<String> foundNames = Collections.synchronizedList(new ArrayList<String>());

        boolean hasFound(String name) {
            synchronized (foundNames) {
                return foundNames.contains(name);
            }
        }

        List<String> foundNames() {
            synchronized (foundNames) {
                return new ArrayList<String>(foundNames);
            }
        }

    }

    private final class RecListener implements NsdManager.DiscoveryListener {
        final Rec rec;
        final CountDownLatch startedLatch;
        volatile CountDownLatch stopped;
        final List<NsdServiceInfo> foundInfos =
                Collections.synchronizedList(new ArrayList<NsdServiceInfo>());

        RecListener(Rec rec, CountDownLatch startedLatch) {
            this.rec = rec;
            this.startedLatch = startedLatch;
        }

        public void onStartDiscoveryFailed(String type, int errorCode) {
            rec.startFailed = true;
            rec.startErrorCode = errorCode;
            rec.started = false;
            startedLatch.countDown();
        }

        public void onStopDiscoveryFailed(String type, int errorCode) {
            exceptions.add("onStopDiscoveryFailed(" + type + ", " + errorCode + ")");
            if (stopped != null) stopped.countDown();
        }

        public void onDiscoveryStarted(String type) {
            rec.started = true;
            rec.serviceType = type;
            startedLatch.countDown();
        }

        public void onDiscoveryStopped(String type) {
            if (stopped != null) stopped.countDown();
        }

        public void onServiceFound(NsdServiceInfo info) {
            rec.foundNames.add(info.getServiceName());
            foundInfos.add(info);
            rec.found.countDown();
        }

        public void onServiceLost(NsdServiceInfo info) {
        }

        NsdServiceInfo takeFoundInfo() {
            synchronized (foundInfos) {
                return foundInfos.isEmpty() ? null : foundInfos.get(0);
            }
        }
    }

    private final class ResolveRec {
        volatile boolean resolved;
        volatile int errorCode = -1;
        volatile NsdServiceInfo info;
        final CountDownLatch done = new CountDownLatch(1);

        NsdManager.ResolveListener listener() {
            return new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo si, int code) {
                    errorCode = code;
                    done.countDown();
                }

                public void onServiceResolved(NsdServiceInfo si) {
                    resolved = true;
                    info = si;
                    done.countDown();
                }
            };
        }
    }

    // ---- reflection helpers --------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<Long, Object> discoveries(NsdManager nsd) throws Exception {
        Field f = NsdManager.class.getDeclaredField("sDiscoveries");
        f.setAccessible(true);
        return (Map<Long, Object>) f.get(null);
    }

    private static Long handleFor(NsdManager nsd, NsdManager.DiscoveryListener listener)
            throws Exception {
        Map<Long, Object> map = discoveries(nsd);
        synchronized (map) {
            for (Map.Entry<Long, Object> e : map.entrySet()) {
                Field lf = e.getValue().getClass().getDeclaredField("listener");
                lf.setAccessible(true);
                if (lf.get(e.getValue()) == listener)
                    return e.getKey();
            }
        }
        return null;
    }

    private static int activeCount(NsdManager nsd) throws Exception {
        return discoveries(nsd).size();
    }

    private static void injectServiceFound(long handle, String name) throws Exception {
        Method m = NsdManager.class.getDeclaredMethod("onNativeServiceFound", long.class,
                String.class, String.class, String.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(null, handle, name, SERVICE_TYPE, "local", 0, 0);
    }

    // ---- io ------------------------------------------------------------------

    private void writeResults(String json) {
        try {
            FileOutputStream out = new FileOutputStream(
                    new java.io.File(getFilesDir(), "results.json"));
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            } finally {
                out.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot write results.json", e);
        }
    }

    private static String js(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String jsList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(js(list.get(i)));
        }
        return sb.toString();
    }
}
