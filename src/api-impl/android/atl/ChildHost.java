/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Reused sources (attribution):
 *  - PR #31 baa9b489, src/api-impl/android/atl/ATLRemoteServices.java:
 *    runChild() shape (control Binder as transport root, onTransact running the
 *    command through main.post(FutureTask).get() with the request Parcel kept
 *    alive, ATLBinderConnection(ATL_SERVICE_FD, ATL_SERVICE_PARENT_PID, control),
 *    onDeath -> System.exit(0), start()). PR31 rethrows a failed command; this
 *    class follows Firefox and ends the process instead (see below).
 *  - Firefox a9ebfe2, src/api-impl/android/atl/ATLServiceProcess.java:
 *    HostedServices.serviceFor() (load class, attachBaseContext(new ContextImpl(...)),
 *    publish before onCreate), HostedServices.run() (a callback that throws logs
 *    ATL_SERVICE_PROCESS_CALLBACK_FAILED and exits 10), the exit-0-on-link-death
 *    rule and the ATL_SERVICE_PROCESS_READY evidence line.
 *  - ATLLoadedApp.bringUpService() (this repo): the instance/context bring-up pattern.
 *
 * New in this file: the frozen wire handlers (START/BIND/UNBIND/DESTROY/REBIND/
 * STATUS, READY and STOP_SELF reports), op-time manifest refusals, the mirrored
 * bind counter and the fault seams crash-before-ready / stall-before-ready /
 * stopself-broken-link (see reportStopSelf()).
 */
package android.atl;

import android.app.Application;
import android.app.ContextImpl;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageParser;
import android.os.ATLBinderConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.io.File;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * The service-hosting side of a spawned ATL service process. The loader
 * (ATLProcessHost) drives it over the control link; this class hosts the
 * {@link Service} instances directly, on the child's main looper, with no
 * ATLServiceRegistry routing.
 *
 * <p>The child's life is the link's: when the link goes away the process exits
 * 0. The only other exits are the two fault exits (10: a hosted callback threw,
 * 11: initialization failed). The child never releases itself.
 */
public class ChildHost {
	private static final int EXIT_CALLBACK_FAILED = 10;
	private static final int EXIT_INIT_FAILED = 11;
	private static final long STALL_MS = 600000L;

	private static final Object lock = new Object();
	private static volatile ATLBinderConnection link;
	private static volatile Handler main;
	/** The manifest process name this child hosts; set by init(), read by the fault seam. */
	private static volatile String processName;

	/** Touched only on the main looper. */
	private static final HashMap<String, Hosted> instances = new HashMap<>();
	/** Mirrored non-null-binder counter per component: balance check only, never a release decision. */
	private static final HashMap<String, Integer> binds = new HashMap<>();

	private static final class Hosted {
		final Service service;
		final ContextImpl context;

		Hosted(Service service, ContextImpl context) {
			this.service = service;
			this.context = context;
		}
	}

	private ChildHost() {}

	/**
	 * Called from native (main.c child branch) after the child's Application was
	 * constructed, its providers created and Application.onCreate ran. Starts the
	 * control link, reports READY to the loader and returns; the GLib main loop
	 * keeps the process alive.
	 *
	 * @param fd      this process' end of the link (ATL_SERVICE_FD)
	 * @param process the full manifest process name this child was spawned for
	 */
	public static void runChild(int fd, String process) {
		try {
			init(fd, process);
		} catch (Throwable t) {
			System.err.println("ChildHost: initialization failed in " + process + ": " + t);
			t.printStackTrace();
			die("ATL_SERVICE_PROCESS_INIT_FAILED pid=" + android.os.Process.myPid() + " process=" + process, EXIT_INIT_FAILED);
		}
	}

	private static void init(final int fd, final String process) throws Throwable {
		processName = process;
		if (ATLProcessControl.faultApplies("crash-before-ready", process))
			throw new IllegalStateException("fault injection: crash-before-ready");

		int parentPid = Integer.parseInt(System.getenv("ATL_SERVICE_PARENT_PID"));

		// Manifest scan: the first service (manifest order) resolved to this process.
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		String firstHosted = null;
		for (PackageParser.Service service : app.pkg.services) {
			if (process.equals(ATLProcessControl.componentProcess(app, service))) {
				firstHosted = service.className;
				break;
			}
		}

		main = new Handler(Looper.getMainLooper());
		Binder control = newControl(app, process);
		ATLBinderConnection connection = new ATLBinderConnection(fd, parentPid, control);
		connection.onDeath(new Runnable() {
			public void run() {
				// The loader released this process (or died): nobody is left to
				// deliver callbacks to, so the process ends with the link.
				System.out.println("ATL_SERVICE_PROCESS_RELEASED pid=" + android.os.Process.myPid() + " process=" + process);
				System.out.flush();
				System.exit(0);
			}
		});
		synchronized (lock) {
			link = connection;
		}
		connection.start();

		if (ATLProcessControl.faultApplies("stall-before-ready", process)) {
			// The loader's READY timeout closes the link, and onDeath exits 0.
			System.err.println("ChildHost: fault injection: stalling before READY in " + process);
			Thread.sleep(STALL_MS);
		}

		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeInt(android.os.Process.myPid());
			data.writeString(process);
			data.writeString(firstHosted);
			if (!connection.remote(1).transact(ATLProcessControl.READY, data, reply, 0))
				throw new IllegalStateException("loader rejected READY");
			reply.readException();
		} finally {
			data.recycle();
			reply.recycle();
		}
		// Evidence line for humans; nothing parses it.
		System.out.println("ATL_SERVICE_PROCESS_READY pid=" + android.os.Process.myPid() + " process=" + process
		                   + " first=" + firstHosted);
		System.out.flush();
	}

	/** Only expose a caller known through the live, authenticated parent link. */
	public static android.app.ActivityManager.RunningAppProcessInfo callingProcess() {
		if (link == null || Binder.getCallingPid() == android.os.Process.myPid()) return null;
		String pidText = System.getenv("ATL_SERVICE_PARENT_PID");
		String pkg = System.getenv("ATL_SERVICE_PARENT_PACKAGE");
		if (pidText == null || pkg == null) return null;
		int pid = Integer.parseInt(pidText);
		if (Binder.getCallingPid() != pid) return null;
		return new android.app.ActivityManager.RunningAppProcessInfo(
		    System.getenv("ATL_SERVICE_PARENT_PROCESS"), pid, new String[] {pkg});
	}

	private static void die(String marker, int status) {
		System.out.println(marker);
		System.out.flush();
		System.err.flush();
		System.exit(status);
	}

	/**
	 * Reports a Service.stopSelf*() call to the loader (STOP_SELF) and returns the
	 * loader's decision: true only when the record accepted the stop. Synchronous:
	 * the loader answers on its transport worker and decides on its own schedule,
	 * never by waiting for the main looper, so this call cannot deadlock against a
	 * main-looper thread parked in a call into this child.
	 *
	 * <p>A link that is gone, a refused transaction or a failed report is false, never
	 * a fabricated success (AOSP {@code Service.stopSelfResult} returns false when the
	 * call cannot be delivered - pinned 94b4c163b7df
	 * {@code core/java/android/app/Service.java:662-673}). Both failures are loud on
	 * stderr. Only the failed-transaction path is followed by the link's own death
	 * handler; when there is no link at all (the child is already tearing down, or the
	 * test seam took that path) nothing ends this process, and the false is exactly the
	 * answer a caller whose report could not be delivered must see.
	 */
	public static boolean reportStopSelf(String componentClass, int startId) {
		ATLBinderConnection connection;
		synchronized (lock) {
			connection = link;
		}
		if (connection == null || ATLProcessControl.faultApplies("stopself-broken-link", processName)) {
			// Test seam ATL_SERVICE_PROCESS_FAULT=stopself-broken-link[:proc]: report as
			// if the control link were already gone, i.e. take the real no-link path.
			System.err.println("ChildHost: no control link for the STOP_SELF report of " + componentClass
			                   + " startId=" + startId + "; reporting no decision");
			return false;
		}
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeString(componentClass);
			data.writeInt(startId);
			if (!connection.remote(1).transact(ATLProcessControl.STOP_SELF, data, reply, 0)) {
				System.err.println("ChildHost: the loader rejected the STOP_SELF report of " + componentClass
				                   + " startId=" + startId + "; reporting no decision");
				return false;
			}
			reply.readException();
			return reply.readInt() != 0;
		} catch (RuntimeException e) {
			System.err.println("ChildHost: STOP_SELF report for " + componentClass + " startId=" + startId
			                   + " failed (link dying): " + e);
			e.printStackTrace();
			return false;
		} finally {
			data.recycle();
			reply.recycle();
		}
	}

	/** Control binder: the transport root. Every op runs on the main looper (PR31 runChild pattern). */
	private static Binder newControl(final ATLLoadedApp app, final String process) {
		return new Binder() {
			@Override
			protected boolean onTransact(final int code, final Parcel data, final Parcel reply, int flags) {
				if (code == ATLProviderBridge.ACQUIRE || code == ATLProviderBridge.CALL || code == ATLProviderBridge.QUERY)
					return ATLProviderBridge.dispatch(app, process, code, data, reply);
				if (code < ATLProcessControl.START || code > ATLProcessControl.STATUS)
					return super.onTransact(code, data, reply, flags);
				final FutureTask<Boolean> task = new FutureTask<Boolean>(new Callable<Boolean>() {
					public Boolean call() throws Exception {
						return perform(app, process, code, data, reply);
					}
				});
				main.post(task);
				try {
					// The caller's transport deadline closes this process if its main
					// thread is stuck. Keep the request Parcel alive until the
					// main-thread task finishes, instead of timing out/recycling
					// it while a queued task could still read its descriptors.
					return task.get();
				} catch (ExecutionException e) {
					Throwable cause = e.getCause();
					if (cause instanceof RuntimeException)
						throw (RuntimeException)cause;
					throw new IllegalStateException("Service command failed", cause);
				} catch (Exception e) {
					throw new IllegalStateException("Service main thread did not respond", e);
				}
			}
		};
	}

	/**
	 * Runs one op on the main looper: refusals first (thrown to the loader as
	 * the transaction's exception, the process stays up), then the service
	 * callbacks; a Throwable out of the callbacks ends the process with exit 10
	 * like a crashed app process (Firefox HostedServices.run()).
	 */
	private static boolean perform(ATLLoadedApp app, String process, int code, Parcel data, Parcel reply) {
		final Intent intent;
		final String componentClass;
		final int startId;
		switch (code) {
		case ATLProcessControl.START: {
			// The component is on the wire independently of the intent, so a null intent
			// (AOSP's faked-up start item, ActiveServices.java:6596-6601/6640-6645) still
			// reaches the app as onStartCommand(null, 0, startId).
			ComponentName component = ATLProcessControl.readComponent(data);
			intent = ATLProcessControl.readIntent(data, component);
			startId = data.readInt();
			componentClass = component.getClassName();
			break;
		}
		case ATLProcessControl.BIND:
		case ATLProcessControl.UNBIND:
		case ATLProcessControl.REBIND: {
			ComponentName component = ATLProcessControl.readComponent(data);
			intent = ATLProcessControl.readIntent(data, component);
			startId = 0;
			componentClass = component.getClassName();
			break;
		}
		case ATLProcessControl.DESTROY:
			intent = null;
			startId = 0;
			componentClass = data.readString();
			break;
		default: // STATUS
			intent = null;
			startId = 0;
			componentClass = null;
			break;
		}
		if (componentClass != null)
			requireHostable(app, process, componentClass);
		if ((code == ATLProcessControl.UNBIND || code == ATLProcessControl.REBIND) && !instances.containsKey(componentClass))
			throw new IllegalStateException("Service " + componentClass + " has no live instance in " + process);
		try {
			switch (code) {
			case ATLProcessControl.START: {
				int result = serviceFor(app, componentClass).service.onStartCommand(intent, 0, startId);
				reply.writeNoException();
				reply.writeInt(result);
				break;
			}
			case ATLProcessControl.BIND: {
				IBinder binder = serviceFor(app, componentClass).service.onBind(intent);
				if (binder != null) {
					Integer count = binds.get(componentClass);
					binds.put(componentClass, count == null ? 1 : count + 1);
				}
				reply.writeNoException();
				reply.writeInt(1);
				// A null binder is a successful null-binding, not an error.
				reply.writeStrongBinder(binder);
				break;
			}
			case ATLProcessControl.REBIND:
				instances.get(componentClass).service.onRebind(intent);
				reply.writeNoException();
				reply.writeInt(1);
				break;
			case ATLProcessControl.UNBIND: {
				boolean rebind = instances.get(componentClass).service.onUnbind(intent);
				Integer count = binds.get(componentClass);
				if (count == null || count == 0)
					System.err.println("ChildHost: bind balance: UNBIND of " + componentClass
					                   + " with no counted non-null bind (expected after a null onBind)");
				else
					binds.put(componentClass, count - 1);
				reply.writeNoException();
				reply.writeInt(rebind ? 1 : 0);
				break;
			}
			case ATLProcessControl.DESTROY: {
				Hosted hosted = instances.remove(componentClass);
				Integer count = binds.remove(componentClass);
				if (count != null && count != 0)
					System.err.println("ChildHost: bind balance mismatch: " + componentClass + " destroyed with " + count + " counted bind(s)");
				if (hosted != null)
					hosted.service.onDestroy();
				else
					System.err.println("ChildHost: DESTROY of " + componentClass + " with no live instance");
				reply.writeNoException();
				reply.writeInt(1);
				break;
			}
			default: { // STATUS
				reply.writeNoException();
				reply.writeString(app.pkg.packageName);
				reply.writeString(Application.getProcessName());
				reply.writeInt(android.os.Process.myPid());
				reply.writeString(app.getApplication().getDataDir().getPath());
				reply.writeStringArray(new File("/proc/self/fd").list());
				break;
			}
			}
		} catch (Throwable t) {
			System.err.println("ChildHost: hosted service " + componentClass + " failed in op " + code + ": " + t);
			t.printStackTrace();
			die("ATL_SERVICE_PROCESS_CALLBACK_FAILED pid=" + android.os.Process.myPid() + " process=" + process
			    + " class=" + componentClass + " op=" + code, EXIT_CALLBACK_FAILED);
		}
		return true;
	}

	/** Op-time refusals, before any application code runs. */
	private static void requireHostable(ATLLoadedApp app, String process, String componentClass) {
		PackageParser.Service service = ATLProcessControl.findService(app, componentClass);
		if (service == null)
			throw new IllegalArgumentException("Service " + componentClass + " is not declared in the manifest");
		if (ATLProcessControl.isIsolated(service))
			throw new UnsupportedOperationException("ATL does not host isolated services: " + componentClass);
		String declared = ATLProcessControl.componentProcess(app, service);
		if (!process.equals(declared))
			throw new IllegalArgumentException("Service " + componentClass + " belongs to process " + declared + ", not " + process);
	}

	/**
	 * The live instance of {@code className}, created on the main looper on
	 * first use with the ATLLoadedApp.bringUpService() pattern; the instance is
	 * published before onCreate() so a re-entrant call sees it.
	 */
	private static Hosted serviceFor(ATLLoadedApp app, String className) throws ReflectiveOperationException {
		Hosted hosted = instances.get(className);
		if (hosted != null)
			return hosted;
		Class<? extends Service> cls = app.loadClass(className).asSubclass(Service.class);
		Service service = cls.getConstructor().newInstance();
		ContextImpl context = new ContextImpl(app.createDefaultResources(), app, app.pkg.applicationInfo.theme);
		service.attachBaseContext(context);
		hosted = new Hosted(service, context);
		instances.put(className, hosted);
		service.onCreate();
		return hosted;
	}
}
