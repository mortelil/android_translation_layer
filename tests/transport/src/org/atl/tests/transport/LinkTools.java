/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.os.ATLBinderConnection;
import android.os.IBinder;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Reflection seam onto the production transport (android.os.ATLBinderConnection
 * of the lifecycle-foundation lane A tree).
 *
 * The Firefox local-service-probe reached the real decoder the same way
 * (tests/local-service-probe IpcFrames.java, Firefox b51e45f: "the class is the
 * loader's own, this probe only calls it"): the probe measures the production
 * object, never a copy. Everything here resolves against the loader's own
 * ATLBinderConnection class at runtime.
 *
 * Surfaces reached:
 *  - raw channel primitives {@code send(fd, bytes, descriptors)} (private
 *    static native): write one crafted record plus SCM_RIGHTS descriptors into
 *    a socket pair end, so the production readLoop decodes exactly the bytes a
 *    hostile or broken peer would deliver;
 *  - {@code closeFd(fd)} (package-private static native): descriptor hygiene
 *    for sentinel descriptors no Java object owns;
 *  - {@code drop(proxy, ack)} (package-private): the §3 deterministic RELEASE
 *    trigger;
 *  - {@code RemoteBinder.id} and the connection's {@code objects} /
 *    {@code pending} maps: internal observables (monotonic handle source,
 *    export-table reclamation, pending saturation) that have no public getter.
 */
final class LinkTools {
	static final Method SEND;
	static final Method RECEIVE;
	static final Method CLOSE_FD;
	static final Method DROP;
	static final Field PROXY_ID;
	static final Field OBJECTS;
	static final Field PENDING;
	static final String SETUP_ERROR;

	static {
		Method send = null, receive = null, closeFd = null, drop = null;
		Field proxyId = null, objects = null, pending = null;
		String error = null;
		try {
			Class<?> cls = Class.forName("android.os.ATLBinderConnection");
			send = cls.getDeclaredMethod("send", int.class, byte[].class, int[].class);
			send.setAccessible(true);
			receive = cls.getDeclaredMethod("receive", int.class, int[].class);
			receive.setAccessible(true);
			closeFd = cls.getDeclaredMethod("closeFd", int.class);
			closeFd.setAccessible(true);
			drop = cls.getDeclaredMethod("drop", IBinder.class, boolean.class);
			drop.setAccessible(true);
			Class<?> proxy = Class.forName("android.os.ATLBinderConnection$RemoteBinder");
			proxyId = proxy.getDeclaredField("id");
			proxyId.setAccessible(true);
			objects = cls.getDeclaredField("objects");
			objects.setAccessible(true);
			pending = cls.getDeclaredField("pending");
			pending.setAccessible(true);
		} catch (Throwable t) {
			error = "" + t;
		}
		SEND = send;
		RECEIVE = receive;
		CLOSE_FD = closeFd;
		DROP = drop;
		PROXY_ID = proxyId;
		OBJECTS = objects;
		PENDING = pending;
		SETUP_ERROR = error;
	}

	private LinkTools() {
	}

	static boolean available() {
		return SETUP_ERROR == null;
	}

	/** One crafted record plus descriptors, written into the raw channel end. */
	static void rawSend(int fd, byte[] frame, int[] descriptors) throws Exception {
		try {
			SEND.invoke(null, Integer.valueOf(fd), frame, descriptors);
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception)
				throw (Exception)cause;
			throw e;
		}
	}

	/** Blocks for one record on the raw channel; null on clean end. */
	static byte[] rawReceive(int fd, int[] descriptors) throws Exception {
		try {
			return (byte[])RECEIVE.invoke(null, Integer.valueOf(fd), descriptors);
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception)
				throw (Exception)cause;
			throw e;
		}
	}

	static void closeFd(int fd) {
		if (fd < 0)
			return;
		try {
			CLOSE_FD.invoke(null, Integer.valueOf(fd));
		} catch (Throwable ignored) {
		}
	}

	/** Wire handle id of a proxy of the production connection. */
	static int handleOf(IBinder proxy) throws IllegalAccessException {
		return PROXY_ID.getInt(proxy);
	}

	@SuppressWarnings("unchecked")
	static HashMap<Object, Object> mapOf(ATLBinderConnection connection, Field field) {
		try {
			return (HashMap<Object, Object>)field.get(connection);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e); // fields were setAccessible at class init
		}
	}

	/** Size of the connection's export table (reflection observable). */
	static int exportTableSize(ATLBinderConnection connection) {
		synchronized (connection) {
			return mapOf(connection, OBJECTS).size();
		}
	}

	/** Size of the connection's pending sync-call map. */
	static int pendingSize(ATLBinderConnection connection) {
		synchronized (connection) {
			return mapOf(connection, PENDING).size();
		}
	}

	/** §3 RELEASE trigger: drop this side's last use of a proxy. */
	static void drop(ATLBinderConnection connection, IBinder proxy, boolean ack) throws Exception {
		try {
			DROP.invoke(connection, proxy, Boolean.valueOf(ack));
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception)
				throw (Exception)cause;
			throw e;
		}
	}

	static boolean fdExists(int fd) {
		return fd >= 0 && new File("/proc/self/fd/" + fd).exists();
	}

	static int fdCount() {
		String[] entries = new File("/proc/self/fd").list();
		return entries == null ? -1 : entries.length;
	}

	/**
	 * A sentinel descriptor no Java object owns: one end of the transport's own
	 * socket-pair factory, the other end closed (Firefox IpcFrames trick, so a
	 * finalizer can never close a descriptor the battery is measuring).
	 */
	static int sentinel() throws Exception {
		int[] pair = ATLBinderConnection.socketPair();
		closeFd(pair[1]);
		return pair[0];
	}
}
