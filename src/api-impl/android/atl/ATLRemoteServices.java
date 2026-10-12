/* SPDX-License-Identifier: GPL-3.0-only */
package android.atl;

import android.app.Application;
import android.content.*;
import android.content.pm.PackageParser;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Experimental service processes owned by one launcher, not a system-wide AMS. */
public final class ATLRemoteServices {
	private static final int BIND = 1, UNBIND = 2, START = 3, STOP = 4;
	private static final HashMap<Integer, String[]> knownProcesses = new HashMap<>();
	private static final HashMap<String, ATLBinderConnection> processes = new HashMap<>();
	private static final IdentityHashMap<Context, IdentityHashMap<ServiceConnection, Client>> clients = new IdentityHashMap<>();
	private static final Handler main = new Handler(Looper.getMainLooper());
	private static ATLBinderConnection child;
	private static final class Client extends Binder {
		final Context context;
		final ServiceConnection connection;
		final ComponentName component;
		final ATLBinderConnection transport;
		volatile boolean active = true;
		boolean connected;
		IBinder connectedBinder;
		Client(Context context, ServiceConnection connection, ComponentName component, ATLBinderConnection transport) {
			this.context = context;
			this.connection = connection;
			this.component = component;
			this.transport = transport;
		}
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			if (code != 1 && code != 2)
				return super.onTransact(code, data, reply, flags);
			final IBinder service = code == 1 ? data.readStrongBinder() : null;
			final int event = code;
			main.post(new Runnable() { public void run() {
                if (!active || (event == 1 && !transport.remote(1).isBinderAlive())) return;
                if (event == 2) {
                    if (connected) { connected = false; connection.onServiceDisconnected(component); }
                    connection.onBindingDied(component); // No automatic restart in this first implementation.
                }
                else {
                    if (connected && connectedBinder != service) {
                        connected = false;
                        connection.onServiceDisconnected(component);
                        if (!active) return;
                    }
                    connectedBinder = service;
                    if (service == null) connection.onNullBinding(component);
                    else { connected = true; connection.onServiceConnected(component, service); }
                }
            } });
			return true;
		}
		void died() { onTransact(2, null, null, 0); }
	}
	public static String remoteProcess(ATLLoadedApp app, ComponentName component) {
		for (PackageParser.Service service : app.pkg.services)
			if (service.className.equals(component.getClassName())) {
				if ((service.info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0)
					throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
				String desired = service.info.processName;
				if (desired == null)
					desired = app.pkg.applicationInfo.processName;
				if (desired == null)
					desired = app.pkg.packageName;
				String current = System.getenv("ATL_SERVICE_PROCESS");
				if (current == null) {
					current = app.pkg.applicationInfo.processName;
					if (current == null)
						current = app.pkg.packageName;
				}
				if (desired.equals(current))
					return null;
				if (!"1".equals(System.getenv("ATL_ENABLE_REMOTE_SERVICES")))
					throw new UnsupportedOperationException("ATL does not support remote or isolated services: " + service.className);
				if (System.getenv("ATL_SERVICE_PROCESS") != null)
					throw new UnsupportedOperationException("Service-to-other-process bindings are not implemented");
				if (app.pkg.applicationInfo.splitSourceDirs != null && app.pkg.applicationInfo.splitSourceDirs.length != 0)
					throw new UnsupportedOperationException("Remote services in split APKs are not implemented");
				return desired;
			}
		return null;
	}
	private static synchronized ATLBinderConnection process(final ATLLoadedApp app, final String name) {
		final String key = app.pkg.packageName + "/" + name;
		ATLBinderConnection existing = processes.get(key);
		if (existing != null && existing.remote(1).isBinderAlive())
			return existing;
		try {
			final int[] launch = ATLBinderConnection.spawn(app.pkg.applicationInfo.sourceDir, name, Build.VERSION.SDK_INT, ATLLoadedApp.getPrimaryApplication().pkg.packageName, Application.getProcessName());
			final ATLBinderConnection transport = new ATLBinderConnection(launch[0], launch[1], ATLRemoteProvider.endpoint(app.pkg.packageName));
			processes.put(key, transport);
			knownProcesses.put(launch[1], new String[] {name, app.pkg.packageName});
			transport.onDeath(new Runnable() { public void run() {
                synchronized (ATLRemoteServices.class) { if (processes.get(key) == transport) processes.remove(key); knownProcesses.remove(launch[1]); }
            } });
			transport.start();
			Thread reaper = new Thread(new Runnable() { public void run() { ATLBinderConnection.reap(launch[1]); transport.close(); } }, "ATL-service-reaper");
			reaper.setDaemon(true);
			reaper.start();
			return transport;
		} catch (IOException e) {
			throw new IllegalStateException("Cannot launch ATL service process", e);
		}
	}
	/** Only processes owned by this launcher/session are visible, not other Linux apps. */
	public static synchronized java.util.List<android.app.ActivityManager.RunningAppProcessInfo> runningProcesses() {
		java.util.List<android.app.ActivityManager.RunningAppProcessInfo> result = new ArrayList<>();
		addProcess(result, android.os.Process.myPid(), Application.getProcessName(), ATLLoadedApp.getPrimaryApplication().pkg.packageName);
		String parent = System.getenv("ATL_SERVICE_PARENT_PID");
		if (System.getenv("ATL_SERVICE_FD") != null && parent != null)
			addProcess(result, Integer.parseInt(parent), System.getenv("ATL_SERVICE_PARENT_PROCESS"), System.getenv("ATL_SERVICE_PARENT_PACKAGE"));
		for (Map.Entry<Integer, String[]> entry : knownProcesses.entrySet())
			addProcess(result, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
		return result;
	}
	private static void addProcess(java.util.List<android.app.ActivityManager.RunningAppProcessInfo> result, int pid, String name, String pkg) {
		android.app.ActivityManager.RunningAppProcessInfo info = new android.app.ActivityManager.RunningAppProcessInfo(name, pid, new String[] {pkg});
		info.uid = android.os.Process.myUid();
		result.add(info);
	}
	private static Parcel intentData(Intent intent, ComponentName component) {
		// Intent itself currently has no Parcelable implementation in ATL.
		// Encode supported service intent fields explicitly, retaining extras and filter identity.
		if (intent.getClipData() != null || intent.getSelector() != null)
			throw new UnsupportedOperationException("Remote service ClipData/selectors are not implemented");
		Parcel data = Parcel.obtain();
		data.writeString(component.getPackageName());
		data.writeString(component.getClassName());
		data.writeString(intent.getAction());
		data.writeString(intent.getData() == null ? null : intent.getData().toString());
		data.writeString(intent.getType());
		data.writeInt(intent.getFlags());
		data.writeString(intent.getPackage());
		Set<String> categories = intent.getCategories();
		data.writeStringArray(categories == null ? null : categories.toArray(new String[0]));
		data.writeBundle(intent.getExtras());
		return data;
	}
	private static Intent readIntent(Parcel data) {
		ComponentName component = new ComponentName(data.readString(), data.readString());
		Intent intent = new Intent(data.readString()).setComponent(component);
		String uri = data.readString(), type = data.readString();
		intent.setDataAndType(uri == null ? null : Uri.parse(uri), type);
		intent.setFlags(data.readInt());
		intent.setPackage(data.readString());
		String[] categories = data.createStringArray();
		if (categories != null)
			for (String category : categories)
				intent.addCategory(category);
		try {
			Bundle extras = data.readBundle(ATLLoadedApp.getPrimaryApplication().class_loader);
			if (extras != null)
				intent.putExtras(extras);
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Invalid service intent extras", e);
		}
		return intent;
	}
	public static ComponentName bind(ATLLoadedApp app, String name, Context context, Intent intent, ComponentName component, ServiceConnection connection, int flags) {
		synchronized (ATLRemoteServices.class) {
			if ((flags & Context.BIND_AUTO_CREATE) == 0 && !processes.containsKey(app.pkg.packageName + "/" + name))
				throw new UnsupportedOperationException("Non-auto-create binding to an unstarted remote process is not implemented");
		}
		ATLBinderConnection transport = process(app, name);
		final Client client;
		synchronized (ATLRemoteServices.class) {
			IdentityHashMap<ServiceConnection, Client> owned = clients.get(context);
			if (owned == null) {
				owned = new IdentityHashMap<>();
				clients.put(context, owned);
			}
			Client old = owned.get(connection);
			if (old != null && (old.transport != transport || !old.component.equals(component)))
				throw new UnsupportedOperationException("One ServiceConnection across remote components is not implemented");
			client = old != null ? old : new Client(context, connection, component, transport);
			if (old == null) {
				owned.put(connection, client);
				transport.onDeath(new Runnable() { public void run() { client.died(); } });
			}
		}
		Parcel data = intentData(intent, component), reply = Parcel.obtain();
		try {
			data.writeInt(flags);
			data.writeStrongBinder(client);
			transport.remote(1).transact(BIND, data, reply, 0);
			reply.readException();
			if (reply.readInt() == 0) {
				forget(context, connection);
				return null;
			}
		} catch (RuntimeException e) {
			forget(context, connection);
			throw e;
		} finally {
			data.recycle();
			reply.recycle();
		}
		return component;
	}
	private static synchronized Client forget(Context context, ServiceConnection connection) {
		IdentityHashMap<ServiceConnection, Client> owned = clients.get(context);
		Client client = owned == null ? null : owned.remove(connection);
		if (owned != null && owned.isEmpty())
			clients.remove(context);
		if (client != null)
			client.active = false;
		return client;
	}
	public static boolean unbind(Context context, ServiceConnection connection) {
		Client client = forget(context, connection);
		if (client == null)
			return false;
		if (!client.transport.remote(1).isBinderAlive())
			return true;
		Parcel data = Parcel.obtain(), reply = Parcel.obtain();
		try {
			data.writeStrongBinder(client);
			client.transport.remote(1).transact(UNBIND, data, reply, 0);
			reply.readException();
		} finally {
			data.recycle();
			reply.recycle();
		}
		return true;
	}
	public static boolean command(ATLLoadedApp app, String name, Intent intent, ComponentName component, boolean start) {
		ATLBinderConnection transport;
		synchronized (ATLRemoteServices.class) { transport = processes.get(app.pkg.packageName + "/" + name); }
		if (transport == null) {
			if (!start)
				return false; // Stopping an absent service must not create an Application.
			transport = process(app, name);
		}
		Parcel data = intentData(intent, component), reply = Parcel.obtain();
		try {
			transport.remote(1).transact(start ? START : STOP, data, reply, 0);
			reply.readException();
			return reply.readInt() != 0;
		} finally {
			data.recycle();
			reply.recycle();
		}
	}
	private static int handleCommand(final ATLLoadedApp app, final Context context,
	                                 IdentityHashMap<IBinder, ServiceConnection> bindings, int code, Parcel data) {
		if (code == UNBIND) {
			IBinder token = data.readStrongBinder();
			ServiceConnection connection = bindings.remove(token);
			if (connection == null)
				throw new IllegalArgumentException("Service not registered");
			app.unbindService(context, connection);
			return 1;
		}
		Intent intent = readIntent(data);
		ComponentName component = app.resolveServiceComponent(intent);
		if (component == null || remoteProcess(app, component) != null)
			throw new IllegalArgumentException("Service does not belong to this process");
		if (code == START)
			return app.startService(intent) == null ? 0 : 1;
		if (code == STOP)
			return app.stopService(intent) ? 1 : 0;
		int bindFlags = data.readInt();
		final IBinder callback = data.readStrongBinder();
		ServiceConnection connection = bindings.get(callback);
		if (connection == null) {
			connection = new ServiceConnection() {
				public void onServiceConnected(ComponentName component, IBinder service) { notify(service); }
				public void onNullBinding(ComponentName component) { notify(null); }
				private void notify(IBinder service) {
					Parcel event = Parcel.obtain();
					try {
						event.writeStrongBinder(service);
						callback.transact(1, event, null, IBinder.FLAG_ONEWAY);
					} finally {
						event.recycle();
					}
				}
			};
			bindings.put(callback, connection);
		}
		return app.bindService(context, intent, connection, bindFlags) == null ? 0 : 1;
	}
	public static IBinder parentProviderBinder() {
		if (child == null) throw new IllegalStateException("Parent provider channel is not ready during Application startup");
		return child.remote(1);
	}
	/** Called by the native launcher after Application.onCreate, instead of launching an Activity. */
	public static void runChild() {
		final ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		final Context context = app.getApplication();
		final IdentityHashMap<IBinder, ServiceConnection> bindings = new IdentityHashMap<>();
		Binder control = new Binder() {
			protected boolean onTransact(final int code, final Parcel data, Parcel reply, int flags) {
				if (code < BIND || code > STOP)
					return super.onTransact(code, data, reply, flags);
				Callable<Integer> command = new Callable<Integer>() {
					public Integer call() { return handleCommand(app, context, bindings, code, data); }
				};
				final FutureTask<Integer> task = new FutureTask<>(command);
				main.post(task);
				try {
					// The caller's transport deadline closes this process if its main
					// thread is stuck. Keep the request Parcel alive until the
					// main-thread task finishes, instead of timing out/recycling
					// it while a queued task could still read its descriptors.
					int result = task.get();
					reply.writeNoException();
					reply.writeInt(result);
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof RuntimeException)
						throw (RuntimeException)cause;
					throw new IllegalStateException("Service command failed", cause);
				} catch (Exception e) {
					throw new IllegalStateException("Service main thread did not respond", e);
				}
				return true;
			}
		};
		child = new ATLBinderConnection(Integer.parseInt(System.getenv("ATL_SERVICE_FD")), Integer.parseInt(System.getenv("ATL_SERVICE_PARENT_PID")), control);
		child.onDeath(new Runnable() { public void run() { System.exit(0); } });
		child.start();
	}
}
