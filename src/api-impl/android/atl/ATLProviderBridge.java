// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.app.Application;
import android.content.ContentProvider;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.os.*;
import android.util.Slog;
import java.io.File;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Provider call and bounded query transport. Uses the existing private Binder channel,
 * never imports another app's provider code into the caller. Android contract:
 * AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
 * ContentProvider.Transport.call and ContentProviderClient.call.
 */
public final class ATLProviderBridge {
    public static final int ACQUIRE = 9, CALL = 10, QUERY = 11;
    private static final HashMap<String, Host> hosts = new HashMap<>();
    private ATLProviderBridge() {}

    public static boolean hasAuthority(ProviderInfo info, String authority) {
        if (authority == null || info.authority == null) return false;
        for (String name : info.authority.split(";")) if (authority.equals(name)) return true;
        return false;
    }

    /** Manifest lookup only; does not execute installed application code. */
    public static ProviderInfo resolve(String authority) {
        if (authority == null) throw new NullPointerException("authority");
        PackageParser.Package primary = ATLLoadedApp.getPrimaryApplication().pkg;
        for (PackageParser.Provider p : primary.providers)
            if (hasAuthority(p.info, authority) && p.info.enabled && primary.applicationInfo.enabled) return p.info;
        File[] files = ATLPaths.installed_apks_dir.listFiles();
        ProviderInfo found = null;
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(".apk")) continue;
            String pkgName = name.substring(0, name.length() - 4);
            if (primary.packageName.equals(pkgName)) continue;
            PackageParser.Package pkg = ATLLoadedAppManager.getPackageFromPackageName(pkgName);
            if (pkg == null || !pkg.applicationInfo.enabled) continue;
            for (PackageParser.Provider p : pkg.providers) {
                if (!hasAuthority(p.info, authority) || !p.info.enabled) continue;
                if (found != null && !found.packageName.equals(p.info.packageName))
                    throw new IllegalStateException("Ambiguous installed provider authority: " + authority);
                found = p.info;
            }
        }
        return found;
    }

    private static String process(ProviderInfo info) {
        String value = info.processName;
        if (value == null) value = info.applicationInfo.processName;
        return value == null ? info.packageName : value;
    }

    private static void enforce(ProviderInfo info, String caller) {
        if (!info.enabled || !info.applicationInfo.enabled) throw new SecurityException("Provider disabled");
        if (info.packageName.equals(caller)) return;
        if (!info.exported) throw new SecurityException("Provider is not exported: " + info.authority);
        // This first transport milestone does not grant permissions or URI grants.
        // In particular, never turn missing signature-permission support into access.
        if (info.readPermission != null || info.writePermission != null)
            throw new SecurityException("Permission-protected remote providers are not supported: " + info.authority);
    }

    public static Endpoint acquire(String authority) {
        ProviderInfo info = resolve(authority);
        if (info == null) return null;
        String caller = ATLLoadedApp.getPrimaryApplication().pkg.packageName;
        enforce(info, caller);
        String process = process(info);
        String key = info.packageName + "/" + process;
        synchronized (hosts) {
            Host host = hosts.get(key);
            try {
                if (host == null || host.dead) {
                    host = new Host(info, process);
                    hosts.put(key, host);
                }
                if (!host.acquire(authority)) {
                    if (host.references == 0) host.connection.close();
                    return null;
                }
                host.references++;
                return new Endpoint(host, authority);
            } catch (Exception e) {
                if (host != null && host.references == 0) host.connection.close();
                Slog.w("ATLProviderBridge", "Could not acquire " + authority, e);
                return null;
            }
        }
    }

    public static final class Endpoint {
        private final Host host;
        private final String authority;
        private boolean closed;
        Endpoint(Host host, String authority) { this.host = host; this.authority = authority; }
        // Hold this client across a call so close cannot terminate an in-flight operation.
        public synchronized Bundle call(String method, String arg, Bundle extras) throws RemoteException {
            if (closed) throw new IllegalStateException("ContentProviderClient is closed");
            if (method == null) throw new NullPointerException("method");
            if (host.dead) throw new RemoteException("Provider process died");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeString(authority); data.writeString(method); data.writeString(arg); data.writeBundle(extras);
                if (!host.connection.remote(1).transact(CALL, data, reply, 0))
                    throw new RemoteException("Provider call transaction failed");
                reply.readException();
                return reply.readBundle(ATLLoadedApp.getPrimaryApplication().class_loader);
            } catch (ReflectiveOperationException e) {
                throw new RemoteException("Cannot decode provider result: " + e);
            } finally { data.recycle(); reply.recycle(); }
        }
        public synchronized android.database.Cursor query(android.net.Uri uri, String[] projection,
                String selection, String[] args, String order) throws RemoteException {
            if (closed) throw new IllegalStateException("ContentProviderClient is closed");
            if (uri == null || !authority.equals(uri.getAuthority())) throw new IllegalArgumentException("Wrong provider authority");
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeString(authority); data.writeString(uri.toString()); data.writeStringArray(projection);
                data.writeString(selection); data.writeStringArray(args); data.writeString(order);
                if (!host.connection.remote(1).transact(QUERY, data, reply, 0)) throw new RemoteException("Provider query failed");
                reply.readException();
                int count = reply.readInt();
                if (count == -1) return null;
                String[] columns = reply.createStringArray();
                if (count < 0 || count > 4096 || columns == null || columns.length > 128)
                    throw new IllegalArgumentException("Invalid provider cursor size");
                android.database.MatrixCursor cursor = new android.database.MatrixCursor(columns, count);
                for (int row = 0; row < count; row++) {
                    Object[] values = new Object[columns.length];
                    for (int col = 0; col < columns.length; col++) values[col] = reply.readValue(null);
                    cursor.addRow(values);
                }
                return cursor;
            } catch (ReflectiveOperationException e) { throw new RemoteException("Cannot decode provider cursor: " + e); }
            finally { data.recycle(); reply.recycle(); }
        }
        public synchronized void close() {
            if (closed) return;
            closed = true;
            synchronized (hosts) {
                if (--host.references == 0) host.connection.close();
            }
        }
    }

    private static final class Host {
        final ATLBinderConnection connection;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile boolean dead;
        volatile boolean acknowledged;
        int references;
        Host(final ProviderInfo info, final String process) throws Exception {
            String apk = info.packageName.equals(ATLLoadedApp.getPrimaryApplication().pkg.packageName)
                ? ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.sourceDir
                : new File(ATLPaths.installed_apks_dir, info.packageName + ".apk").getAbsolutePath();
            final int[] spawned = ATLProcessHost.nativeSpawnServiceChild(apk, process,
                Build.VERSION.SDK_INT, ATLLoadedApp.getPrimaryApplication().pkg.packageName, Application.getProcessName());
            Thread reaper = new Thread(new Runnable() { public void run() { ATLBinderConnection.reap(spawned[1]); } }, "ATL-provider-reaper");
            reaper.setDaemon(true); reaper.start();
            connection = new ATLBinderConnection(spawned[0], spawned[1], new Binder() {
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    if (code != ATLProcessControl.READY) return false;
                    int pid = data.readInt(); String reported = data.readString(); data.readString();
                    if (Binder.getCallingPid() != spawned[1] || pid != spawned[1] || !process.equals(reported))
                        throw new SecurityException("Invalid provider host identity");
                    acknowledged = true; ready.countDown(); reply.writeNoException(); return true;
                }
            });
            connection.onDeath(new Runnable() { public void run() { dead = true; ready.countDown(); } });
            connection.start();
            try {
                if (!ready.await(30, TimeUnit.SECONDS) || !acknowledged || dead)
                    throw new IllegalStateException("Provider host did not become ready");
            } catch (Exception e) { connection.close(); throw e; }
        }
        boolean acquire(String authority) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeString(authority);
                if (!connection.remote(1).transact(ACQUIRE, data, reply, 0)) return false;
                reply.readException(); return reply.readInt() != 0;
            } finally { data.recycle(); reply.recycle(); }
        }
    }

    /** Runs on a Binder worker in the provider's own process, preserving caller pid. */
    static boolean dispatch(ATLLoadedApp app, String ownProcess, int code, Parcel data, Parcel reply) {
        String caller = System.getenv("ATL_SERVICE_PARENT_PACKAGE");
        int parentPid = Integer.parseInt(System.getenv("ATL_SERVICE_PARENT_PID"));
        if (Binder.getCallingPid() != parentPid || caller == null) throw new SecurityException("Untrusted provider caller");
        String authority = data.readString();
        ProviderInfo info = null;
        for (PackageParser.Provider p : app.pkg.providers)
            if (hasAuthority(p.info, authority)) { info = p.info; break; }
        if (info == null || !ownProcess.equals(process(info))) throw new SecurityException("Authority is not hosted here");
        enforce(info, caller);
        ContentProvider provider = ContentProvider.atl_get_content_provider(authority);
        if (code == ACQUIRE) { reply.writeNoException(); reply.writeInt(provider == null ? 0 : 1); return true; }
        if (provider == null) throw new IllegalStateException("Provider initialization failed");
        if (code == QUERY) {
            android.net.Uri uri = android.net.Uri.parse(data.readString());
            if (!authority.equals(uri.getAuthority())) throw new SecurityException("Wrong query authority");
            String[] projection = data.createStringArray();
            String selection = data.readString(); String[] args = data.createStringArray(); String order = data.readString();
            android.database.Cursor cursor = provider.atl_queryFromPackage(caller, uri, projection, selection, args, order);
            reply.writeNoException();
            if (cursor == null) { reply.writeInt(-1); return true; }
            try {
                int count = cursor.getCount(); String[] columns = cursor.getColumnNames();
                if (count < 0 || count > 4096 || columns.length > 128)
                    throw new UnsupportedOperationException("Remote cursor exceeds snapshot limits");
                reply.writeInt(count); reply.writeStringArray(columns);
                for (int row = 0; row < count; row++) {
                    if (!cursor.moveToPosition(row)) throw new IllegalStateException("Provider cursor changed during snapshot");
                    for (int col = 0; col < columns.length; col++) {
                        Object value;
                        switch (cursor.getType(col)) {
                        case android.database.Cursor.FIELD_TYPE_NULL: value = null; break;
                        case android.database.Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(col); break;
                        case android.database.Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(col); break;
                        case android.database.Cursor.FIELD_TYPE_STRING: value = cursor.getString(col); break;
                        case android.database.Cursor.FIELD_TYPE_BLOB: value = cursor.getBlob(col); break;
                        default: throw new IllegalArgumentException("Invalid cursor value type");
                        }
                        if ((value instanceof String && ((String)value).length() > 16000)
                            || (value instanceof byte[] && ((byte[])value).length > 64000))
                            throw new UnsupportedOperationException("Remote cursor cell exceeds snapshot limits");
                        reply.writeValue(value);
                        if (reply.dataSize() > 64000) throw new UnsupportedOperationException("Remote cursor exceeds snapshot byte limit");
                    }
                }
                return true;
            } finally { cursor.close(); }
        }
        String method = data.readString(), arg = data.readString();
        if (method == null) throw new NullPointerException("method");
        try {
            Bundle extras = data.readBundle(app.class_loader);
            Bundle result = provider.atl_callFromPackage(caller, method, arg, extras);
            reply.writeNoException(); reply.writeBundle(result); return true;
        } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot decode provider arguments", e); }
    }
}
