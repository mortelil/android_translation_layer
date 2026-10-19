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
import android.os.IBinder;
import android.os.Parcel;
import android.util.Slog;
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
 * START     parent -> child : ComponentName, Intent|null, int startId  reply: int onStartCommand() result
 * BIND      parent -> child : ComponentName, Intent          reply: int 1, IBinder|null
 * REBIND    parent -> child : ComponentName, Intent          reply: int 1
 * UNBIND    parent -> child : ComponentName, Intent          reply: int onUnbind() as 1/0
 * DESTROY   parent -> child : String componentClass          reply: int 1
 * STATUS    parent -> child : -                              reply: String pkg, String process,
 *                                                            int pid, String dataDir, String[] fd names
 * STOP_SELF child -> parent : String componentClass, int startId  reply: int decision (1/0)
 * SVC_START child -> parent : int token, String processName, ComponentName, Intent|null,
 *                             int flags, int startId         reply: - (the result arrives as SVC_RESULT)
 * SVC_BIND  child -> parent : int token, String processName, ComponentName, Intent,
 *                             int flags                      reply: -
 * SVC_UNBIND child -> parent: int token, String processName, ComponentName, Intent  reply: -
 * SVC_REBIND child -> parent: int token, String processName, ComponentName, Intent  reply: -
 * SVC_DESTROY child -> parent: int token, String processName, ComponentName         reply: -
 * PROCESS_UP child -> parent: String processName             reply: int 1/0
 * SVC_RESULT parent -> child: int token, int status, int result, IBinder binder     reply: -
 * </pre>
 *
 * <p>The SVC_* messages are a service child asking its loader to act on a
 * service declared for a process other than the child's own. AOSP has one
 * ActivityManagerService service record for the whole application and resolves
 * a request to the DECLARED process of the component
 * ({@code ActiveServices.startServiceLocked}/{@code bringUpServiceLocked}:
 * {@code procName = r.processName} then
 * {@code mAm.getProcessRecordLocked(procName, uid)}, pinned 94b4c163b7df
 * {@code services/core/java/com/android/server/am/ActiveServices.java:6228-6266};
 * {@code bindServiceInstanceLocked} routes through
 * {@code retrieveServiceLocked} + {@code bringUpServiceLocked}, and the
 * client observes the binder through {@code scheduleBindService} - {@code
 * ActivityManagerService.bindServiceInstance} ->
 * {@code ApplicationThread.scheduleBindService} -> the client's
 * {@code onServiceConnected} on its own looper). The loader is the one
 * authority here, so the child forwards the request, the loader resolves it
 * through its own {@link ATLServiceController} (which owns the process backend
 * and spawns the declared process if it is not running), and the outcome comes
 * back to the child as SVC_RESULT. The child's app-visible outcome is exactly
 * the one its own {@code bindService}/{@code startService} would report: a
 * binder delivered to {@code ServiceConnection.onServiceConnected}, a null
 * binder as {@code onNullBinding}, and a host that could not be reached as
 * {@code onServiceDisconnected} (the DO_SPAWN_FAILURE ruling), never a fake
 * success.
 *
 * <p>SVC_RESULT's {@code status} is one of {@link #SVC_STATUS_OK},
 * {@link #SVC_STATUS_HOST_UNAVAILABLE}, {@link #SVC_STATUS_REFUSED} or
 * {@link #SVC_STATUS_DIED}. A
 * refusal is a synchronous check of the loader's controller (an unknown or
 * unhostable component, a split APK, a non-auto-create bind of a process that
 * is not running) and is reported to the child as a loud failure, never
 * retried and never downgraded to an in-process service: the service always
 * runs in the process its manifest declares.
 *
 * <p>SVC_STATUS_DIED reports that a binding which had already delivered a
 * binder lost its host (the loader's client saw
 * {@code onServiceDisconnected}/{@code onBindingDied}): AOSP delivers
 * {@code onServiceDisconnected()} to the client and keeps the binding, so the
 * child forwards the death, forgets its cached binder and keeps the request
 * pending. A later SVC_STATUS_OK under the same token is therefore a
 * <em>reconnect</em> (the loader's restart ladder re-spawned the process and its
 * own client reconnected), not a second first connect; a SVC_STATUS_REFUSED
 * that arrives after a delivered binder means the relay of a third process'
 * binder is unsupported and is reported as a death.
 *
 * <p>STOP_SELF is the one child-initiated message whose reply carries a payload: the sender is
 * {@code Service.stopSelfResult(int)}, which must return the authority's decision synchronously
 * (AOSP {@code Service.stopSelfResult} returns {@code stopServiceToken}'s result, and returns
 * false when that call cannot be delivered - pinned 94b4c163b7df {@code
 * core/java/android/app/Service.java:662-673}). The decision is therefore computed on the
 * transport worker that handles the message, never handed to the main looper to await: the child
 * is parked in this call, and a loader that answered from its main looper could deadlock against
 * its own synchronous parent-&gt;child call ({@code ParentEntry#onStopSelf}).
 *
 * <p>Every parent->child intent payload starts with the target {@link ComponentName} and an int
 * presence marker: {@code 0} - no intent follows, {@code 1} - the intent fields follow (see
 * {@link #writeIntent}). An intent may legitimately be absent: AOSP fakes up a null-intent start
 * item when it restarts a started service with nothing pending (pinned 94b4c163b7df
 * {@code ActiveServices.java:6596-6601}) and refuses to skip that item "to support the
 * {@code onStartCommand(null)} case" ({@code :6640-6645}). A control message this loader rejects is
 * contained at its decode site ({@link ParentControl#onTransact}) and never reaches the runtime's
 * uncaught-exception handler, which exits the whole loader with status 10.
 */
public final class ATLProcessControl {
	/** Log tag of the control wire. */
	private static final String TAG = "ATLProcessControl";

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
	/** child -> parent: token + process + component + intent(nullable) + flags + startId; result via SVC_RESULT. */
	public static final int SVC_START = 9;
	/** child -> parent: token + process + component + intent + flags; result via SVC_RESULT. */
	public static final int SVC_BIND = 10;
	/** child -> parent: token + process + component + intent; result via SVC_RESULT. */
	public static final int SVC_UNBIND = 11;
	/** child -> parent: token + process + component + intent; no result. */
	public static final int SVC_REBIND = 12;
	/** child -> parent: token + process + component; no result. */
	public static final int SVC_DESTROY = 13;
	/** child -> parent: process name; reply int 1 when the loader has that process up. */
	public static final int PROCESS_UP = 14;
	/** parent -> child: token + status + int result + strong binder (may be null). */
	public static final int SVC_RESULT = 15;

	/** SVC_RESULT: the forwarded op completed; {@code result} and the binder carry its outcome. */
	public static final int SVC_STATUS_OK = 0;
	/** SVC_RESULT: the declared process could not be reached (spawn failure, link loss). */
	public static final int SVC_STATUS_HOST_UNAVAILABLE = 1;
	/** SVC_RESULT: the loader's controller refused the request synchronously (never a downgrade). */
	public static final int SVC_STATUS_REFUSED = 2;
	/**
	 * SVC_RESULT: a binding that had delivered a binder lost its host (AOSP
	 * {@code onServiceDisconnected()}); the binding survives and a later OK
	 * reconnects it.
	 */
	public static final int SVC_STATUS_DIED = 3;
	public static final int SVC_STATUS_BINDING_DIED = 4;

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
	 *
	 * <p>A payload carries the target component first, then an int presence marker --
	 * {@code 0} = no intent follows, {@code 1} = the intent fields follow -- because an
	 * intent may be absent: AOSP fakes up a null-intent start item when it restarts a
	 * started service with nothing pending ({@code ActiveServices.java:6596-6601},
	 * {@code r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
	 * null, null, 0, null, null, ...))}) and {@code :6640-6645} refuses to skip that item
	 * "to support the {@code onStartCommand(null)} case", which is exactly what
	 * {@code ATLServiceRegistry.stickyRestartItem()} fabricates. An explicit absent field
	 * is used rather than a synthetic sentinel intent, which would leak ATL into
	 * app-visible semantics. Both ends are the same build, so no compatibility handling
	 * is needed.
	 */
	static void writeIntent(Parcel data, Intent intent, ComponentName component) {
		if (intent != null && (intent.getClipData() != null || intent.getSelector() != null))
			throw new UnsupportedOperationException("Remote service ClipData/selectors are not implemented");
		data.writeString(component.getPackageName());
		data.writeString(component.getClassName());
		if (intent == null) {
			// Absent intent (AOSP's faked-up start item): the component alone is the payload.
			data.writeInt(0);
			return;
		}
		data.writeInt(1);
		data.writeString(intent.getAction());
		data.writeString(intent.getData() == null ? null : intent.getData().toString());
		data.writeString(intent.getType());
		data.writeInt(intent.getFlags());
		data.writeString(intent.getPackage());
		Set<String> categories = intent.getCategories();
		data.writeStringArray(categories == null ? null : categories.toArray(new String[0]));
		data.writeBundle(intent.getExtras());
	}

	/** The target component of one control payload; always present, even when no intent follows. */
	static ComponentName readComponent(Parcel data) {
		return new ComponentName(data.readString(), data.readString());
	}

	/**
	 * Reads the intent of one control payload, or {@code null} when the payload carried none
	 * ({@code onStartCommand(null, ...)}, AOSP {@code ActiveServices.java:6640-6645}).
	 */
	static Intent readIntent(Parcel data, ComponentName component) {
		if (data.readInt() == 0)
			return null;
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

		/**
		 * Decides a child's {@code Service.stopSelf*()} report and returns the decision: true when
		 * this loader's record accepted the stop (AOSP {@code stopServiceTokenLocked}, pinned
		 * 94b4c163b7df {@code ActiveServices.java:1895-1952}: false for a stale start ID - {@code
		 * r.getLastStartId() != startId} - or no record, true once the record is brought down, also
		 * when bound clients keep it running). Called on the transport worker thread that received
		 * the message, which the child is parked in, so it must decide from thread-safe state and
		 * must never wait on the main looper; the teardown it triggers is posted instead.
		 */
		boolean onStopSelf(String componentClass, int startId);

		/**
		 * True while the loader considers {@code processName} (a manifest process
		 * name) up. Answered on the transport worker thread from thread-safe state:
		 * the child is parked in its synchronous PROCESS_UP call, so this must never
		 * wait on the main looper.
		 */
		boolean isProcessUp(String processName);

		/**
		 * A service child asked the loader to perform SVC_START/SVC_BIND/SVC_UNBIND/
		 * SVC_REBIND/SVC_DESTROY for a component declared for a process other than the
		 * child's own. Called on the transport worker thread; the implementation posts
		 * the controller work and answers the child later with
		 * {@link #sendServiceResult}.
		 */
		void onForwardedServiceOp(int code, int token, String process, ComponentName component,
		                          Intent intent, int flags, int startId);

		/**
		 * Sends one SVC_RESULT to the child (status + result + binder). Never throws:
		 * a child that has gone away has already had its state torn down.
		 */
		void sendServiceResult(int token, int status, int result, IBinder binder);

		/**
		 * A control message of this child was rejected at the decode site: logs it and closes
		 * that child's channel, so the child sees EOF and winds down and the loss surfaces as
		 * this child's death instead of as an uncaught exception on a loader thread (which
		 * would exit the whole loader with status 10). Never blocks; runs on a transport
		 * worker thread.
		 */
		void onRejectedControlMessage(String reason);
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
			try {
				return dispatch(code, data, reply, flags);
			} catch (RuntimeException e) {
				/*
				 * Control decode site. An uncaught exception on any thread exits the whole
				 * loader (status 10), so a malformed or unauthorised message from one child
				 * is contained here: logged against that child, the child's channel closed,
				 * and the loss surfaced as that child's death - the reaper's teardown
				 * reaches onProcessDied(), which the controller already handles. Rethrown so
				 * the transport answers a still-readable child with the refusal (status 2)
				 * rather than with silence.
				 */
				Slog.e(TAG, "control message " + code + " from pid " + Binder.getCallingPid()
				       + " was rejected for process " + entry.processName(), e);
				entry.onRejectedControlMessage(code + ": " + e);
				throw e;
			}
		}

		/** One control message; only called from {@link #onTransact}, which contains what it throws. */
		private boolean dispatch(int code, Parcel data, Parcel reply, int flags) {
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
				// The sender is Service.stopSelfResult(), parked in this call until the
				// reply arrives: decide here (thread-safe, no main-looper wait) and carry
				// the decision back. A refusal or a dead link must never look like a stop.
				boolean stopped = entry.onStopSelf(componentClass, startId);
				reply.writeNoException();
				reply.writeInt(stopped ? 1 : 0);
				return true;
			}
			case SVC_START:
			case SVC_BIND:
			case SVC_UNBIND:
			case SVC_REBIND:
			case SVC_DESTROY: {
				int caller = Binder.getCallingPid();
				if (caller != entry.pid())
					throw new SecurityException("service request " + code + " from pid " + caller + ", expected "
					                            + entry.pid());
				int token = data.readInt();
				String processName = data.readString();
				ComponentName component = readComponent(data);
				Intent intent = readIntent(data, component);
				int opFlags = (code == SVC_START || code == SVC_BIND) ? data.readInt() : 0;
				int startId = code == SVC_START ? data.readInt() : 0;
				// Accepted here, performed on the loader's main looper: the child is
				// released at once and the outcome arrives as SVC_RESULT. The transport
				// worker that received this must stay free for the child's other
				// synchronous control calls (READY/STOP_SELF/PROCESS_UP).
				entry.onForwardedServiceOp(code, token, processName, component, intent, opFlags, startId);
				reply.writeNoException();
				return true;
			}
			case PROCESS_UP: {
				String processName = data.readString();
				int caller = Binder.getCallingPid();
				if (caller != entry.pid())
					throw new SecurityException("PROCESS_UP from pid " + caller + ", expected " + entry.pid());
				// Synchronous by design: ATLServiceController.bindService() asks this
				// before it may forward a non-auto-create bind (AOSP's process check).
				// Answered from thread-safe state, never through the main looper.
				reply.writeNoException();
				reply.writeInt(entry.isProcessUp(processName) ? 1 : 0);
				return true;
			}
			default:
				return super.onTransact(code, data, reply, flags);
			}
		}
	}
}
