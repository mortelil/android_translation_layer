/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.ATLBinderConnection;
import android.os.Binder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TRN-011/TRN-014 transport probe: acceptance rows T01-T14 for the production
 * ATLBinderConnection (lifecycle-foundation lane A).
 *
 * Parent mode runs the whole battery against the transport as built (spawned
 * cross-process child for T01-T07/T10-T12 via the production spawn path,
 * in-process production links for the saturation/deadline and workload rows,
 * raw channel injection for the T08 framing battery, and the IPCConnection
 * red baseline for T14). Child mode is {@link ChildTransportHost}; the
 * launcher activity switches on ATL_SERVICE_FD like the PR31 remote-services
 * probe switched on its service process (PR31 baa9b489).
 *
 * Cases map one-to-one to acceptance rows; every case prints one unique
 * marker line on success, and the final marker TRANSPORT BATTERY PASSED
 * appears only when every case passed in this single run (T13's combined
 * log requirement). results.json (Firefox local-service-probe format) is
 * written next to the app files for the offline verifier.
 */
public class TransportProbe extends Activity implements Frames.Sink {
	static final int FLAG_ONEWAY = IBinder.FLAG_ONEWAY;
	static final long CASE_WAIT_MS = 5000;

	final List<String[]> events = new ArrayList<>();
	final List<String> markers = new ArrayList<>();
	final List<String> failures = new ArrayList<>();
	volatile boolean batteryDone;
	volatile int nestedCallerThread = -1;
	volatile int nestedCallbackThread = -1;
	volatile int deathRecipientCalls;
	volatile long deadlineMs = 30000;

	// In-process T09 server observables.
	final AtomicInteger gateStart = new AtomicInteger();
	final AtomicInteger delayLongStart = new AtomicInteger();
	final AtomicInteger delayLongEnd = new AtomicInteger();

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (ChildTransportHost.isChild()) {
			try {
				ChildTransportHost.start(this);
			} catch (Throwable t) {
				System.out.println("TRANSPORT CHILD FAILED " + t);
				throw new RuntimeException(t);
			}
			return; // the ATL looper keeps this process alive
		}
		Thread probe = new Thread(new Runnable() {
			public void run() {
				battery();
			}
		}, "trn-probe");
		probe.setDaemon(true);
		probe.start();
	}

	// ------------------------------------------------------------ event sink

	@Override
	public void event(String cmd, String ret) {
		synchronized (events) {
			events.add(new String[]{cmd, ret});
		}
		System.out.println("TRANSPORT EVENT " + cmd + " " + ret);
	}

	@Override
	public void marker(String line) {
		synchronized (markers) {
			markers.add(line);
		}
		System.out.println(line);
	}

	void probeEvent(String cmd, String ret) {
		event("probeCase:" + cmd, ret);
	}

	private void caseMarker(String row, String name, String detail) {
		String line = "TRANSPORT PASS " + name + " " + detail;
		marker(line);
		probeEvent(name, "pass=1;" + detail);
	}

	private void caseFail(String name, String detail) {
		marker("TRANSPORT FAIL " + name + " " + detail);
		synchronized (failures) {
			failures.add(name + ": " + detail);
		}
		probeEvent(name, "pass=0;" + detail);
	}

	// ------------------------------------------------------------- the battery

	void battery() {
		String envDeadline = System.getenv("ATL_IPC_TIMEOUT_MS");
		if (envDeadline != null) {
			try {
				deadlineMs = Math.max(1000, Long.parseLong(envDeadline.trim()));
			} catch (NumberFormatException ignored) {
			}
		}
		marker("TRANSPORT BATTERY START pid=" + Process.myPid()
		       + " uid=" + android.os.Process.myUid() + " deadlineMs=" + deadlineMs);
		try {
			if (!LinkTools.available())
				throw new IllegalStateException("reflection seam broken: " + LinkTools.SETUP_ERROR);
			ParentCallback callback = new ParentCallback(this);

			Child child = spawnChild(":transport-child", callback, 90000);
			t06Identity(child);
			t01ConcurrentSync(child);
			t02NestedReentrancy(child, callback);
			IBinder orderProxy = getOrderTarget(child);
			t03OnewayNonblocking(orderProxy);
			t04OnewayOrder(orderProxy, child);
			t05FailureDistinction(child);
			t07FdTransfer(child);
			t11HandleChurn(child, orderProxy);
			t10DeathWake(child);

			Child child2 = spawnChild(":transport-child2", callback, 90000);
			t12ReleaseVsDeathRace(child2);

			t09SaturationAndDeadline(callback);
			t09bCloseUnderLoad(callback);
			t06ForgedIdentity();
			t06ForgedSpawnPid();
			Frames.run(this);
			checkFrameBattery();
			redBaseline(callback);
			checkInventory();
			// "PASSED" has to mean every required row passed, not "the run reached
			// the end": a case that recorded a failure (caseFail) must stop the
			// battery here rather than let the marker contradict an EVENT pass=0.
			check(failures.isEmpty(), "cases failed: " + failures);
			writeResults(true);
			marker("TRANSPORT BATTERY PASSED");
			// End the run deterministically: the harness waits on the marker in
			// stdout.log, so leaving the process alive only makes a finished run
			// indistinguishable from a hung one.
			System.out.flush();
			try {
				Thread.sleep(400); // let the child releases land in their logs
			} catch (InterruptedException ignored) {
			}
			System.exit(0);
		} catch (Throwable t) {
			synchronized (failures) {
				failures.add("battery: " + t);
			}
			System.out.println("TRANSPORT BATTERY FAILED " + t);
			t.printStackTrace();
			writeResults(false);
			marker("TRANSPORT BATTERY FAILED " + t.getClass().getSimpleName());
			// A failed battery stops at the first failure; exit at once instead
			// of idling until the harness deadline, which would cost a full
			// timeout for every iteration.
			System.out.flush();
			System.exit(2);
		} finally {
			batteryDone = true;
		}
	}

	// ------------------------------------------------------------------ spawn

	static final class Child {
		final int pid;
		final int fd;
		final ATLBinderConnection connection;
		volatile IBinder root;
		volatile IBinder lastNewBinder;
		final CountDownLatch death = new CountDownLatch(1);
		volatile boolean deathListenerFired;
		Child(int pid, int fd, ATLBinderConnection connection) {
			this.pid = pid;
			this.fd = fd;
			this.connection = connection;
		}
	}

	Child spawnChild(String processSuffix, IBinder parentRoot, long readyTimeoutMs)
	        throws Exception {
		String apk = getApplicationInfo().sourceDir;
		int[] spawn = ATLBinderConnection.spawn(apk, processSuffix, Build.VERSION.SDK_INT,
		                                        getPackageName(), Application.getProcessName());
		// Frozen spawn contract (native android_os_ATLBinderConnection.c: values[]={pair[0],pid},
		// PR31 caller ATLRemoteServices.process(): launch[0]=fd, launch[1]=pid; ctor is (fd, peerPid, root)).
		final int fd = spawn[0], pid = spawn[1];
		final Child child = new Child(pid, fd,
		                              new ATLBinderConnection(fd, pid, parentRoot));
		child.connection.onDeath(new Runnable() {
			public void run() {
				child.deathListenerFired = true;
				child.death.countDown();
			}
		});
		child.connection.start();
		File ready = new File(getFilesDir(), "child-ready-" + pid);
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs);
		while (!ready.exists() && System.nanoTime() < deadline)
			Thread.sleep(100);
		if (!ready.exists())
			throw new IllegalStateException("child never signalled ready: " + processSuffix);
		IBinder remoteRoot = child.connection.remote(1);
		child.root = remoteRoot;
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			check(remoteRoot.transact(ChildTransportHost.PING, data, reply, 0),
			      "child did not answer PING");
			check(reply.readInt() == pid, "child PING returned wrong pid");
		} finally {
			data.recycle();
			reply.recycle();
		}
		marker("TRANSPORT CHILD READY confirmed pid=" + pid);
		return child;
	}

	// ------------------------------------------------------------------ rows

	void t01ConcurrentSync(Child child) throws Exception {
		final int threads = 12, calls = 8;
		final AtomicInteger mismatches = new AtomicInteger();
		Thread[] pool = new Thread[threads];
		long start = System.nanoTime();
		for (int t = 0; t < threads; ++t) {
			final int id = t;
			pool[t] = new Thread(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < calls; ++i) {
							int value = 1000 * id + i;
							Parcel data = Parcel.obtain();
							Parcel reply = Parcel.obtain();
							try {
								data.writeInt(value);
								if (!child.root.transact(ChildTransportHost.ECHO, data, reply, 0)
								    || reply.readInt() != value)
									mismatches.incrementAndGet();
							} finally {
								data.recycle();
								reply.recycle();
							}
						}
					} catch (Throwable e) {
						mismatches.incrementAndGet();
					}
				}
			}, "trn-t01-" + t);
			pool[t].start();
		}
		for (int t = 0; t < threads; ++t)
			pool[t].join(60000);
		long wallMs = (System.nanoTime() - start) / 1000000L;
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		int overlap;
		try {
			check(child.root.transact(ChildTransportHost.OVERLAP, data, reply, 0),
			      "child did not answer OVERLAP");
			overlap = reply.readInt();
		} finally {
			data.recycle();
			reply.recycle();
		}
		boolean ok = mismatches.get() == 0 && overlap > 1;
		probeEvent("t01-concurrent-sync", "mismatches=" + mismatches + ";overlap=" + overlap
		                                         + ";wallMs=" + wallMs);
		if (ok)
			caseMarker("T01", "t01-concurrent-sync", "overlap=" + overlap
			                      + " mismatches=" + mismatches + " wallMs=" + wallMs);
		else
			caseFail("t01-concurrent-sync", "overlap=" + overlap + " mismatches="
			                                   + mismatches);
	}

	void t02NestedReentrancy(Child child, ParentCallback callback) throws Exception {
		nestedCallbackThread = -1;
		long callerThread = Thread.currentThread().getId();
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeInt(7);
			data.writeStrongBinder(callback);
			check(child.root.transact(ChildTransportHost.NESTED, data, reply, 0),
			      "nested transact failed");
			check(reply.readInt() == 42, "nested reply value lost");
			IBinder echoed = reply.readStrongBinder();
			check(echoed == (IBinder)callback, "callback identity not preserved");
		} finally {
			data.recycle();
			reply.recycle();
		}
		boolean onCallerThread = nestedCallbackThread == callerThread;
		probeEvent("t02-nested-reentrancy", "callbackThread=" + nestedCallbackThread
		                                      + ";callerThread=" + callerThread
		                                      + ";onCallerThread=" + onCallerThread);
		if (onCallerThread)
			caseMarker("T02", "t02-nested-reentrancy", "callbackThread=" + nestedCallbackThread);
		else
			caseFail("t02-nested-reentrancy", "callbackThread=" + nestedCallbackThread
			                                    + " callerThread=" + callerThread);
	}

	IBinder getOrderTarget(Child child) throws Exception {
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			check(child.root.transact(ChildTransportHost.GET_ORDER_TARGET, data, reply, 0),
			      "child did not hand out the order target");
			IBinder proxy = reply.readStrongBinder();
			check(proxy != null, "order target binder missing");
			return proxy;
		} finally {
			data.recycle();
			reply.recycle();
		}
	}

	void t03OnewayNonblocking(IBinder orderProxy) throws Exception {
		long start = System.nanoTime();
		Parcel data = Parcel.obtain();
		try {
			check(orderProxy.transact(ChildTransportHost.SLEEP_ONEWAY, data, null, FLAG_ONEWAY),
			      "oneway send failed");
		} finally {
			data.recycle();
		}
		long elapsedMs = (System.nanoTime() - start) / 1000000L;
		boolean ok = elapsedMs < 300;
		probeEvent("t03-oneway-nonblocking", "elapsedMs=" + elapsedMs);
		if (ok)
			caseMarker("T03", "t03-oneway-nonblocking", "elapsedMs=" + elapsedMs);
		else
			caseFail("t03-oneway-nonblocking", "elapsedMs=" + elapsedMs);
	}

	void t04OnewayOrder(IBinder orderProxy, Child child) throws Exception {
		final int count = 20;
		for (int i = 0; i < count; ++i) {
			Parcel data = Parcel.obtain();
			try {
				data.writeInt(i);
				check(orderProxy.transact(ChildTransportHost.ORDER, data, null, FLAG_ONEWAY),
				      "oneway order send failed for " + i);
			} finally {
				data.recycle();
			}
		}
		List<Integer> order = null;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(6000);
		do {
			Parcel data = Parcel.obtain();
			Parcel reply = Parcel.obtain();
			try {
				check(orderProxy.transact(ChildTransportHost.READ_ORDER, data, reply, 0),
				      "order read failed");
				int size = reply.readInt();
				if (size == count) {
					order = new ArrayList<>();
					for (int i = 0; i < size; ++i)
						order.add(Integer.valueOf(reply.readInt()));
				}
			} finally {
				data.recycle();
				reply.recycle();
			}
			if (order != null)
				break;
			Thread.sleep(100);
		} while (System.nanoTime() < deadline);
		boolean ordered = order != null;
		for (int i = 0; ordered && i < count; ++i)
			ordered = order.get(i).intValue() == i;
		probeEvent("t04-oneway-order", "received=" + (order == null ? -1 : order.size())
		                                     + ";ordered=" + ordered);
		if (ordered)
			caseMarker("T04", "t04-oneway-order", "count=" + count + " ordered=true");
		else
			caseFail("t04-oneway-order", "received=" + (order == null ? -1 : order.size()));
	}

	void t05FailureDistinction(Child child) throws Exception {
		StringBuilder detail = new StringBuilder();
		// Unknown code: dispatch returns false without an exception.
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		boolean unknownFalse;
		try {
			unknownFalse = !child.root.transact(999, data, reply, 0);
		} finally {
			data.recycle();
			reply.recycle();
		}
		check(unknownFalse, "unknown code should return false");
		detail.append("unknownCode=false");
		// IllegalArgumentException surfaces through readException with the
		// original message.
		IllegalArgumentException illegal = null;
		data = Parcel.obtain();
		reply = Parcel.obtain();
		try {
			child.root.transact(ChildTransportHost.THROW_ILLEGAL, data, reply, 0);
			reply.readException();
		} catch (IllegalArgumentException e) {
			illegal = e;
		} finally {
			data.recycle();
			reply.recycle();
		}
		check(illegal != null && "synthetic-error".equals(illegal.getMessage()),
		      "IllegalArgumentException did not surface intact: " + illegal);
		detail.append(";illegalArgument=synthetic-error");
		// A runtime type the reply channel cannot serialize becomes the
		// remote-exception status 2: still a RuntimeException at the caller,
		// not a false success and not a wedge.
		RuntimeException runtime = null;
		data = Parcel.obtain();
		reply = Parcel.obtain();
		try {
			child.root.transact(ChildTransportHost.THROW_RUNTIME, data, reply, 0);
			reply.readException();
		} catch (RuntimeException e) {
			runtime = e;
		} finally {
			data.recycle();
			reply.recycle();
		}
		check(runtime != null, "unserializable runtime exception did not surface");
		detail.append(";runtime=").append(runtime.getClass().getSimpleName());
		detail.append(";deadLink=exercised-in-t10");
		probeEvent("t05-failure-distinction", detail.toString());
		caseMarker("T05", "t05-failure-distinction", detail.toString());
	}

	void t06Identity(Child child) throws Exception {
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		int callingPid, callingUid, childPid, childChannelPid, childChannelUid;
		try {
			check(child.root.transact(ChildTransportHost.IDENTITY, data, reply, 0),
			      "identity transact failed");
			callingPid = reply.readInt();
			callingUid = reply.readInt();
			childPid = reply.readInt();
			childChannelPid = reply.readInt();
			childChannelUid = reply.readInt();
		} finally {
			data.recycle();
			reply.recycle();
		}
		int myPid = Process.myPid(), myUid = android.os.Process.myUid();
		int realUid = realUid();
		int[] creds = ATLBinderConnection.peerCredentials(child.fd);
		/* W1 (android_os_ATLBinderConnection.c:33, verified on Linux 6.8): a
		 * socketpair records the process that created the pair, so OUR end of a
		 * pair we created reports US - never the child that inherited the other
		 * end. What the channel can therefore prove, kernel-side and
		 * un-spoofable by message fields, is: (a) the child's own pid is the one
		 * spawn() returned and holds the channel, (b) the child's channel view of
		 * the caller is our kernel pid/uid. callingUid equals myUid because ATL
		 * emulates one app uid per process (Binder.atlEnter uses the local
		 * Process.myUid), while peerCredentials reports raw kernel credentials -
		 * which is why the two uids differ here. */
		boolean ok = callingPid == myPid && callingUid == myUid
		             && childPid == child.pid && childChannelPid == myPid
		             && childChannelUid == realUid && creds[0] == myPid
		             && creds[1] == realUid;
		probeEvent("t06-peer-identity", "calling=" + callingPid + "/" + callingUid
		                                  + ";local=" + myPid + "/" + myUid
		                                  + ";realUid=" + realUid
		                                  + ";childPid=" + childPid
		                                  + ";childChannel=" + childChannelPid + "/"
		                                  + childChannelUid
		                                  + ";peerCred=" + creds[0] + "/" + creds[1] + "/" + creds[2]);
		if (ok)
			caseMarker("T06", "t06-peer-identity", "calling=" + callingPid + "/" + callingUid
			                      + " childPid=" + childPid + " childChannel=" + childChannelPid
			                      + "/" + childChannelUid + " realUid=" + realUid);
		else
			caseFail("t06-peer-identity", "calling=" + callingPid + "/" + callingUid
			                                + " childPid=" + childPid
			                                + " childChannel=" + childChannelPid + "/" + childChannelUid
			                                + " realUid=" + realUid
			                                + " peerCred=" + creds[0] + "/" + creds[1]);
	}

	/** Real (kernel) uid of this process: the value SO_PEERCRED carries, as
	 * opposed to ATL's emulated per-app Process.myUid(). */
	static int realUid() throws Exception {
		for (String line : java.nio.file.Files.readAllLines(
		                     java.nio.file.Paths.get("/proc/self/status")))
			if (line.startsWith("Uid:"))
				return Integer.parseInt(line.split("\\s+")[1]);
		throw new IllegalStateException("no Uid: line in /proc/self/status");
	}

	/**
	 * W1: a channel this process created has no kernel-supplied peer, so the
	 * claimed peer pid is cross-checked against /proc (the claimed child must
	 * hold the channel socket at the fd 3 spawn() hands out). A wrong claim is
	 * rejected - the missing half of "identity comes from the channel": the
	 * constructor argument is not trusted on its own.
	 */
	void t06ForgedSpawnPid() throws Exception {
		int[] pair = ATLBinderConnection.socketPair();
		boolean rejected = false;
		String detail;
		try {
			// pid 1 is a live process, but it does not hold a socket at fd 3.
			new ATLBinderConnection(pair[0], 1, null);
			detail = "accepted-claimed-pid-1";
		} catch (IllegalStateException expected) {
			rejected = true;
			detail = "rejected:" + expected.getMessage();
		}
		probeEvent("t06-forged-spawn-pid", detail);
		if (rejected)
			caseMarker("T06", "t06-forged-spawn-pid", detail);
		else
			caseFail("t06-forged-spawn-pid", detail);
	}

	void t06ForgedIdentity() throws Exception {
		Frames.Wire wire = new Frames.Wire();
		try {
			// callChain with a bogus pid field (999999) in a well-formed CALL.
			byte[] record = Frames.forgedChainRecord(0x000f423f00000001L);
			wire.send(record, new int[0]);
			Frames.Obs obs = wire.awaitObserved(699, CASE_WAIT_MS);
			boolean observed = obs != null;
			boolean ignored = observed && obs.callingPid == Process.myPid()
			                  && obs.callingUid == android.os.Process.myUid();
			probeEvent("t06-forged-identity", "observed=" + observed + ";reportedPid="
			                                     + (observed ? obs.callingPid : -1)
			                                     + ";expectedPid=" + Process.myPid());
			if (ignored)
				caseMarker("T06", "t06-forged-identity", "reportedPid=" + obs.callingPid);
			else
				caseFail("t06-forged-identity", "observed=" + observed + " reportedPid="
				                                + (observed ? obs.callingPid : -1));
		} finally {
			wire.dispose();
		}
	}

	void t07FdTransfer(Child child) throws Exception {
		// Push leg 1 (content): the file holds one byte, the sender descriptor
		// is read-only and the parent closes it before the transact, yet the
		// receiver reads the byte. The duplicate Parcel makes at
		// writeFileDescriptor time is what keeps the open file description
		// alive after the sender's own close.
		File push = new File(getFilesDir(), "fd-push.bin");
		FileOutputStream out = new FileOutputStream(push);
		out.write(91);
		out.close();
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			FileInputStream sender = new FileInputStream(push);
			try {
				data.writeFileDescriptor(sender.getFD());
			} finally {
				sender.close(); // early close: sender is gone before the read
			}
			check(child.root.transact(ChildTransportHost.READ_FD, data, reply, 0),
			      "fd push transact failed");
			check(reply.readInt() == 91, "pushed descriptor read the wrong byte");
		} finally {
			data.recycle();
			reply.recycle();
		}
		// Push leg 2 (mode fidelity): this sender descriptor is write-only, so
		// the receiver's read must fail (the child reports -2). A transport
		// that re-opened the path instead of passing the open file description
		// would hand the receiver a readable descriptor and be caught here.
		// Append mode keeps the one byte of leg 1 in place, so a leak would
		// surface as 91 rather than as a clean end of file.
		data = Parcel.obtain();
		reply = Parcel.obtain();
		try {
			FileOutputStream modeSender = new FileOutputStream(push, true);
			try {
				data.writeFileDescriptor(modeSender.getFD());
			} finally {
				modeSender.close();
			}
			check(child.root.transact(ChildTransportHost.READ_FD, data, reply, 0),
			      "fd write-only transact failed");
			check(reply.readInt() == -2,
			      "write-only descriptor was readable at the receiver (access mode not preserved)");
		} finally {
			data.recycle();
			reply.recycle();
		}
		// Pull: child hands a descriptor back; the received fd belongs to the
		// reply Parcel and must be closed by Parcel.recycle, leaving only the
		// caller's own duplicate open.
		data = Parcel.obtain();
		reply = Parcel.obtain();
		try {
			check(child.root.transact(ChildTransportHost.RETURN_FD, data, reply, 0),
			      "fd pull transact failed");
			ParcelFileDescriptor received = reply.readFileDescriptor();
			check(received != null, "no descriptor came back");
			int value = new FileInputStream(received.getFileDescriptor()).read();
			check(value == 73, "pulled descriptor read the wrong byte: " + value);
			File pull = new File(getFilesDir(), "fd-pull.bin");
			String pullPath = pull.getCanonicalPath();
			reply.recycle(); // Parcel-recycle closes the received descriptor
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
			int open;
			do {
				open = fdsPointingAt(pullPath);
				if (open == 1)
					break;
				Thread.sleep(20);
			} while (System.nanoTime() < deadline);
			check(open == 1, "Parcel.recycle did not close the received descriptor: " + open);
			received.close();
			deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
			do {
				open = fdsPointingAt(pullPath);
				if (open == 0)
					break;
				Thread.sleep(20);
			} while (System.nanoTime() < deadline);
			check(open == 0, "caller duplicate leaked");
		} finally {
			data.recycle();
			reply.recycle();
		}
		// Oversized descriptor bundle: rejected at send with every sentinel
		// still open (transport bound 64).
		int[] sentinels = new int[65];
		for (int i = 0; i < 65; ++i)
			sentinels[i] = LinkTools.sentinel();
		byte[] record = Frames.validPlainRecord();
		try {
			LinkTools.rawSend(child.fd, record, sentinels);
			check(false, "65 descriptors must be rejected");
		} catch (Exception expected) {
			check(("" + expected.getMessage()).contains("exceeds"), "wrong reject: " + expected);
		}
		int stillOpen = 0;
		for (int fd : sentinels)
			if (LinkTools.fdExists(fd))
				stillOpen++;
		check(stillOpen == 65, "sentinels disturbed by the rejected send: " + stillOpen);
		for (int fd : sentinels)
			LinkTools.closeFd(fd);
		probeEvent("t07-fd-transfer", "push=91;pushModeWriteOnly=-2;pull=73;parcelRecycle=1"
		                                  + ";oversizedRejected=65;bulkClosure=framing-fd-many-reject");
		caseMarker("T07", "t07-fd-transfer",
		           "push=91 pushModeWriteOnly=-2 pull=73 parcelRecycle=1 oversized=65");
	}

	void t11HandleChurn(Child child, IBinder orderProxy) throws Exception {
		// The order-target export from t03/t04 is released deterministically.
		LinkTools.drop(child.connection, orderProxy, true);
		int base = queryTable(child, -1);
		int h1 = newBinderHandle(child);
		IBinder proxy1 = child.lastNewBinder;
		check(queryTable(child, base + 1) == base + 1, "export table did not grow");
		int h2 = newBinderHandle(child);
		IBinder proxy2 = child.lastNewBinder;
		check(queryTable(child, base + 2) == base + 2, "export table did not grow twice");
		check(h2 > h1, "handles not monotonic: " + h1 + " then " + h2);
		// The second proxy still answers while it is referenced.
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			data.writeInt(21);
			check(proxy2.transact(2, data, reply, 0), "fresh binder did not answer");
			check(reply.readInt() == 42, "fresh binder wrong reply");
		} finally {
			data.recycle();
			reply.recycle();
		}
		// Release reclaims exactly the handle it names: the other new export is
		// still pinned, so the table stops one entry above the base (the release
		// is per reference, not a table sweep).
		LinkTools.drop(child.connection, proxy2, true);
		check(queryTable(child, base + 1) == base + 1,
		      "releasing one of two handles did not reclaim exactly one entry");
		LinkTools.drop(child.connection, proxy1, true);
		check(queryTable(child, base) == base, "table did not reclaim after second drop");
		// After reclamation the next handle is still monotonic, never reused.
		int h3 = newBinderHandle(child);
		IBinder proxy3 = child.lastNewBinder;
		check(h3 > h2, "handle reused after reclamation: " + h3 + " after " + h2);
		LinkTools.drop(child.connection, proxy3, true);
		check(queryTable(child, base) == base, "table did not reclaim after third drop");
		probeEvent("t11-handle-churn", "handles=" + h1 + "," + h2 + "," + h3 + ";base=" + base);
		caseMarker("T11", "t11-handle-churn", "handles=" + h1 + "," + h2 + "," + h3
		                      + " base=" + base);
	}

	int queryTable(Child child, int expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CASE_WAIT_MS);
		int size = -1;
		do {
			Parcel data = Parcel.obtain();
			Parcel reply = Parcel.obtain();
			try {
				check(child.root.transact(ChildTransportHost.TABLE_SIZE, data, reply, 0),
				      "table size transact failed");
				size = reply.readInt();
			} finally {
				data.recycle();
				reply.recycle();
			}
			if (expected < 0 || size == expected)
				return size;
			Thread.sleep(20);
		} while (System.nanoTime() < deadline);
		return size;
	}

	int newBinderHandle(Child child) throws Exception {
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			check(child.root.transact(ChildTransportHost.NEW_BINDER, data, reply, 0),
			      "new binder transact failed");
			child.lastNewBinder = reply.readStrongBinder();
			return LinkTools.handleOf(child.lastNewBinder);
		} finally {
			data.recycle();
			reply.recycle();
		}
	}

	void t10DeathWake(Child child) throws Exception {
		final int waiters = 8;
		final List<Throwable> outcomes = new ArrayList<>();
		final CountDownLatch launched = new CountDownLatch(waiters);
		Thread[] pool = new Thread[waiters];
		for (int t = 0; t < waiters; ++t) {
			pool[t] = new Thread(new Runnable() {
				public void run() {
					Parcel data = Parcel.obtain();
					Parcel reply = Parcel.obtain();
					try {
						launched.countDown();
						child.root.transact(ChildTransportHost.BLOCK_FOREVER, data, reply, 0);
						synchronized (outcomes) {
							outcomes.add(new IllegalStateException("blocked call returned"));
						}
					} catch (Throwable e) {
						synchronized (outcomes) {
							outcomes.add(e);
						}
					} finally {
						data.recycle();
						reply.recycle();
					}
				}
			}, "trn-t10-" + t);
			pool[t].start();
		}
		check(launched.await(10, TimeUnit.SECONDS), "waiters never launched");
		// All eight are now outstanding on the connection.
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10000);
		while (LinkTools.pendingSize(child.connection) != waiters
		       && System.nanoTime() < deadline)
			Thread.sleep(20);
		check(LinkTools.pendingSize(child.connection) == waiters,
		      "waiters not all pending: " + LinkTools.pendingSize(child.connection));
		final IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
			public void binderDied() {
				deathRecipientCalls++;
			}
		};
		child.root.linkToDeath(recipient, 0);
		Thread.sleep(300); // the kill must beat the deadline wake
		kill(child.pid);
		long joinDeadline = deadlineMs + 5000;
		for (int t = 0; t < waiters; ++t)
			pool[t].join(joinDeadline);
		int deadObject = 0, other = 0;
		synchronized (outcomes) {
			for (Throwable t : outcomes) {
				// Two wordings are legitimate and both are the documented kill
				// semantics: the reader detects the peer's death ("ATL IPC peer
				// disconnected") for calls already outstanding, while a call
				// that sends after the link died is refused by the send path
				// ("ATL IPC peer is dead"). Only the type and the peer-anchored
				// wording are contract; the exact sentence is not.
				if (t instanceof DeadObjectException
				    && ("" + t.getMessage()).contains("peer"))
					deadObject++;
				else
					other++;
			}
		}
		check(child.death.await(5, TimeUnit.SECONDS), "onDeath listener never fired");
		check(deathRecipientCalls >= 1, "linkToDeath recipient never fired");
		check(other == 0, "waiters woke with wrong failures: " + other);
		// A dead link stays dead: distinct from every failure above.
		// (The checked DeadObjectException crosses IBinder.transact via the
		// connection's generic raise() bridge, so catch Exception + instanceof.)
		{
			Parcel data = Parcel.obtain();
			Parcel reply = Parcel.obtain();
			try {
				child.root.transact(ChildTransportHost.PING, data, reply, 0);
				check(false, "transact on a dead link returned");
			} catch (Exception expected) {
				check(expected instanceof DeadObjectException,
				      "dead-link exception is not DeadObject: " + expected);
				check((("" + expected.getMessage()).contains("peer")),
				      "dead-link exception carries the wrong message: " + expected);
			} finally {
				data.recycle();
				reply.recycle();
			}
		}
		int blocked = countExecLog("code=13 blocking");
		probeEvent("t10-death-wake", "waiters=" + waiters + ";deadObject=" + deadObject
		                                        + ";other=" + other
		                                        + ";deathListener=" + child.deathListenerFired
		                                        + ";recipients=" + deathRecipientCalls
		                                        + ";childBlocked=" + blocked);
		caseMarker("T10", "t10-death-wake", "waiters=" + waiters + " deadObject=" + deadObject
		                     + " recipients=" + deathRecipientCalls);
	}

	void t12ReleaseVsDeathRace(Child child2) throws Exception {
		// Two live exports of child2: one is explicitly RELEASEd while a kill -9
		// races it, the other is held for the whole test. §3 owes a death
		// notification to every proxy the connection still tracks, so the held
		// proxy's recipient must fire whatever the racer does; the released
		// proxy is dropped from the proxy table by RELEASE (exactly-once, see
		// t11) and therefore is not owed one. The release itself must settle
		// either cleanly or as a DeadObjectException naming the dead link -
		// never a hang and never an unrelated failure.
		newBinderHandle(child2);
		final IBinder racing = child2.lastNewBinder;
		newBinderHandle(child2);
		final IBinder held = child2.lastNewBinder;
		final AtomicInteger heldNotices = new AtomicInteger();
		final AtomicInteger racingNotices = new AtomicInteger();
		held.linkToDeath(new IBinder.DeathRecipient() {
			public void binderDied() {
				heldNotices.incrementAndGet();
			}
		}, 0);
		racing.linkToDeath(new IBinder.DeathRecipient() {
			public void binderDied() {
				racingNotices.incrementAndGet();
			}
		}, 0);
		final AtomicReference<String> winner = new AtomicReference<String>("none");
		final AtomicReference<String> releaseOutcome = new AtomicReference<String>("not-run");
		Thread releaser = new Thread(new Runnable() {
			public void run() {
				try {
					LinkTools.drop(child2.connection, racing, true);
					releaseOutcome.set("clean");
				} catch (DeadObjectException e) {
					releaseOutcome.set("dead-link");
				} catch (Throwable t) {
					releaseOutcome.set("fault:" + t);
				} finally {
					winner.compareAndSet("none", "release");
				}
			}
		}, "trn-t12-release");
		Thread killer = new Thread(new Runnable() {
			public void run() {
				try {
					Thread.sleep(new Random().nextInt(40));
					kill(child2.pid);
					winner.compareAndSet("none", "death");
				} catch (Throwable t) {
					winner.compareAndSet("none", "kill-fault:" + t);
				}
			}
		}, "trn-t12-kill");
		releaser.start();
		killer.start();
		releaser.join(deadlineMs + 8000);
		killer.join(15000);
		check(releaser.getState() == Thread.State.TERMINATED, "RELEASE never settled");
		check(killer.getState() == Thread.State.TERMINATED, "kill never settled");
		check(child2.death.await(5, TimeUnit.SECONDS), "second child link death not observed");
		check(heldNotices.get() >= 1, "held proxy did not learn of the exporting process' death");
		String outcome = releaseOutcome.get();
		check("clean".equals(outcome) || "dead-link".equals(outcome),
		      "release-vs-death released with an unexpected outcome: " + outcome);
		// A proxy that witnessed the death must never transact again.
		{
			Parcel data = Parcel.obtain();
			Parcel reply = Parcel.obtain();
			try {
				held.transact(ChildTransportHost.PING, data, reply, 0);
				check(false, "held proxy transacted on a dead link");
			} catch (Exception expected) {
				check(expected instanceof DeadObjectException,
				      "held proxy dead-link exception is not DeadObject: " + expected);
			} finally {
				data.recycle();
				reply.recycle();
			}
		}
		probeEvent("t12-release-vs-death-race", "winner=" + winner + ";release=" + outcome
		                                          + ";heldNotices=" + heldNotices.get()
		                                          + ";releasedNotices=" + racingNotices.get()
		                                          + ";releaserDone="
		                                          + (releaser.getState() == Thread.State.TERMINATED));
		caseMarker("T12", "t12-release-vs-death-race", "winner=" + winner + " release=" + outcome
		                            + " heldNotices=" + heldNotices.get());
	}

	void t09SaturationAndDeadline(ParentCallback callback) throws Exception {
		ProbeServer server = new ProbeServer(this);
		int[] pair = ATLBinderConnection.socketPair();
		final ATLBinderConnection client =
			new ATLBinderConnection(pair[0], -1, callback);
		final ATLBinderConnection serverConn =
			new ATLBinderConnection(pair[1], -1, server);
		client.start();
		serverConn.start();
		IBinder svc = client.remote(1);

		// Saturation: 256 outstanding calls are carried; the 257th is refused.
		final int saturation = 256;
		final CountDownLatch gate = new CountDownLatch(1);
		server.gate = gate;
		final AtomicInteger mismatches = new AtomicInteger();
		final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
		Thread[] pool = new Thread[saturation];
		for (int t = 0; t < saturation; ++t) {
			final int id = t;
			pool[t] = new Thread(new Runnable() {
				public void run() {
					Parcel data = Parcel.obtain();
					Parcel reply = Parcel.obtain();
					try {
						data.writeInt(id);
						if (!svc.transact(ProbeServer.GATE, data, reply, 0)
						    || reply.readInt() != id)
							mismatches.incrementAndGet();
					} catch (Throwable e) {
						firstFailure.compareAndSet(null, e);
						mismatches.incrementAndGet();
					} finally {
						data.recycle();
						reply.recycle();
					}
				}
			}, "trn-t09-" + t);
			pool[t].start();
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(15000);
		while (LinkTools.pendingSize(client) < saturation && System.nanoTime() < deadline)
			Thread.sleep(20);
		probeEvent("t09-saturation-diag", "pending=" + LinkTools.pendingSize(client)
		        + ";alive=" + svc.isBinderAlive()
		        + ";serverExports=" + LinkTools.exportTableSize(serverConn)
		        + ";gateStart=" + gateStart.get()
		        + ";mismatches=" + mismatches.get()
		        + ";first=" + firstFailure.get());
		check(LinkTools.pendingSize(client) == saturation,
		      "pending never saturated: " + LinkTools.pendingSize(client));
		boolean refused = false;
		Parcel data = Parcel.obtain();
		Parcel reply = Parcel.obtain();
		try {
			svc.transact(ProbeServer.GATE, data, reply, 0);
		} catch (IllegalStateException e) {
			refused = ("" + e.getMessage()).contains("Too many outstanding");
		} finally {
			data.recycle();
			reply.recycle();
		}
		check(refused, "257th outstanding call was not refused");
		gate.countDown();
		for (int t = 0; t < saturation; ++t)
			pool[t].join(60000);
		check(gateStart.get() == saturation,
		      "server carried " + gateStart.get() + " of " + saturation + " saturated calls");
		check(mismatches.get() == 0, "saturated calls lost payloads: " + mismatches);

		// Delayed reply within the deadline succeeds.
		long start = System.nanoTime();
		data = Parcel.obtain();
		reply = Parcel.obtain();
		try {
			check(svc.transact(ProbeServer.DELAY_400, data, reply, 0),
			      "delayed reply call failed");
			check(reply.readInt() == 42, "delayed reply payload wrong");
		} finally {
			data.recycle();
			reply.recycle();
		}
		long delayedMs = (System.nanoTime() - start) / 1000000L;
		check(delayedMs >= 350 && delayedMs < deadlineMs, "delayed reply timing: " + delayedMs);

		// Timeout: the caller wakes with DeadObjectException, the link dies,
		// and the server executes exactly once and is never retried.
		long delay = deadlineMs + 3000;
		delayLongStart.set(0);
		delayLongEnd.set(0);
		start = System.nanoTime();
		data = Parcel.obtain();
		reply = Parcel.obtain();
		long timeoutElapsed = -1;
		try {
			data.writeLong(delay);
			svc.transact(ProbeServer.DELAY_LONG, data, reply, 0);
			check(false, "timed-out call returned");
		} catch (Exception e) {
			timeoutElapsed = (System.nanoTime() - start) / 1000000L;
			check(e instanceof DeadObjectException,
			      "timeout exception is not DeadObject: " + e);
			check(("" + e.getMessage()).contains("timed out"),
			      "timeout exception is not the deadline: " + e);
		} finally {
			data.recycle();
			reply.recycle();
		}
		check(timeoutElapsed >= deadlineMs - 2000 && timeoutElapsed <= deadlineMs + 5000,
		      "timeout fired at the wrong time: " + timeoutElapsed + "ms");
		check(!svc.isBinderAlive(), "link survived the timeout");
		deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay + 15000);
		while ((delayLongStart.get() != 1 || delayLongEnd.get() != 1)
		       && System.nanoTime() < deadline)
			Thread.sleep(100);
		check(delayLongStart.get() == 1, "timeout call executed " + delayLongStart.get()
		                                + " times (never-retry violated)");
		check(delayLongEnd.get() == 1, "server never completed the abandoned call");
		probeEvent("t09-saturation-deadline", "saturation=" + saturation
		                                            + ";refused=true;delayedMs=" + delayedMs
		                                            + ";timeoutMs=" + timeoutElapsed
		                                            + ";executed=" + delayLongStart.get()
		                                            + ";completed=" + delayLongEnd.get());
		caseMarker("T09", "t09-saturation-deadline", "saturation=256 refused=true delayedMs="
		                            + delayedMs + " timeoutMs=" + timeoutElapsed
		                            + " executedOnce=1");
	}

	/**
	 * The other half of saturation: a link that dies while dispatch work is still
	 * queued must abandon that work exactly once. Every queued request owns the
	 * descriptors it received, so a request that is neither run nor recycled
	 * shows up as a descriptor leak - and a request that is recycled twice would
	 * close a descriptor someone else now owns.
	 */
	void t09bCloseUnderLoad(ParentCallback callback) throws Exception {
		ProbeServer server = new ProbeServer(this);
		int[] pair = ATLBinderConnection.socketPair();
		ATLBinderConnection client = new ATLBinderConnection(pair[0], -1, callback);
		ATLBinderConnection serverConn = new ATLBinderConnection(pair[1], -1, server);
		final CountDownLatch gate = new CountDownLatch(1);
		server.gate = gate;
		client.start();
		serverConn.start();
		IBinder svc = client.remote(1);
		final File payload = new File(getFilesDir(), "t09b-payload.bin");
		payload.createNewFile();
		final int calls = 40; // 16 run, the rest queue behind the blocked handlers
		gateStart.set(0);
		final int baseFds = LinkTools.fdCount();
		Thread[] pool = new Thread[calls];
		for (int t = 0; t < calls; ++t) {
			pool[t] = new Thread(new Runnable() {
				public void run() {
					FileInputStream stream = null;
					Parcel data = Parcel.obtain();
					Parcel reply = Parcel.obtain();
					try {
						stream = new FileInputStream(payload);
						data.writeInt(1);
						data.writeFileDescriptor(stream.getFD());
						svc.transact(ProbeServer.GATE, data, reply, 0);
					} catch (Throwable expectedOnClose) {
						// the link dies under load: a DeadObjectException is the
						// contract for all of them, recorded by the case below
					} finally {
						try {
							if (stream != null)
								stream.close();
						} catch (Exception ignored) {
						}
						data.recycle();
						reply.recycle();
					}
				}
			}, "trn-t09b-" + t);
			pool[t].start();
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(15000);
		while (gateStart.get() < 16 && System.nanoTime() < deadline)
			Thread.sleep(20);
		check(gateStart.get() >= 16, "workers never saturated: " + gateStart.get());
		int carried = LinkTools.pendingSize(client);
		check(carried >= 16, "calls were not carried, only " + carried + " pending");
		serverConn.close(); // link death with dispatch work still queued
		gate.countDown();   // release the blocked handlers so their requests settle
		for (int t = 0; t < calls; ++t)
			pool[t].join(30000);
		int stuck = 0;
		for (int t = 0; t < calls; ++t)
			if (pool[t].getState() != Thread.State.TERMINATED)
				++stuck;
		check(stuck == 0, "callers never settled after the link died: " + stuck);
		deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(10000);
		while (LinkTools.fdCount() > baseFds && System.nanoTime() < deadline)
			Thread.sleep(50);
		int leaked = LinkTools.fdCount() - baseFds;
		probeEvent("t09b-close-under-load", "calls=" + calls + ";carried=" + carried
		        + ";handlers=" + gateStart.get() + ";base=" + baseFds + ";leaked=" + leaked);
		check(leaked <= 0, "queued requests leaked " + leaked + " descriptors");
		caseMarker("T09", "t09b-close-under-load", "calls=" + calls + " carried=" + carried
		                            + " handlers=" + gateStart.get() + " leaked=" + leaked);
	}

	void redBaseline(ParentCallback callback) throws Exception {
		// ---------------- RED: IPCConnection-family link semantics.
		RedLink[] pair = RedLink.pair();
		final RedLink client = pair[0], server = pair[1];
		RedServer redServer = new RedServer();
		redServer.link = server;
		int serverHandle = server.export(redServer);
		int callbackHandle = client.export(callback);

		// concurrent sync: serialized by the request lock.
		final int threads = 4;
		final AtomicInteger mismatches = new AtomicInteger();
		Thread[] pool = new Thread[threads];
		long start = System.nanoTime();
		for (int t = 0; t < threads; ++t) {
			final int id = t;
			pool[t] = new Thread(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < 2; ++i) {
							Parcel data = Parcel.obtain();
							Parcel reply = Parcel.obtain();
							try {
								data.writeInt(1000 * id + i);
								if (!client.proxyFor(serverHandle)
								         .transact(RedServer.ECHO, data, reply, 0)
								    || reply.readInt() != 1000 * id + i)
									mismatches.incrementAndGet();
							} finally {
								data.recycle();
								reply.recycle();
							}
						}
					} catch (Throwable e) {
						mismatches.incrementAndGet();
					}
				}
			}, "trn-red-concurrent-" + t);
			pool[t].start();
		}
		for (int t = 0; t < threads; ++t)
			pool[t].join(30000);
		long concurrentMs = (System.nanoTime() - start) / 1000000L;
		int redOverlap = redServer.maxOverlap();
		boolean concurrentRed = redOverlap == 1 && mismatches.get() == 0
		                        && concurrentMs >= 2 * RedServer.ECHO_SLEEP_MS;

		// oneway: the flags are ignored, the call blocks for the handler.
		redServer.reset();
		start = System.nanoTime();
		Parcel data = Parcel.obtain();
		try {
			client.proxyFor(serverHandle).transact(RedServer.SLEEP_ONEWAY, data, null,
			                                       FLAG_ONEWAY);
		} catch (Throwable ignored) {
		} finally {
			data.recycle();
		}
		long onewayMs = (System.nanoTime() - start) / 1000000L;
		boolean onewayRed = onewayMs >= RedServer.SLEEP_ONEWAY_MS;

		// nested reentrancy: the callback calls back into the same link space;
		// IPCConnection semantics wedge; the watchpoint records the deadlock.
		final AtomicReference<Throwable> nestedOutcome = new AtomicReference<Throwable>();
		Thread nested = new Thread(new Runnable() {
			public void run() {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInt(7);
					data.writeInt(callbackHandle);
					client.proxyFor(serverHandle).transact(RedServer.NESTED, data, reply, 0);
				} catch (Throwable t) {
					nestedOutcome.set(t);
				} finally {
					data.recycle();
					reply.recycle();
				}
			}
		}, "trn-red-nested");
		nested.start();
		nested.join(RedLink.WATCHPOINT_MS + 10000);
		boolean nestedDone = nested.getState() == Thread.State.TERMINATED
		                     && nestedOutcome.get() == null;
		boolean nestedRed = !nestedDone && client.wedged();
		event("redlink:red-concurrent", "verdict=" + (concurrentRed ? "RED" : "NOT-RED")
		                                     + ";overlap=" + redOverlap + ";wallMs=" + concurrentMs
		                                     + ";mismatches=" + mismatches);
		event("redlink:red-oneway", "verdict=" + (onewayRed ? "RED" : "NOT-RED")
		                                 + ";elapsedMs=" + onewayMs);
		event("redlink:red-nested", "verdict=" + (nestedRed ? "RED" : "NOT-RED")
		                                  + ";deadlock=" + !nestedDone
		                                  + ";watchpoint=" + client.wedged());
		marker("REDLINK BASELINE RED concurrent=" + (concurrentRed ? "RED" : "NOT-RED")
		       + " overlap=" + redOverlap + " wallMs=" + concurrentMs
		       + " oneway=" + (onewayRed ? "RED" : "NOT-RED") + " elapsedMs=" + onewayMs
		       + " nested=" + (nestedRed ? "RED" : "NOT-RED"));
		client.forceClose();
		server.forceClose();
		nested.join(2000);

		// ---------------- GREEN: the same workloads on the production link.
		GreenServer greenServer = new GreenServer();
		int[] prodPair = ATLBinderConnection.socketPair();
		final ATLBinderConnection prodClient =
			new ATLBinderConnection(prodPair[0], -1, callback);
		final ATLBinderConnection prodServerConn =
			new ATLBinderConnection(prodPair[1], -1, greenServer);
		prodClient.start();
		prodServerConn.start();
		IBinder greenSvc = prodClient.remote(1);

		final AtomicInteger greenMismatches = new AtomicInteger();
		Thread[] greenPool = new Thread[threads];
		start = System.nanoTime();
		for (int t = 0; t < threads; ++t) {
			final int id = t;
			greenPool[t] = new Thread(new Runnable() {
				public void run() {
					try {
						for (int i = 0; i < 2; ++i) {
							Parcel data = Parcel.obtain();
							Parcel reply = Parcel.obtain();
							try {
								data.writeInt(1000 * id + i);
								if (!greenSvc.transact(GreenServer.ECHO, data, reply, 0)
								    || reply.readInt() != 1000 * id + i)
									greenMismatches.incrementAndGet();
							} finally {
								data.recycle();
								reply.recycle();
							}
						}
					} catch (Throwable e) {
						greenMismatches.incrementAndGet();
					}
				}
			}, "trn-green-concurrent-" + t);
			greenPool[t].start();
		}
		for (int t = 0; t < threads; ++t)
			greenPool[t].join(30000);
		long greenConcurrentMs = (System.nanoTime() - start) / 1000000L;
		int greenOverlap = greenServer.maxOverlap();
		boolean concurrentGreen = greenOverlap >= 2 && greenMismatches.get() == 0;

		start = System.nanoTime();
		Parcel greenData = Parcel.obtain();
		try {
			check(greenSvc.transact(GreenServer.SLEEP_ONEWAY, greenData, null, FLAG_ONEWAY),
			      "green oneway send failed");
		} finally {
			greenData.recycle();
		}
		long greenOnewayMs = (System.nanoTime() - start) / 1000000L;
		boolean onewayGreen = greenOnewayMs < 300;

		// Nested: the callback runs on the waiting caller's thread and the
		// whole cycle completes.
		nestedCallerThread = -1;
		nestedCallbackThread = -1;
		final AtomicReference<String> nestedGreen = new AtomicReference<String>("unset");
		Thread greenNested = new Thread(new Runnable() {
			public void run() {
				Parcel data = Parcel.obtain();
				Parcel reply = Parcel.obtain();
				try {
					data.writeInt(7);
					data.writeStrongBinder(callback);
					if (!greenSvc.transact(GreenServer.NESTED, data, reply, 0))
						nestedGreen.set("dispatch-false");
					else if (reply.readInt() != 42)
						nestedGreen.set("value-lost");
					else if (reply.readStrongBinder() != (IBinder)callback)
						nestedGreen.set("identity-lost");
					else
						nestedGreen.set("ok");
				} catch (Throwable t) {
					nestedGreen.set("" + t);
				} finally {
					data.recycle();
					reply.recycle();
				}
			}
		}, "trn-green-nested");
		long greenCaller = Thread.currentThread().getId();
		greenNested.start();
		greenNested.join(15000);
		boolean nestedGreenOk = "ok".equals(nestedGreen.get())
		                        && nestedCallbackThread == greenNested.getId();
		event("redlink:green-concurrent", "verdict=" + (concurrentGreen ? "GREEN" : "FAIL")
		                                      + ";overlap=" + greenOverlap
		                                      + ";wallMs=" + greenConcurrentMs
		                                      + ";mismatches=" + greenMismatches);
		event("redlink:green-oneway", "verdict=" + (onewayGreen ? "GREEN" : "FAIL")
		                                    + ";elapsedMs=" + greenOnewayMs);
		event("redlink:green-nested", "verdict=" + (nestedGreenOk ? "GREEN" : "FAIL")
		                                    + ";detail=" + nestedGreen
		                                    + ";callbackThread=" + nestedCallbackThread);
		prodClient.close();
		prodServerConn.close();

		boolean red = concurrentRed && onewayRed && nestedRed;
		boolean green = concurrentGreen && onewayGreen && nestedGreenOk;
		marker("TRANSPORT WORKLOAD GREEN concurrent=" + greenOverlap
		       + " onewayMs=" + greenOnewayMs + " nested=" + nestedGreen);
		if (red && green) {
			marker("T14 REDLINK PASSED red=1 green=1");
			probeEvent("t14-redlink", "red=1;green=1");
		} else {
			caseFail("t14-redlink", "red=" + red + " green=" + green
			                        + " concurrent=" + concurrentRed + "/" + concurrentGreen
			                        + " oneway=" + onewayRed + "/" + onewayGreen
			                        + " nested=" + nestedRed + "/" + nestedGreenOk);
		}
	}

	void checkFrameBattery() {
		List<String> requiredReject = java.util.Arrays.asList(
			"truncated-header", "truncated-binder-table", "binder-count-overflow",
			"binder-count-negative", "fd-count-negative", "fd-count-huge",
			"fd-count-over-max", "fd-table-past-frame", "length-past-record",
			"short-record", "fd-offset-negative", "fd-offset-past-body",
			"fd-offsets-overlap", "fd-offsets-overlap-range", "fd-not-received",
			"fd-partially-received", "bad-magic", "trailing-bytes",
			"unknown-target", "fd-many-reject", "invalid-fd");
		List<String> requiredAccept = java.util.Arrays.asList(
			"fd-offset-odd", "valid-control", "peer-end-closed");
		List<String[]> snapshot;
		synchronized (events) {
			snapshot = new ArrayList<>(events);
		}
		int rejectOk = 0, acceptOk = 0, bad = 0;
		StringBuilder badOnes = new StringBuilder();
		for (String[] ev : snapshot) {
			String name = ev[0], ret = ev[1];
			if (name.indexOf(':') >= 0)
				continue; // probeCase:/redlink: rows are not framing cases
			String verdict = field(ret, "verdict");
			if (name.equals("concurrent")) {
				if ("OK".equals(verdict))
					rejectOk++;
				else {
					bad++;
					badOnes.append(name).append(' ');
				}
				continue;
			}
			if (name.equals("teardown")) {
				if ("OK".equals(verdict))
					acceptOk++;
				else {
					bad++;
					badOnes.append(name).append(' ');
				}
				continue;
			}
			if (requiredReject.contains(name)) {
				if ("REJECT".equals(verdict) && "accept".equals(field(ret, "validNext")))
					rejectOk++;
				else {
					bad++;
					badOnes.append(name).append(' ');
				}
			} else if (requiredAccept.contains(name)) {
				if ("ACCEPT".equals(verdict) && "accept".equals(field(ret, "validNext")))
					acceptOk++;
				else {
					bad++;
					badOnes.append(name).append(' ');
				}
			}
		}
		boolean ok = bad == 0 && rejectOk >= requiredReject.size()
		             && acceptOk >= requiredAccept.size() + 1; // +1: teardown
		probeEvent("t08-framing-battery", "rejectOk=" + rejectOk + "/"
		                                     + requiredReject.size()
		                                     + ";acceptOk=" + acceptOk + "/"
		                                     + (requiredAccept.size() + 1)
		                                     + ";bad=" + badOnes);
		if (ok)
			marker("TRANSPORT FRAMES PASSED reject=" + rejectOk + " accept=" + acceptOk);
		else {
			caseFail("t08-framing-battery", "rejectOk=" + rejectOk + " acceptOk=" + acceptOk
			                                + " bad=" + badOnes);
			check(false, "framing battery incomplete: rejectOk=" + rejectOk + "/"
			             + requiredReject.size() + " acceptOk=" + acceptOk + "/"
			             + (requiredAccept.size() + 1) + " bad=" + badOnes);
		}
	}

	void checkInventory() {
		// Every case the run is supposed to have executed, so a suite that silently
		// loses a row cannot report PASSED: rows carry details after the case name,
		// so these are prefixes, matched against the recorded marker lines.
		String[] required = {
			"TRANSPORT PASS t01-concurrent-sync",
			"TRANSPORT PASS t02-nested-reentrancy",
			"TRANSPORT PASS t03-oneway-nonblocking",
			"TRANSPORT PASS t04-oneway-order",
			"TRANSPORT PASS t05-failure-distinction",
			"TRANSPORT PASS t06-peer-identity",
			"TRANSPORT PASS t06-forged-identity",
			"TRANSPORT PASS t06-forged-spawn-pid",
			"TRANSPORT PASS t07-fd-transfer",
			"TRANSPORT PASS t09-saturation-deadline",
			"TRANSPORT PASS t09b-close-under-load",
			"TRANSPORT PASS t10-death-wake",
			"TRANSPORT PASS t11-handle-churn",
			"TRANSPORT PASS t12-release-vs-death-race",
			"TRANSPORT FRAMES PASSED",
			"T14 REDLINK PASSED",
		};
		List<String> snapshot;
		synchronized (markers) {
			snapshot = new ArrayList<>(markers);
		}
		int present = 0;
		StringBuilder missing = new StringBuilder();
		for (String marker : required) {
			boolean found = false;
			for (String line : snapshot) {
				if (line.startsWith(marker)) {
					found = true;
					break;
				}
			}
			if (found)
				present++;
			else
				missing.append(marker).append(' ');
		}
		probeEvent("t13-inventory", "present=" + present + "/" + required.length
		                                 + ";missing=" + missing);
		if (present == required.length)
			marker("TRANSPORT INVENTORY COMPLETE " + present + "/" + required.length);
		else {
			caseFail("t13-inventory", "missing " + missing);
			check(false, "T13 inventory incomplete: " + present + "/" + required.length
			             + " missing " + missing);
		}
	}

	// ---------------------------------------------------------------- helpers

	static String field(String ret, String name) {
		for (String part : ret.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith(name + "="))
				return trimmed.substring(name.length() + 1);
		}
		return "";
	}

	static void check(boolean condition, String what) throws AssertionError {
		if (!condition)
			throw new AssertionError(what);
	}

	void kill(int pid) throws Exception {
		Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
		// Reap the spawn from a housekeeping thread; the battery does not
		// depend on waitpid completing.
		Thread reaper = new Thread(new Runnable() {
			public void run() {
				ATLBinderConnection.reap(pid);
			}
		}, "trn-reap-" + pid);
		reaper.setDaemon(true);
		reaper.start();
	}

	int countExecLog(String needle) {
		try {
			File log = new File(getFilesDir(), ChildTransportHost.EXEC_LOG);
			if (!log.exists())
				return 0;
			FileInputStream in = new FileInputStream(log);
			try {
				byte[] all = new byte[(int)log.length()];
				int read = in.read(all);
				String text = new String(all, 0, Math.max(read, 0), "UTF-8");
				int count = 0, at = 0;
				while ((at = text.indexOf(needle, at)) >= 0) {
					count++;
					at += needle.length();
				}
				return count;
			} finally {
				in.close();
			}
		} catch (Exception e) {
			return -1;
		}
	}

	int fdsPointingAt(String path) {
		File dir = new File("/proc/self/fd");
		String[] entries = dir.list();
		if (entries == null)
			return -1;
		int count = 0;
		for (String entry : entries) {
			try {
				// getCanonicalPath resolves the /proc/self/fd symlink
				String target = new File("/proc/self/fd/" + entry).getCanonicalPath();
				if (path.equals(target))
					count++;
			} catch (Exception ignored) {
				// entry vanished or is not a file: not counted
			}
		}
		return count;
	}

	void writeResults(boolean pass) {
		try {
			StringBuilder json = new StringBuilder();
			json.append("{\n");
			json.append("  \"probe\": \"transport-battery\",\n");
			json.append("  \"component\": \"android.os.ATLBinderConnection\",\n");
			json.append("  \"adaptedFrom\": [\"Firefox IPCConnection b51e45f (IpcFrames, SVC-021)\", \"PR31 baa9b489 (remote-services)\"],\n");
			json.append("  \"deadlineMs\": ").append(deadlineMs).append(",\n");
			json.append("  \"pass\": ").append(pass).append(",\n");
			json.append("  \"events\": [\n");
			synchronized (events) {
				for (int i = 0; i < events.size(); ++i) {
					String[] ev = events.get(i);
					json.append("    {\"event\": \"ipcFrame\", \"cmd\": ")
					   .append(quote(ev[0])).append(", \"ret\": ").append(quote(ev[1]))
					   .append('}');
					if (i + 1 < events.size())
						json.append(',');
					json.append('\n');
				}
			}
			json.append("  ],\n  \"markers\": [\n");
			synchronized (markers) {
				for (int i = 0; i < markers.size(); ++i) {
					json.append("    ").append(quote(markers.get(i)));
					if (i + 1 < markers.size())
						json.append(',');
					json.append('\n');
				}
			}
			json.append("  ],\n  \"failures\": [\n");
			synchronized (failures) {
				for (int i = 0; i < failures.size(); ++i) {
					json.append("    ").append(quote(failures.get(i)));
					if (i + 1 < failures.size())
						json.append(',');
					json.append('\n');
				}
			}
			json.append("  ]\n}\n");
			File results = new File(getFilesDir(), "results.json");
			FileWriter writer = new FileWriter(results, false);
			try {
				writer.write(json.toString());
			} finally {
				writer.close();
			}
			System.out.println("TRANSPORT RESULTS WRITTEN " + results.getAbsolutePath());
		} catch (Throwable t) {
			System.out.println("TRANSPORT RESULTS WRITE FAILED " + t);
		}
	}

	static String quote(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			if (c == '"' || c == '\\')
				out.append('\\').append(c);
			else if (c == '\n')
				out.append("\\n");
			else if (c == '\r')
				out.append("\\r");
			else if (c == '\t')
				out.append("\\t");
			else if (c < 32)
				out.append(String.format("\\u%04x", (int)c));
			else
				out.append(c);
		}
		return out.append('"').toString();
	}

	// -------------------------------------------------------------- binders

	/** Parent-side callback: the child's NESTED case calls back into it. */
	static final class ParentCallback extends Binder {
		final TransportProbe probe;
		ParentCallback(TransportProbe probe) {
			this.probe = probe;
		}
		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			if (code == 99) {
				int value = data.readInt();
				probe.nestedCallbackThread = (int)Thread.currentThread().getId();
				if (reply != null)
					reply.writeInt(value + 1);
				return true;
			}
			return false;
		}
	}

	/** In-process T09 server. */
	static final class ProbeServer extends Binder {
		static final int ECHO = 2, GATE = 21, DELAY_400 = 22, DELAY_LONG = 23;
		final TransportProbe probe;
		volatile CountDownLatch gate = new CountDownLatch(1);
		ProbeServer(TransportProbe probe) {
			this.probe = probe;
		}
		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			try {
				return serve(code, data, reply, flags);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		private boolean serve(int code, Parcel data, Parcel reply, int flags)
		        throws Exception {
			switch (code) {
				case GATE: {
					int id = data.readInt();
					probe.gateStart.incrementAndGet();
					gate.await(60, TimeUnit.SECONDS);
					if (reply != null)
						reply.writeInt(id);
					return true;
				}
				case DELAY_400:
					Thread.sleep(400);
					if (reply != null)
						reply.writeInt(42);
					return true;
				case DELAY_LONG: {
					long delay = data.readLong();
					probe.delayLongStart.incrementAndGet();
					Thread.sleep(delay);
					probe.delayLongEnd.incrementAndGet();
					if (reply != null)
						reply.writeInt(42);
					return true;
				}
				default:
					return false;
			}
		}
	}

	/** T14 RED-side server (IPCConnection semantics). */
	static final class RedServer extends Binder {
		static final int ECHO = 1, SLEEP_ONEWAY = 2, NESTED = 3;
		static final long ECHO_SLEEP_MS = 500, SLEEP_ONEWAY_MS = 800;
		final AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
		RedLink link;
		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			try {
				return serve(code, data, reply, flags);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		private boolean serve(int code, Parcel data, Parcel reply, int flags)
		        throws Exception {
			if (code == ECHO) {
				int value = data.readInt();
				int now = active.incrementAndGet();
				int was = max.get();
				while (now > was && !max.compareAndSet(was, now))
					was = max.get();
				Thread.sleep(ECHO_SLEEP_MS);
				active.decrementAndGet();
				if (reply != null)
					reply.writeInt(value);
				return true;
			}
			if (code == SLEEP_ONEWAY) {
				Thread.sleep(SLEEP_ONEWAY_MS);
				return true;
			}
			if (code == NESTED) {
				int expected = data.readInt();
				int callbackHandle = data.readInt();
				Parcel nested = Parcel.obtain();
				Parcel nestedReply = Parcel.obtain();
				try {
					nested.writeInt(expected);
					link.proxyFor(callbackHandle).transact(99, nested, nestedReply, 0);
				} finally {
					nested.recycle();
					nestedReply.recycle();
				}
				if (reply != null)
					reply.writeInt(42);
				return true;
			}
			return false;
		}
		int maxOverlap() {
			return max.get();
		}
		void reset() {
			active.set(0);
			max.set(0);
		}
	}

	/** T14 GREEN-side server (production link). */
	static final class GreenServer extends Binder {
		static final int ECHO = 1, SLEEP_ONEWAY = 2, NESTED = 9;
		final AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			try {
				return serve(code, data, reply, flags);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		private boolean serve(int code, Parcel data, Parcel reply, int flags)
		        throws Exception {
			if (code == ECHO) {
				int value = data.readInt();
				int now = active.incrementAndGet();
				int was = max.get();
				while (now > was && !max.compareAndSet(was, now))
					was = max.get();
				Thread.sleep(40);
				active.decrementAndGet();
				if (reply != null)
					reply.writeInt(value);
				return true;
			}
			if (code == SLEEP_ONEWAY) {
				Thread.sleep(800);
				return true;
			}
			if (code == NESTED) {
				int expected = data.readInt();
				IBinder callback = data.readStrongBinder();
				Parcel nested = Parcel.obtain();
				Parcel nestedReply = Parcel.obtain();
				try {
					nested.writeInt(expected);
					callback.transact(99, nested, nestedReply, 0);
					if (nestedReply.readInt() != expected + 1)
						throw new IllegalStateException("callback value lost");
				} finally {
					nested.recycle();
					nestedReply.recycle();
				}
				if (reply != null) {
					reply.writeInt(42);
					reply.writeStrongBinder(callback);
				}
				return true;
			}
			return false;
		}
		int maxOverlap() {
			return max.get();
		}
	}
}
