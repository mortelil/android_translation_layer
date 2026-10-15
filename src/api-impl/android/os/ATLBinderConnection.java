/* SPDX-License-Identifier: GPL-3.0-only */
package android.os;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Private, connection-scoped ATL Binder transport; never a public listening socket.
 *
 * <p>Unified lifecycle-foundation transport (lane A). Adapted from PR #31
 * feat/remote-services {@code baa9b489005995efed6d18d199d22683483a5258}
 * (src/api-impl/android/os/ATLBinderConnection.java, GPL-3.0-only) with the
 * Gate-1 fixes of LIFECYCLE-FOUNDATION/architecture.md §2-3:
 *
 * <ul>
 * <li>W1 peer identity from the established channel (SO_PEERCRED), never from
 * trust in message fields; the spawn path cross-checks the child.</li>
 * <li>W2 monotonic export handles plus the W-7 ACQUIRE / W-8 RELEASE
 * reference-lifetime protocol of §3 (in-flight references, proxy collection,
 * teardown, simultaneous release/death).</li>
 * <li>W3 receive-side adversarial validation: overflow-safe long-arithmetic
 * bounds, whole-table FD-slot validation with 4-byte stride overlap rejection
 * before anything is patched, count-vs-received mismatch, trailing-bytes
 * rejection, truncation closes every received descriptor (contract of Firefox
 * IPCConnection b51e45f {@code parse()}/{@code installReceivedFds()},
 * reuse-ledger R09, re-encoded for this wire format; the Firefox parser itself
 * is not copied).</li>
 * <li>W4 the native receive buffer lives on the heap and the descriptor
 * accumulation loop is bounded before memcpy.</li>
 * <li>W5 {@code dispatch()} Error path replies status 2 where the reply is
 * still writable and tears the link down otherwise; silence is never mistaken
 * for success.</li>
 * <li>W6 PR31's concurrency model kept: pending map ≤ 256 sync calls with a
 * 30 s deadline (env {@code ATL_IPC_TIMEOUT_MS} for tests; an ATL limitation,
 * not an Android timing guarantee, and a timeout is never retried), callChain
 * caller-thread reentrancy, per-target FIFO oneway queues (global ≤ 256) on a
 * 0-16 daemon worker pool, and {@code Binder.atlEnter(peerPid)} around every
 * dispatched {@code transact}.</li>
 * </ul>
 *
 * <p>Wire format (one SOCK_SEQPACKET record per message, ≤ 128 KiB, ≤ 64
 * descriptors per message, ≤ 4096 objects per connection):
 * <pre>
 *  int32 MAGIC 0x41544c31
 *  int32 id          correlation id (sender's sequence)
 *  int32 kind        0 call, 1 reply, 2 acquire (W-7), 3 release (W-8)
 *  int32 target      binder handle the call targets / handle being referenced
 *  int32 code        transaction code
 *  int32 flags       IBinder flags; bit 0 FLAG_ONEWAY, bit 0 of a release asks
 *                    for an acknowledgement reply
 *  int64 callChain   reentrancy chain (pid&lt;&lt;32 | seq); 0 for oneway
 *  int32 status      reply status: 1 handled, 0 not handled, 2 exception
 *  int32 length, byte data[length]        marshalled Parcel body
 *  int32 binderCount, int32 binderRefs[binderCount]
 *      ref &gt; 0: a binder of the sender's (receiver makes a proxy);
 *      ref &lt; 0: a binder of the receiver's, handed back (no new reference)
 *  int32 fdCount, int32 fdPositions[fdCount]
 *      byte position of each descriptor's number inside data; the actual
 *      descriptors arrive as SCM_RIGHTS of the same record
 * </pre>
 *
 * <p>Reference-lifetime protocol (§3, written before implementation — T12):
 * handles are never GC-inferred. A handle is allocated monotonically and never
 * retargeted, so a stale reference can never name a different object. The
 * sender of a message that carries one of its own binders follows it with
 * ACQUIRE(handle) while the handle is not yet referenced; the receiver pins
 * the proxy. When a side drops its last use of a proxy (explicit
 * {@link #drop(IBinder)}: dispatcher doForget / record teardown) it sends
 * RELEASE(handle), optionally asking for an acknowledgement; the owner removes
 * its exported entry once no in-flight message still resolves it. RELEASE or
 * ACQUIRE naming an unknown handle is a protocol fault and closes the link.
 * A holder only releases after every message that still referenced the handle;
 * the owner's in-flight bookkeeping covers the overlap. Process death closes
 * the connection: all proxies die (linkToDeath delivery), the exported table
 * is cleared and unclaimed descriptors are closed exactly once.
 *
 * <p>Behavioral references: AOSP frameworks/base
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d (IBinder, Binder, Parcel transport
 * contracts). New transport code is original GPL-3.0-only ATL implementation.
 */
public final class ATLBinderConnection {
	private static final int MAGIC = 0x41544c31, LIMIT = 128 * 1024, MAX_OBJECTS = 4096, MAX_FDS = 64;
	private static final int KIND_CALL = 0, KIND_REPLY = 1, KIND_ACQUIRE = 2, KIND_RELEASE = 3;
	private static final int FLAG_RELEASE_REPLY = 1;
	private static final long DEADLINE_NANOS = deadlineNanos();
	private final int fd, peerPid;
	/** Peer pid as reported by the channel itself (SO_PEERCRED at prepare time). */
	private final int channelPid;
	private final Object output = new Object();
	private volatile boolean alive = true;
	private volatile boolean fdOpen = true;
	private int queuedOneway;
	private final AtomicInteger sequence = new AtomicInteger(1);
	/** Monotonic handle source; a released handle is never reused (W2). */
	private int nextHandle;
	private final IdentityHashMap<IBinder, Integer> exported = new IdentityHashMap<>();
	private final HashMap<Integer, Export> objects = new HashMap<>();
	private final HashMap<Integer, RemoteBinder> proxies = new HashMap<>();
	/** Peer handles this side holds after ACQUIRE; makes RELEASE exactly-once. */
	private final HashSet<Integer> acquired = new HashSet<>();
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
	/** Owner-side state of an exported binder (§3 reference lifetime). */
	private static final class Export {
		final IBinder binder;
		/** Set while the peer holds a reference (ACQUIRE sent/received). */
		boolean referenced;
		/** Messages of ours still resolving this handle (target or ref &lt; 0). */
		int inFlight;
		/** RELEASE seen; the entry goes away once inFlight drains. */
		boolean released;
		Export(IBinder binder) { this.binder = binder; }
	}
	private static long deadlineNanos() {
		String override = System.getenv("ATL_IPC_TIMEOUT_MS");
		long millis = 30_000;
		if (override != null)
			try {
				millis = Math.max(1000, Integer.parseInt(override));
			} catch (NumberFormatException ignored) {
			}
		return TimeUnit.MILLISECONDS.toNanos(millis);
	}
	public ATLBinderConnection(int fd, int peerPid, IBinder root) {
		this.fd = fd;
		int[] credentials;
		try {
			credentials = prepare(fd);
		} catch (IOException e) {
			closeFd(fd);
			throw new IllegalStateException("Cannot prepare IPC socket", e);
		}
		channelPid = credentials[0];
		this.peerPid = resolvePeerPid(peerPid);
		if (root != null) {
			export(root); // Root has handle 1, permanently referenced by the peer.
			objects.get(1).referenced = true;
		}
	}
	/**
	 * W1: the peer's identity comes from the channel, not from trust in the
	 * constructor argument. SO_PEERCRED of a socketpair records the process
	 * that created the pair (verified on Linux 6.8), so:
	 * <ul>
	 * <li>channel pid != ours: the far end was created by another process (the
	 * child side of spawn(), or a socket handed to us); the channel value is
	 * authoritative and a disagreeing constructor argument is rejected.</li>
	 * <li>channel pid == ours: we created the pair (host side of spawn(), or an
	 * in-process test pair). A constructor pid naming another process is then
	 * cross-checked against /proc: the claimed child must exist and hold its
	 * channel socket at the fd 3 that spawn() hands the peer end to.</li>
	 * </ul>
	 */
	private int resolvePeerPid(int claimed) {
		if (channelPid != Process.myPid()) {
			if (claimed != -1 && claimed != channelPid)
				throw new IllegalStateException("Constructor peer pid " + claimed
				                                + " contradicts channel credentials " + channelPid);
			return channelPid;
		}
		if (claimed == Process.myPid() || claimed == -1)
			return channelPid;
		try {
			Path childEnd = Paths.get("/proc/" + claimed + "/fd/3");
			Object target = Files.readSymbolicLink(childEnd);
			if (target.toString().startsWith("socket:"))
				return claimed;
		} catch (IOException | RuntimeException unreadable) {
		}
		throw new IllegalStateException("Spawn peer pid " + claimed + " does not hold the channel socket");
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
			if (objects.size() >= MAX_OBJECTS)
				throw new IllegalStateException("ATL IPC object limit reached");
			id = ++nextHandle; // W2: monotonic; never size()+1 reuse after removal.
			Export state = new Export(binder);
			exported.put(binder, id);
			objects.put(id, state);
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
	/**
	 * Drops this side's last use of a remote proxy and releases the peer's
	 * export (§3). Deterministic transport-internal trigger for the service
	 * dispatcher's doForget/record teardown; never GC-inferred.
	 *
	 * @param waitForAcknowledgement block until the owner confirms removal
	 */
	void drop(IBinder proxy, boolean waitForAcknowledgement) throws DeadObjectException {
		final int handle;
		RemoteBinder remote = (RemoteBinder)proxy;
		if (remote == null || remote.connection() != this)
			throw new IllegalArgumentException("Not a proxy of this connection");
		synchronized (this) {
			if (!acquired.remove(remote.id))
				return; // never referenced here, or already released exactly once
			proxies.remove(remote.id);
			handle = remote.id;
		}
		int id = sequence.getAndIncrement();
		if (!waitForAcknowledgement) {
			try {
				sendControl(id, KIND_RELEASE, handle, 0);
			} catch (IOException | RuntimeException e) {
				close(); // the link is the only way to release; without it we die
			}
			return;
		}
		Pending call = new Pending();
		synchronized (this) {
			if (pending.size() >= 256)
				throw new IllegalStateException("Too many outstanding ATL IPC calls");
			pending.put(id, call);
		}
		try {
			sendControl(id, KIND_RELEASE, handle, FLAG_RELEASE_REPLY);
			synchronized (call) {
				long deadline = System.nanoTime() + DEADLINE_NANOS;
				while (!call.done) {
					if (!alive)
						throw new DeadObjectException("ATL IPC peer disconnected");
					long left = deadline - System.nanoTime();
					if (left <= 0)
						throw new DeadObjectException("ATL IPC release acknowledgement timed out");
					TimeUnit.NANOSECONDS.timedWait(call, left);
				}
			}
		} catch (IOException e) {
			close();
			throw new DeadObjectException(e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			throw new DeadObjectException("ATL IPC interrupted");
		} finally {
			synchronized (this) {
				pending.remove(id);
			}
		}
	}
	private void sendControl(int id, int kind, int handle, int flags) throws IOException {
		sendMessage(id, kind, handle, 0, flags, 0, 0, null);
	}
	/** Wire id for {@code binder} inside an outbound message, remembering handles whose ACQUIRE must follow it. */
	private int referenceOutbound(IBinder binder, ArrayList<Integer> announce) {
		int handle = reference(binder);
		if (handle > 0) {
			synchronized (this) {
				Export state = objects.get(handle);
				if (state != null && !state.referenced) {
					state.referenced = true;
					announce.add(handle);
				}
			}
		}
		return handle;
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
		ArrayList<Integer> announce = new ArrayList<>();
		out.writeInt(binders.length);
		for (IBinder binder : binders)
			out.writeInt(referenceOutbound(binder, announce));
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
		// W2 (§3): the first send of a handle pins it on the peer. ACQUIRE
		// follows its message on this ordered channel, so the peer's pin always
		// exists before it can possibly drop the proxy.
		for (int handle : announce) {
			try {
				sendControl(sequence.getAndIncrement(), KIND_ACQUIRE, handle, 0);
			} catch (IOException | RuntimeException e) {
				close();
				throw new IOException("ATL IPC peer is dead", e);
			}
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
			long deadline = System.nanoTime() + DEADLINE_NANOS;
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
				int[] received = new int[MAX_FDS + 1];
				byte[] bytes = receive(fd, received);
				Parcel parcel = null;
				int[] pinned = NO_PINS;
				int pins = 0;
				try {
					DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
					if (in.readInt() != MAGIC)
						throw new IOException("Invalid ATL IPC version");
					final int id = in.readInt(), kind = in.readInt(), target = in.readInt(), code = in.readInt(), flags = in.readInt();
					final long callChain = in.readLong();
					final int status = in.readInt();
					// W3: every count is bounded before any array is sized, and
					// every table is read with EOF rejection when the frame is
					// too short to hold what it declares (R09 contract).
					int length = bounded(in.readInt(), LIMIT);
					if ((long)length + 4L + 4L + 4L > (long)bytes.length - FIXED_HEADER)
						throw new IOException("ATL IPC declared length exceeds the record");
					byte[] data = new byte[length];
					in.readFully(data);
					int count = bounded(in.readInt(), MAX_OBJECTS);
					// Minimum footprint after the count field: the refs table
					// (4*count) plus the descriptor-table length field (4). A
					// frame with zero descriptors is legal, so exactly one 4-byte
					// field beyond the refs must still be present; the refs and
					// the descriptor table are then read with EOF rejection.
					if (4L * (long)count + 4L > (long)in.available())
						throw new IOException("ATL IPC binder table exceeds the record");
					int[] refs = new int[count];
					for (int i = 0; i < count; ++i)
						refs[i] = in.readInt();
					count = bounded(in.readInt(), MAX_FDS);
					if (count != received[0])
						throw new IOException("ATL IPC descriptor count mismatch");
					int[] positions = new int[count];
					for (int i = 0; i < count; ++i)
						positions[i] = in.readInt();
					if (in.available() != 0)
						throw new IOException("Trailing ATL IPC bytes");
					// Everything the frame declares is validated; publish it.
					IBinder[] binders = new IBinder[refs.length];
					pinned = new int[refs.length + 1];
					for (int i = 0; i < refs.length; ++i) {
						int ref = refs[i];
						if (ref > MAX_OBJECTS || ref < -MAX_OBJECTS)
							throw new IOException("Invalid ATL Binder handle");
						if (ref > 0) {
							binders[i] = remote(ref);
							continue;
						}
						IBinder local = pinLocal(-ref);
						if (local == null)
							throw new IOException("Unknown ATL Binder handle");
						pinned[pins++] = -ref;
					}
					switch (kind) {
					case KIND_CALL: {
						if (target > MAX_OBJECTS || target <= 0)
							throw new IOException("Invalid ATL Binder target");
						IBinder local = pinLocal(target);
						if (local == null)
							throw new IOException("Unknown ATL Binder target");
						pinned[pins++] = target;
						parcel = Parcel.obtain();
						// atlReceive validates the whole descriptor table
						// (bounds and 4-byte stride overlap) before patching.
						parcel.atlReceive(data, binders, positions, Arrays.copyOfRange(received, 1, count + 1));
						received[0] = 0;
						final Parcel request = parcel;
						parcel = null;
						final int[] held = pinned;
						pinned = NO_PINS;
						final int heldCount = pins;
						pins = 0;
						Runnable task = new Runnable() {
							public void run() {
								try {
									dispatch(id, target, code, flags, callChain, request);
								} finally {
									releasePins(held, heldCount);
								}
							}
						};
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
						break;
					}
					case KIND_REPLY: {
						parcel = Parcel.obtain();
						parcel.atlReceive(data, binders, positions, Arrays.copyOfRange(received, 1, count + 1));
						received[0] = 0;
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
						// The reply's resolved binders are held by the caller's
						// Parcel; the owner entry is free to go as soon as the
						// peer's RELEASE arrives.
						break;
					}
					case KIND_ACQUIRE: {
						if (target <= 0 || target > MAX_OBJECTS)
							throw new IOException("Invalid ATL IPC acquire handle");
						synchronized (this) {
							if (!proxies.containsKey(target))
								throw new IOException("ATL IPC acquire for unknown handle");
							acquired.add(target);
						}
						break;
					}
					case KIND_RELEASE: {
						if (target <= 0 || target > MAX_OBJECTS)
							throw new IOException("Invalid ATL IPC release handle");
						boolean removed;
						synchronized (this) {
							Export state = objects.get(target);
							if (state == null)
								throw new IOException("ATL IPC release for unknown handle");
							state.referenced = false;
							state.released = true;
							removed = removeIfDrained(target, state);
						}
						if ((flags & FLAG_RELEASE_REPLY) != 0 && removed)
							sendMessage(id, KIND_REPLY, 0, 0, 0, 0, 1, null);
						break;
					}
					default:
						throw new IOException("Invalid ATL IPC message kind");
					}
				} finally {
					if (parcel != null)
						parcel.recycle();
					releasePins(pinned, pins);
					for (int i = 1; i <= received[0]; ++i)
						closeFd(received[i]);
				}
			}
		} catch (IOException | RuntimeException e) {
			android.util.Log.w("ATLBinder", "IPC connection closed: " + e.getClass().getSimpleName()
			                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
		} finally {
			close();
			synchronized (output) {
				if (fdOpen) {
					// Wake the reader even when close() came first: shutdown is
					// issued exactly once under this lock, so it can never hit
					// a reused descriptor number after the reader closed ours.
					shutdown(fd);
					closeFd(fd);
					fdOpen = false;
				}
			}
		}
	}
	private static final int FIXED_HEADER = 4 + 4 + 4 + 4 + 4 + 4 + 8 + 4;
	private static final int[] NO_PINS = new int[0];
	/** Pins an exported binder for a message being processed (§3 in-flight reference). */
	private IBinder pinLocal(int handle) {
		synchronized (this) {
			Export state = objects.get(handle);
			if (state == null)
				return null;
			++state.inFlight;
			return state.binder;
		}
	}
	private void releasePins(int[] pinned, int pins) {
		if (pins == 0)
			return;
		synchronized (this) {
			for (int i = 0; i < pins; ++i) {
				int handle = pinned[i];
				Export state = objects.get(handle);
				if (state != null && --state.inFlight == 0 && state.released)
					removeIfDrained(handle, state);
			}
		}
	}
	private boolean removeIfDrained(int handle, Export state) {
		if (state.referenced || state.inFlight > 0)
			return false;
		objects.remove(handle);
		exported.remove(state.binder);
		return true;
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
			synchronized (this) {
				Export state = objects.get(target);
				binder = state == null ? null : state.binder;
			}
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
			// W5: an Error in remote dispatch leaves the peer's state unknown.
			// Reply status 2 while the reply is still writable so the caller
			// sees the failure instead of a hang; then close the link either
			// way. Silence on a dead reply path is the documented kill
			// semantics — never mistaken for success, never retried.
			reply.recycle();
			reply = Parcel.obtain();
			try {
				reply.writeException(new IllegalStateException("Remote transaction failed: " + fatal));
				if ((flags & IBinder.FLAG_ONEWAY) == 0)
					sendMessage(id, 1, 0, 0, 0, callChain, 2, reply);
			} catch (Throwable unreadable) {
				// Reply not writable: fall through to the kill below.
			}
			close();
			return;
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
		synchronized (output) {
			if (fdOpen)
				shutdown(fd); // wake the reader so it observes !alive
		}
		synchronized (this) {
			if (!alive)
				return;
			alive = false;
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
			proxies.clear();
			acquired.clear();
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
	/** Socketpair factory for this transport's channel type (test/host channel setup). */
	public static native int[] socketPair() throws IOException;
	private static native void send(int fd, byte[] data, int[] descriptors) throws IOException;
	private static native byte[] receive(int fd, int[] descriptors) throws IOException;
	private static native void shutdown(int fd);
	/** Sets FD_CLOEXEC and the send timeout, and returns the channel's SO_PEERCRED {pid, uid, gid}. */
	private static native int[] prepare(int fd) throws IOException;
	static native void closeFd(int fd);
	static native int dupFd(int fd) throws IOException;
	/** Returns the SO_PEERCRED {pid, uid, gid} of the socket's far end. */
	public static native int[] peerCredentials(int fd) throws IOException;
}
