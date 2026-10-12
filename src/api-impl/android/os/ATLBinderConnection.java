/* SPDX-License-Identifier: GPL-3.0-only */
package android.os;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Private, connection-scoped ATL Binder transport; never a public listening socket. */
public final class ATLBinderConnection {
	private static final int MAGIC = 0x41544c31, LIMIT = 128 * 1024, MAX_OBJECTS = 4096;
	private final int fd, peerPid;
	private final Object output = new Object();
	private volatile boolean alive = true;
	private int queuedOneway;
	private final AtomicInteger sequence = new AtomicInteger(1);
	private final IdentityHashMap<IBinder, Integer> exported = new IdentityHashMap<>();
	private final HashMap<Integer, IBinder> objects = new HashMap<>();
	private final HashMap<Integer, RemoteBinder> proxies = new HashMap<>();
	private final HashMap<Integer, Pending> pending = new HashMap<>();
	private final HashMap<Long, Pending> waiting = new HashMap<>();
	private final ThreadLocal<Long> chain = new ThreadLocal<>();
	private static final ThreadFactory WORKER_THREADS = new ThreadFactory() {
		public Thread newThread(Runnable r) {
			Thread thread = new Thread(r, "ATL-Binder");
			thread.setDaemon(true);
			return thread;
		}
	};
	private final ExecutorService workers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
	                                                               new SynchronousQueue<Runnable>(), WORKER_THREADS);
	private final HashMap<Integer, ArrayDeque<Runnable>> oneways = new HashMap<>();
	private final ArrayList<Runnable> deathListeners = new ArrayList<>();
	private static final class Pending {
		final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
		Parcel reply;
		int status;
		boolean done;
	}
	public ATLBinderConnection(int fd, int peerPid, IBinder root) {
		this.fd = fd;
		this.peerPid = peerPid;
		try {
			prepare(fd);
		} catch (IOException e) {
			closeFd(fd);
			throw new IllegalStateException("Cannot prepare IPC socket", e);
		}
		if (root != null)
			export(root); // Root has handle 1.
	}
	public void start() {
		Thread reader = new Thread(new Runnable() { public void run() { readLoop(); } }, "ATL-Binder-reader");
		reader.setDaemon(true);
		reader.start();
	}
	public void onDeath(Runnable listener) {
		synchronized (this) {
			if (alive) {
				deathListeners.add(listener);
				return;
			}
		}
		listener.run();
	}
	public synchronized IBinder remote(int id) {
		RemoteBinder result = proxies.get(id);
		if (result == null) {
			result = new RemoteBinder(id);
			proxies.put(id, result);
		}
		return result;
	}
	private synchronized int export(IBinder binder) {
		Integer id = exported.get(binder);
		if (id == null) {
			if (exported.size() >= MAX_OBJECTS)
				throw new IllegalStateException("ATL IPC object limit reached");
			id = exported.size() + 1;
			exported.put(binder, id);
			objects.put(id, binder);
		}
		return id;
	}
	private int reference(IBinder binder) {
		if (binder instanceof RemoteBinder) {
			RemoteBinder remote = (RemoteBinder)binder;
			if (remote.connection() != this)
				throw new UnsupportedOperationException("Binder forwarding between ATL connections is not supported");
			return -remote.id;
		}
		return export(binder);
	}
	private synchronized IBinder resolve(int id) throws IOException {
		if (id > MAX_OBJECTS || id < -MAX_OBJECTS)
			throw new IOException("Invalid ATL Binder handle");
		if (id > 0)
			return remote(id);
		IBinder local = objects.get(-id);
		if (local == null)
			throw new IOException("Unknown ATL Binder handle");
		return local;
	}
	private void sendMessage(int id, int kind, int target, int code, int flags, long callChain, int status, Parcel parcel) throws IOException {
		if (!alive)
			throw new IOException("ATL IPC peer is dead");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(MAGIC);
		out.writeInt(id);
		out.writeInt(kind);
		out.writeInt(target);
		out.writeInt(code);
		out.writeInt(flags);
		out.writeLong(callChain);
		out.writeInt(status);
		byte[] data = parcel == null ? new byte[0] : parcel.marshall();
		out.writeInt(data.length);
		out.write(data);
		IBinder[] binders = parcel == null ? new IBinder[0] : parcel.atlBinders();
		out.writeInt(binders.length);
		for (IBinder binder : binders)
			out.writeInt(reference(binder));
		int[] positions = parcel == null ? new int[0] : parcel.atlFdPositions();
		out.writeInt(positions.length);
		for (int position : positions)
			out.writeInt(position);
		int[] fds = parcel == null ? new int[0] : parcel.atlFds();
		if (bytes.size() > LIMIT)
			throw new IOException("ATL IPC transaction exceeds 128 KiB");
		synchronized (output) {
			if (!alive)
				throw new IOException("ATL IPC peer is dead");
			send(fd, bytes.toByteArray(), fds);
		}
	}
	private boolean transact(int target, int code, Parcel data, Parcel reply, int flags) {
		int id = sequence.getAndIncrement();
		Long previous = chain.get();
		long callChain = previous != null ? previous : (((long)Process.myPid()) << 32) | (id & 0xffffffffL);
		boolean oneway = (flags & IBinder.FLAG_ONEWAY) != 0;
		Pending call = new Pending(), oldWaiter = null;
		if (!oneway)
			synchronized (this) {
				if (pending.size() >= 256)
					throw new IllegalStateException("Too many outstanding ATL IPC calls");
				pending.put(id, call);
				oldWaiter = waiting.put(callChain, call);
			}
		chain.set(callChain);
		try {
			sendMessage(id, 0, target, code, flags, oneway ? 0 : callChain, 0, data);
			if (oneway)
				return true;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (true) {
				Runnable callback = null;
				synchronized (call) {
					if (call.done)
						break;
					if (!alive)
						throw new IOException("ATL IPC peer disconnected");
					if (!call.callbacks.isEmpty())
						callback = call.callbacks.removeFirst();
					else {
						long left = deadline - System.nanoTime();
						if (left <= 0)
							throw new IOException("ATL IPC reply timed out; peer will be disconnected");
						TimeUnit.NANOSECONDS.timedWait(call, left);
					}
				}
				// Reentrant synchronous calls return to the waiting thread, as Binder requires.
				if (callback != null)
					callback.run();
			}
			if (call.status == 2) {
				call.reply.readException();
				throw new IllegalStateException("Missing remote exception");
			}
			if (reply != null)
				reply.atlTake(call.reply);
			return call.status == 1;
		} catch (IOException e) {
			close();
			return ATLBinderConnection.<RuntimeException, Boolean>raise(new DeadObjectException(e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			return ATLBinderConnection.<RuntimeException, Boolean>raise(new DeadObjectException("ATL IPC interrupted"));
		} finally {
			chain.set(previous);
			if (!oneway)
				synchronized (this) {
					pending.remove(id);
					if (oldWaiter == null)
						waiting.remove(callChain);
					else
						waiting.put(callChain, oldWaiter);
				}
			if (call.reply != null)
				call.reply.recycle();
		}
	}
	private void readLoop() {
		try {
			while (alive) {
				int[] received = new int[65];
				byte[] bytes = receive(fd, received);
				Parcel parcel = null;
				try {
					DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
					if (in.readInt() != MAGIC)
						throw new IOException("Invalid ATL IPC version");
					final int id = in.readInt(), kind = in.readInt(), target = in.readInt(), code = in.readInt(), flags = in.readInt();
					final long callChain = in.readLong();
					final int status = in.readInt();
					int length = bounded(in.readInt(), LIMIT);
					byte[] data = new byte[length];
					in.readFully(data);
					int count = bounded(in.readInt(), MAX_OBJECTS);
					IBinder[] binders = new IBinder[count];
					for (int i = 0; i < count; ++i)
						binders[i] = resolve(in.readInt());
					count = bounded(in.readInt(), 64);
					if (count != received[0])
						throw new IOException("ATL IPC descriptor count mismatch");
					int[] positions = new int[count];
					for (int i = 0; i < count; ++i)
						positions[i] = in.readInt();
					if (in.available() != 0)
						throw new IOException("Trailing ATL IPC bytes");
					parcel = Parcel.obtain();
					parcel.atlReceive(data, binders, positions, Arrays.copyOfRange(received, 1, count + 1));
					received[0] = 0;
					if (kind == 1) {
						Pending call;
						synchronized (this) { call = pending.get(id); }
						if (call == null)
							throw new IOException("Unexpected ATL IPC reply");
						synchronized (call) {
							call.reply = parcel;
							call.status = status;
							call.done = true;
							call.notifyAll();
						}
						parcel = null;
					} else if (kind == 0) {
						final Parcel request = parcel;
						parcel = null;
						Runnable task = new Runnable() { public void run() { dispatch(id, target, code, flags, callChain, request); } };
						try {
							if ((flags & IBinder.FLAG_ONEWAY) != 0)
								enqueueOneway(target, task);
							else {
								Pending waiter;
								synchronized (this) { waiter = waiting.get(callChain); }
								if (waiter != null)
									synchronized (waiter) {
										if (alive) {
											waiter.callbacks.add(task);
											waiter.notifyAll();
										} else
											request.recycle();
									}
								else
									workers.execute(task);
							}
						} catch (RuntimeException rejected) {
							request.recycle();
							throw rejected;
						}
					} else
						throw new IOException("Invalid ATL IPC message kind");
				} finally {
					if (parcel != null)
						parcel.recycle();
					for (int i = 1; i <= received[0]; ++i)
						closeFd(received[i]);
				}
			}
		} catch (IOException | RuntimeException e) {
			android.util.Log.w("ATLBinder", "IPC connection closed: " + e.getClass().getSimpleName());
		} finally {
			close();
			synchronized (output) { closeFd(fd); }
		}
	}
	private static int bounded(int n, int limit) throws IOException {
		if (n < 0 || n > limit)
			throw new IOException("Invalid ATL IPC length");
		return n;
	}
	private synchronized void enqueueOneway(final int target, Runnable task) {
		if (queuedOneway >= 256)
			throw new IllegalStateException("ATL IPC oneway queue full");
		++queuedOneway;
		ArrayDeque<Runnable> queue = oneways.get(target);
		if (queue != null) {
			queue.add(task);
			return;
		}
		queue = new ArrayDeque<>();
		queue.add(task);
		oneways.put(target, queue);
		workers.execute(new Runnable() { public void run() {
            while (true) {
                Runnable next;
                synchronized (ATLBinderConnection.this) {
                    next = oneways.get(target).poll();
                    if (next == null) { oneways.remove(target); return; }
                    --queuedOneway;
                }
                next.run();
            }
        } });
	}
	private void dispatch(int id, int target, int code, int flags, long callChain, Parcel request) {
		if (!alive) {
			request.recycle();
			return;
		}
		Parcel reply = Parcel.obtain();
		int status = 0;
		Long previous = chain.get();
		chain.set(callChain == 0 ? null : callChain);
		long identity = Binder.atlEnter(peerPid);
		try {
			IBinder binder;
			synchronized (this) { binder = objects.get(target); }
			if (binder == null)
				throw new IllegalArgumentException("Unknown ATL Binder target");
			status = binder.transact(code, request, (flags & IBinder.FLAG_ONEWAY) == 0 ? reply : null, flags) ? 1 : 0;
		} catch (Exception e) {
			reply.recycle();
			reply = Parcel.obtain();
			status = 2;
			try {
				reply.writeException(e);
			} catch (Exception unsupported) {
				try {
					reply.writeException(new IllegalStateException("Remote service transaction failed"));
				} catch (Exception impossible) {
					throw new AssertionError(impossible);
				}
			}
		} catch (Error fatal) {
			close();
		} finally {
			Binder.restoreCallingIdentity(identity);
			chain.set(previous);
			request.recycle();
		}
		try {
			if ((flags & IBinder.FLAG_ONEWAY) == 0)
				sendMessage(id, 1, 0, 0, 0, callChain, status, reply);
		} catch (IOException | RuntimeException e) {
			close();
		} finally {
			reply.recycle();
		}
	}
	public void close() {
		ArrayList<Runnable> listeners;
		ArrayList<Runnable> abandoned = new ArrayList<>();
		ArrayList<RemoteBinder> binders;
		synchronized (this) {
			if (!alive)
				return;
			alive = false;
			shutdown(fd);
			for (Pending call : pending.values())
				synchronized (call) {
					abandoned.addAll(call.callbacks);
					call.callbacks.clear();
					call.notifyAll();
				}
			listeners = new ArrayList<>(deathListeners);
			deathListeners.clear();
			binders = new ArrayList<>(proxies.values());
			objects.clear();
			exported.clear();
		}
		// These dispatchers see !alive and only recycle their received Parcels.
		for (Runnable callback : abandoned)
			callback.run();
		for (RemoteBinder binder : binders)
			binder.died();
		for (Runnable listener : listeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				android.util.Log.w("ATLBinder", "Death listener failed");
			}
		}
		workers.shutdown();
	}
	private final class RemoteBinder implements IBinder {
		final int id;
		final ArrayList<DeathRecipient> recipients = new ArrayList<>();
		RemoteBinder(int id) { this.id = id; }
		ATLBinderConnection connection() { return ATLBinderConnection.this; }
		public IInterface queryLocalInterface(String descriptor) { return null; }
		public boolean transact(int code, Parcel data, Parcel reply, int flags) { return ATLBinderConnection.this.transact(id, code, data, reply, flags); }
		public String getInterfaceDescriptor() {
			Parcel reply = Parcel.obtain();
			try {
				transact(INTERFACE_TRANSACTION, Parcel.obtain(), reply, 0);
				return reply.readString();
			} finally {
				reply.recycle();
			}
		}
		public boolean isBinderAlive() { return alive; }
		public boolean pingBinder() { return alive; }
		public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
			if (recipient == null)
				throw new NullPointerException();
			synchronized (ATLBinderConnection.this) {
				if (!alive)
					throw new DeadObjectException();
				recipients.add(recipient);
			}
		}
		public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
			synchronized (ATLBinderConnection.this) {
				if (!alive)
					return false;
				if (!recipients.remove(recipient))
					throw new NoSuchElementException();
				return true;
			}
		}
		void died() {
			ArrayList<DeathRecipient> copy;
			synchronized (ATLBinderConnection.this) {
				copy = new ArrayList<>(recipients);
				recipients.clear();
			}
			for (DeathRecipient recipient : copy) {
				try {
					recipient.binderDied();
				} catch (RuntimeException e) {
					android.util.Log.w("ATLBinder", "Death recipient failed");
				}
			}
		}
	}
	private static <E extends Throwable, T> T raise(Throwable e) throws E { throw (E)e; }
	public static native int[] spawn(String apk, String process, int sdk, String parentPackage, String parentProcess) throws IOException;
	public static native void reap(int pid);
	private static native void send(int fd, byte[] data, int[] descriptors) throws IOException;
	private static native byte[] receive(int fd, int[] descriptors) throws IOException;
	private static native void shutdown(int fd);
	private static native void prepare(int fd) throws IOException;
	static native void closeFd(int fd);
	static native int dupFd(int fd) throws IOException;
}
