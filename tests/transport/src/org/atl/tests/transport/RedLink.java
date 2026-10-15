/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.os.DeadObjectException;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * TRN-021/T14 RED baseline: a link with the IPCConnection-family semantics
 * Firefox shipped (adapted from Firefox IPCConnection, b51e45f,
 * src/api-impl/android/os/IPCConnection.java, GPL-3.0-only; reused per
 * reuse-ledger R09 for the red side of the workload rows, and per acceptance
 * row T14 kept as evidence in the log):
 *
 *  - the request lock guards send AND wait, so one connection carries at
 *    most one outstanding synchronous request; concurrent callers serialize;
 *  - the reader thread runs binder transactions INLINE, so a handler blocks
 *    all further traffic on that connection;
 *  - FLAG_ONEWAY is not honored: every call waits for its reply;
 *  - there is no deadline and no link-death wake, so a peer that never
 *    replies wedges the caller.
 *
 * It rides the production channel primitives (SOCK_SEQPACKET pair plus the
 * raw send/receive of ATLBinderConnection, reached by reflection like the
 * Firefox probe reached its loader) so the RED rows measure the LINK
 * SEMANTICS, not a socket difference. The frame header follows the Firefox
 * record: id, type, code, flags, handle, result (24 bytes) plus body.
 *
 * TEST INSTRUMENTATION (not part of the copied semantics): a reply watchpoint
 * of 3 seconds turns a would-be wedge into a recorded RED verdict, and the
 * real transport deadline (ATL_IPC_TIMEOUT_MS) is what production uses.
 * Binder tables are not modelled: the red workload passes callback handles as
 * plain integers, which is itself one of the insufficiencies the row exists
 * to show.
 */
final class RedLink {
	static final int TYPE_TRANSACT = 1, TYPE_REPLY = 2;
	static final int FLAG_ONEWAY = 1;
	static final long WATCHPOINT_MS = 3000;

	static final class Pending {
		final int id;
		boolean done, deadlocked;
		int result = -1;
		byte[] bytes;
		Pending(int id) {
			this.id = id;
		}
	}

	final class RedProxy implements IBinder {
		final int handle;
		RedProxy(int handle) {
			this.handle = handle;
		}
		public boolean transact(int code, Parcel data, Parcel reply, int flags) {
			try {
				return transactRed(this, handle, code, data, reply, flags);
			} catch (RemoteException e) {
				throw new RuntimeException(e);
			}
		}
		public boolean isBinderAlive() {
			return !dead;
		}
		public boolean pingBinder() {
			return !dead;
		}
		public void linkToDeath(IBinder.DeathRecipient recipient, int flags) throws RemoteException {
			synchronized (listeners) {
				listeners.add(recipient);
			}
		}
		public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
			synchronized (listeners) {
				return listeners.remove((Object)recipient);
			}
		}
		public IInterface queryLocalInterface(String descriptor) {
			return null;
		}
		public String getInterfaceDescriptor() {
			return "org.atl.tests.transport.RedLink";
		}
	}

	final int fd;
	final int parity;
	final Object requestLock = new Object();
	final Object lock = new Object();
	final HashMap<Integer, IBinder> locals = new HashMap<>();
	final List<IBinder.DeathRecipient> listeners = new ArrayList<>();
	int seq;
	int handleSeq;
	Pending pending;
	volatile boolean dead;

	private RedLink(int fd, int parity) {
		this.fd = fd;
		this.parity = parity;
	}

	static RedLink[] pair() throws Exception {
		int[] fds = android.os.ATLBinderConnection.socketPair();
		RedLink a = new RedLink(fds[0], 0), b = new RedLink(fds[1], 1);
		a.start();
		b.start();
		return new RedLink[]{a, b};
	}

	void start() {
		Thread reader = new Thread(new Runnable() {
			public void run() {
				readLoop();
			}
		}, "trn-redlink-" + parity);
		reader.setDaemon(true);
		reader.start();
	}

	/** Registers a local binder on this link's handle space. */
	int export(IBinder binder) {
		int handle;
		synchronized (locals) {
			handle = 2 + 2 * (handleSeq++) + parity;
			locals.put(Integer.valueOf(handle), binder);
		}
		return handle;
	}

	IBinder proxyFor(int handle) {
		return new RedProxy(handle);
	}

	boolean transactRed(RedProxy from, int handle, int code, Parcel data, Parcel reply,
	                    int flags) throws RemoteException {
		synchronized (requestLock) {
			if (dead)
				throw new DeadObjectException("red link is dead");
			int id;
			synchronized (lock) {
				id = ++seq;
				pending = new Pending(id);
			}
			byte[] body = data == null ? new byte[0] : data.marshall();
			try {
				LinkTools.rawSend(fd, frame(id, TYPE_TRANSACT, code, flags, handle, 0, body),
				                 new int[0]);
			} catch (Exception e) {
				die("send failed: " + e);
			}
			Pending call;
			synchronized (lock) {
				call = pending;
				long deadline = System.nanoTime() + WATCHPOINT_MS * 1000000L;
				while (call != null && !call.done && !dead) {
					long left = deadline - System.nanoTime();
					if (left <= 0)
						break; // watchpoint: record the wedge instead of hanging
					try {
						lock.wait(left / 1000000L, (int)(left % 1000000L));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
				pending = null;
			}
			if (dead)
				throw new DeadObjectException("red link is dead");
			if (call == null || !call.done) {
				watchpointFired = true;
				throw new DeadObjectException(
					"reply watchpoint: no reply within " + WATCHPOINT_MS
					+ "ms (IPCConnection semantics: no deadline, no wake)");
			}
			if (reply != null && call.bytes != null) {
				reply.unmarshall(call.bytes, 0, call.bytes.length);
			}
			return call.result == 0;
		}
	}

	private void readLoop() {
		int[] descriptors = new int[65];
		try {
			while (!dead) {
				byte[] record = LinkTools.rawReceive(fd, descriptors);
				if (record == null)
					break;
				int count = descriptors[0];
				for (int i = 1; i <= count && i < descriptors.length; ++i)
					LinkTools.closeFd(descriptors[i]);
				if (record.length < 24)
					continue;
				int id = Frames.wordAt(record, 0);
				int type = Frames.wordAt(record, 4);
				int code = Frames.wordAt(record, 8);
				int flags = Frames.wordAt(record, 12);
				int handle = Frames.wordAt(record, 16);
				int result = Frames.wordAt(record, 20);
				byte[] body = new byte[Math.max(record.length - 24, 0)];
				System.arraycopy(record, 24, body, 0, body.length);
				if (type == TYPE_TRANSACT) {
					IBinder target;
					synchronized (locals) {
						target = locals.get(Integer.valueOf(handle));
					}
					Parcel data = Parcel.obtain();
					Parcel reply = Parcel.obtain();
					try {
						data.unmarshall(body, 0, body.length);
						// Inline on the reader thread, FLAG_ONEWAY ignored.
						boolean handled = target != null
						                  && target.transact(code, data, reply, flags);
						LinkTools.rawSend(fd, frame(id, TYPE_REPLY, code, 0, handle,
						                            handled ? 0 : 1, reply.marshall()),
						                  new int[0]);
					} finally {
						data.recycle();
						reply.recycle();
					}
				} else if (type == TYPE_REPLY) {
					synchronized (lock) {
						Pending call = pending;
						if (call != null && call.id == id) {
							call.result = result;
							call.bytes = body;
							call.done = true;
							lock.notifyAll();
						}
					}
				}
			}
		} catch (Throwable t) {
			die("reader failed: " + t);
		}
	}

	volatile boolean watchpointFired;

	boolean wedged() {
		return watchpointFired;
	}

	void forceClose() {
		die("closed by test");
		LinkTools.closeFd(fd);
	}

	private void die(String why) {
		List<IBinder.DeathRecipient> toRun;
		synchronized (lock) {
			if (dead)
				return;
			dead = true;
			lock.notifyAll();
			toRun = new ArrayList<>(listeners);
		}
		for (IBinder.DeathRecipient recipient : toRun) {
			try {
				recipient.binderDied();
			} catch (Throwable ignored) {
			}
		}
	}

	private static byte[] frame(int id, int type, int code, int flags, int handle,
	                            int result, byte[] body) {
		byte[] record = new byte[24 + body.length];
		Frames.putWord(record, 0, id);
		Frames.putWord(record, 4, type);
		Frames.putWord(record, 8, code);
		Frames.putWord(record, 12, flags);
		Frames.putWord(record, 16, handle);
		Frames.putWord(record, 20, result);
		System.arraycopy(body, 0, record, 24, body.length);
		return record;
	}
}
