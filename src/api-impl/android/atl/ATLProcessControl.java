/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Reused sources (attribution):
 *  - PR #31 baa9b489, src/api-impl/android/atl/ATLRemoteServices.java:
 *    intentData()/readIntent() -> writeIntent()/readIntent() below (field order,
 *    ClipData/selector refusal and the readBundle ReflectiveOperationException
 *    handling are kept as in PR31); remoteProcess() process-name fallback chain
 *    (ServiceInfo.processName -> ApplicationInfo.processName -> packageName)
 *    -> componentProcess().
 *  - Firefox a9ebfe2, src/api-impl/android/atl/ATLServiceProcessManager.java:
 *    test-only fault seam ATL_SERVICE_PROCESS_FAULT -> faultApplies().
 *
 * New in this file: the frozen transaction codes, the ParentControl root binder
 * (READY/STOP_SELF validation by calling pid), and the shared manifest helpers.
 */
package android.atl;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageParser;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import java.util.Set;

/**
 * Wire contract and shared helpers of the loader<->service-child control link.
 *
 * <p>The child's control binder is the transport root (the loader reaches it as
 * {@code connection.remote(1)}); the loader's root for that child is a
 * {@link ParentControl}, which the child reaches as its own {@code remote(1)}.
 * Every reply starts with {@code writeNoException()}.
 *
 * <pre>
 * READY     child -> parent : int pid, String processName, String firstHostedComponent|null
 * START     parent -> child : Intent, int startId            reply: int onStartCommand() result
 * BIND      parent -> child : Intent                         reply: int 1, IBinder|null
 * REBIND    parent -> child : Intent                         reply: int 1
 * UNBIND    parent -> child : Intent                         reply: int onUnbind() as 1/0
 * DESTROY   parent -> child : String componentClass          reply: int 1
 * STATUS    parent -> child : -                              reply: String pkg, String process,
 *                                                            int pid, String dataDir, String[] fd names
 * STOP_SELF child -> parent : String componentClass, int startId
 * </pre>
 */
public final class ATLProcessControl {
	/** child -> parent, once: pid + process name + first hosted component. */
	public static final int READY = 1;
	/** parent -> child: intent + startId; reply int is onStartCommand's return. */
	public static final int START = 2;
	/** parent -> child: intent; reply int 1 + strong binder (may be null). */
	public static final int BIND = 3;
	/** parent -> child: intent; reply int is onUnbind's boolean as 1/0. */
	public static final int UNBIND = 4;
	/** parent -> child: component class; reply int 1. */
	public static final int DESTROY = 5;
	/** parent -> child: intent; reply int 1. */
	public static final int REBIND = 6;
	/** parent -> child, D-local diagnostics: reply pkg, process, pid, dataDir, fd names. */
	public static final int STATUS = 7;
	/** child -> parent: component class + startId of a Service.stopSelf*() call. */
	public static final int STOP_SELF = 8;

	/** Test-only fault seam, inherited by the child through the spawn environment. */
	static final String FAULT_ENV = "ATL_SERVICE_PROCESS_FAULT";

	private ATLProcessControl() {}

	/**
	 * True when the fault seam names {@code kind}, either bare or suffixed with
	 * {@code ":<processName>"} for exactly this process.
	 */
	static boolean faultApplies(String kind, String processName) {
		String fault = System.getenv(FAULT_ENV);
		if (fault == null)
			return false;
		if (fault.equals(kind))
			return true;
		return fault.startsWith(kind + ":") && fault.substring(kind.length() + 1).equals(processName);
	}

	/** The manifest service whose class is {@code className}, or null. */
	static PackageParser.Service findService(ATLLoadedApp app, String className) {
		for (PackageParser.Service service : app.pkg.services)
			if (service.className.equals(className))
				return service;
		return null;
	}

	static boolean isIsolated(PackageParser.Service service) {
		return (service.info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
	}

	/**
	 * The full manifest process name the service runs in; a null or empty
	 * ServiceInfo.processName is the default process, the package name
	 * (fallback chain of PR31 remoteProcess()).
	 */
	static String componentProcess(ATLLoadedApp app, PackageParser.Service service) {
		String process = service.info.processName;
		if (process == null || process.isEmpty())
			process = app.pkg.applicationInfo.processName;
		if (process == null || process.isEmpty())
			process = app.pkg.packageName;
		return process;
	}

	/*
	 * Intent codec: PR31 baa9b489 ATLRemoteServices.intentData()/readIntent(),
	 * field order unchanged (writeIntent appends to a caller-supplied Parcel
	 * instead of obtaining one). Intent itself has no Parcelable implementation
	 * in ATL, so the supported service intent fields are encoded explicitly,
	 * retaining extras and filter identity.
	 */
	static void writeIntent(Parcel data, Intent intent, ComponentName component) {
		if (intent.getClipData() != null || intent.getSelector() != null)
			throw new UnsupportedOperationException("Remote service ClipData/selectors are not implemented");
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
	}

	static Intent readIntent(Parcel data) {
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

	/**
	 * What {@link ParentControl} needs of the loader-side record of one spawned
	 * child. Implemented by ATLProcessHost; methods are called on the loader's
	 * transport worker threads and must neither block nor wait on the main looper.
	 */
	interface ParentEntry {
		int pid();

		String processName();

		/** Marks the child ready and releases the spawn waiter; throws IllegalStateException if it already was or is torn down. */
		void onReady(String firstHostedComponent);

		/** Hands a child stopSelf report on to the loader's main looper; never blocks. */
		void onStopSelf(String componentClass, int startId);
	}

	/**
	 * The loader's transport root for exactly one spawned child. Runs on the
	 * loader's transport worker threads: it never posts-and-waits on the main
	 * looper and never blocks (a blocked worker would stall the child's
	 * synchronous READY/STOP_SELF call).
	 */
	static final class ParentControl extends Binder {
		private final ParentEntry entry;

		ParentControl(ParentEntry entry) {
			this.entry = entry;
		}

		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			switch (code) {
			case READY: {
				int pid = data.readInt();
				String processName = data.readString();
				String firstHosted = data.readString();
				int caller = Binder.getCallingPid();
				if (caller != entry.pid() || pid != entry.pid())
					throw new SecurityException("READY from pid " + caller + " (reported " + pid + "), expected " + entry.pid());
				if (!entry.processName().equals(processName))
					throw new IllegalArgumentException("READY for process " + processName + ", expected " + entry.processName());
				entry.onReady(firstHosted);
				reply.writeNoException();
				return true;
			}
			case STOP_SELF: {
				String componentClass = data.readString();
				int startId = data.readInt();
				int caller = Binder.getCallingPid();
				if (caller != entry.pid())
					throw new SecurityException("STOP_SELF from pid " + caller + ", expected " + entry.pid());
				reply.writeNoException();
				entry.onStopSelf(componentClass, startId);
				return true;
			}
			default:
				return super.onTransact(code, data, reply, flags);
			}
		}
	}
}
