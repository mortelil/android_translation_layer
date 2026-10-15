/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.content.Context;
import android.os.ATLBinderConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Child mode of the transport probe (TRN-011/TRN-014 cross-process rows).
 *
 * The production spawn path (PR31 baa9b489: ATLBinderConnection.spawn) starts
 * this same APK with the channel descriptor dup2'd onto fd 3 and
 * ATL_SERVICE_FD/ATL_SERVICE_PARENT_PID in the environment. The launcher
 * activity detects that and hands control here instead of running the parent
 * battery: the child becomes a real service host whose ROOT binder serves the
 * probe protocol, over the production connection, in a real second process.
 *
 * The child never trusts a battery claim: it appends one line per executed
 * transaction to files/child-exec.log (open/append/close per line, so the
 * evidence survives an unorderly kill), answers identity questions with the
 * values the transport reports, and exits after its link dies.
 */
public final class ChildTransportHost {
	// Root protocol codes (the parent's battery is the other half).
	static final int PING = 1;
	static final int ECHO = 2;
	static final int OVERLAP = 15;
	static final int THROW_ILLEGAL = 5;
	static final int THROW_RUNTIME = 6;
	static final int READ_FD = 7;
	static final int RETURN_FD = 8;
	static final int NESTED = 9;
	static final int IDENTITY = 10;
	static final int NEW_BINDER = 11;
	static final int TABLE_SIZE = 12;
	static final int BLOCK_FOREVER = 13;
	static final int GET_ORDER_TARGET = 14;
	// Order-target protocol codes.
	static final int SLEEP_ONEWAY = 3;
	static final int ORDER = 4;
	static final int READ_ORDER = 16;

	static final String EXEC_LOG = "child-exec.log";
	static volatile int executedTransactions;

	public static boolean isChild() {
		return System.getenv("ATL_SERVICE_FD") != null;
	}

	public static void start(final Context context) throws Exception {
		int fd = Integer.parseInt(System.getenv("ATL_SERVICE_FD").trim());
		int parentPid = Integer.parseInt(System.getenv("ATL_SERVICE_PARENT_PID").trim());
		final HostRoot root = new HostRoot(context);
		final ATLBinderConnection connection =
			new ATLBinderConnection(fd, parentPid, root);
		root.connection = connection;
		connection.onDeath(new Runnable() {
			public void run() {
				log(context, "link-dead executed=" + executedTransactions
				             + " killing service host");
				// Flush window for the exec log, then leave; the parent owns
				// the reaping and the verdict.
				Thread reaper = new Thread(new Runnable() {
					public void run() {
						try {
							Thread.sleep(6000);
						} catch (InterruptedException ignored) {
						}
						Runtime.getRuntime().exit(0);
					}
				}, "trn-child-reaper");
				reaper.setDaemon(true);
				reaper.start();
			}
		});
		connection.start();
		writeReady(context, parentPid, fd);
		System.out.println("TRANSPORT CHILD READY pid=" + Process.myPid()
		                   + " parent=" + parentPid + " fd=" + fd);
	}

	private static void writeReady(Context context, int parentPid, int fd) throws IOException {
		File ready = new File(context.getFilesDir(), "child-ready-" + Process.myPid());
		FileWriter writer = new FileWriter(ready, true);
		try {
			writer.write("pid=" + Process.myPid() + " parent=" + parentPid
			             + " fd=" + fd + "\n");
		} finally {
			writer.close();
		}
	}

	static void log(Context context, String line) {
		try {
			FileWriter writer = new FileWriter(new File(context.getFilesDir(), EXEC_LOG), true);
			try {
				writer.write(System.currentTimeMillis() + " " + line + "\n");
			} finally {
				writer.close();
			}
		} catch (IOException ignored) {
		}
	}

	static final class HostRoot extends Binder {
		final Context context;
		ATLBinderConnection connection;
		final CountDownLatch block = new CountDownLatch(1);
		final List<Integer> order = new ArrayList<>();
		final AtomicInteger echoActive = new AtomicInteger();
		final AtomicInteger echoMax = new AtomicInteger();
		final AtomicInteger serial = new AtomicInteger();
		final Binder orderTarget = new Binder() {
			@Override
			protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
				if (code == SLEEP_ONEWAY) {
					log(context, "order-target sleep start");
					try {
						Thread.sleep(800);
					} catch (InterruptedException ignored) {
					}
					log(context, "order-target sleep done");
					return true;
				}
				if (code == ORDER) {
					int sequence = data.readInt();
					synchronized (order) {
						order.add(Integer.valueOf(sequence));
					}
					log(context, "order-target append " + sequence);
					return true;
				}
				if (code == READ_ORDER) {
					synchronized (order) {
						reply.writeInt(order.size());
						for (int sequence : order)
							reply.writeInt(sequence);
					}
					return true;
				}
				return false;
			}
		};

		HostRoot(Context context) {
			this.context = context;
			try {
				File pull = new File(context.getFilesDir(), "fd-pull.bin");
				FileOutputStream out = new FileOutputStream(pull);
				try {
					out.write(73);
				} finally {
					out.close();
				}
			} catch (IOException ignored) {
			}
		}

		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			executedTransactions++;
			try {
				return serve(code, data, reply, flags);
			} catch (Throwable t) {
				log(context, "code=" + code + " fault " + t);
				if (t instanceof Error)
					throw (Error)t;
				if (t instanceof RuntimeException)
					throw (RuntimeException)t;
				throw new RuntimeException(t);
			}
		}

		private boolean serve(int code, Parcel data, Parcel reply, int flags) throws Exception {
			switch (code) {
				case PING:
					log(context, "code=1 ping");
					if (reply != null)
						reply.writeInt(Process.myPid());
					return true;
				case ECHO: {
					int value = data.readInt();
					int active = echoActive.incrementAndGet();
					int was = echoMax.get();
					while (active > was && !echoMax.compareAndSet(was, active))
						was = echoMax.get();
					log(context, "code=2 echo " + value + " active=" + active);
					try {
						Thread.sleep(40);
					} catch (InterruptedException ignored) {
					}
					echoActive.decrementAndGet();
					if (reply != null)
						reply.writeInt(value);
					return true;
				}
				case OVERLAP:
					log(context, "code=15 overlap report");
					if (reply != null)
						reply.writeInt(echoMax.get());
					return true;
				case THROW_ILLEGAL:
					log(context, "code=5 throwing IllegalArgumentException(synthetic-error)");
					throw new IllegalArgumentException("synthetic-error");
				case THROW_RUNTIME:
					log(context, "code=6 throwing RuntimeException(plain-runtime)");
					throw new RuntimeException("plain-runtime");
				case READ_FD: {
					ParcelFileDescriptor received = data.readFileDescriptor();
					if (received == null) {
						log(context, "code=7 NO DESCRIPTOR");
						throw new IllegalStateException("no descriptor arrived");
					}
					int value = new FileInputStream(received.getFileDescriptor()).read();
					log(context, "code=7 read " + value + " from pushed descriptor");
					if (reply != null)
						reply.writeInt(value);
					return true;
				}
				case RETURN_FD: {
					File pull = new File(context.getFilesDir(), "fd-pull.bin");
					FileInputStream in = new FileInputStream(pull);
					try {
						reply.writeFileDescriptor(in.getFD());
					} finally {
						in.close(); // the parcel duplicated the descriptor
					}
					log(context, "code=8 returned descriptor for fd-pull.bin");
					return true;
				}
				case NESTED: {
					int expected = data.readInt();
					IBinder callback = data.readStrongBinder();
					log(context, "code=9 nested into callback marker=" + expected);
					Parcel nested = Parcel.obtain();
					Parcel nestedReply = Parcel.obtain();
					try {
						nested.writeInt(expected);
						callback.transact(99, nested, nestedReply, 0);
						int back = nestedReply.readInt();
						log(context, "code=9 callback returned " + back);
						if (back != expected + 1)
							throw new IllegalStateException("callback value lost: " + back);
					} finally {
						nested.recycle();
						nestedReply.recycle();
					}
					reply.writeInt(42);
					reply.writeStrongBinder(callback);
					return true;
				}
				case IDENTITY:
					log(context, "code=10 identity calling=" + Binder.getCallingPid()
					             + "/" + Binder.getCallingUid());
					if (reply != null) {
						int[] channel = ATLBinderConnection.peerCredentials(
						    Integer.parseInt(System.getenv("ATL_SERVICE_FD").trim()));
						reply.writeInt(Binder.getCallingPid());
						reply.writeInt(Binder.getCallingUid());
						reply.writeInt(Process.myPid());
						/* W1: what the kernel says about the caller on our own
						 * channel end - the value that cannot be spoofed by a
						 * message field (pid/uid of the process that created
						 * the pair, i.e. the parent). */
						reply.writeInt(channel[0]);
						reply.writeInt(channel[1]);
					}
					return true;
				case NEW_BINDER: {
					final int mySerial = serial.getAndIncrement();
					Binder fresh = new Binder() {
						@Override
						protected boolean onTransact(int code2, Parcel data2, Parcel reply2,
						                             int flags2) {
							if (code2 == 2 && reply2 != null) {
								reply2.writeInt(data2.readInt() * 2);
								return true;
							}
							return false;
						}
					};
					log(context, "code=11 new binder serial=" + mySerial);
					if (reply != null) {
						reply.writeStrongBinder(fresh);
						reply.writeInt(mySerial);
					}
					return true;
				}
				case TABLE_SIZE:
					if (reply != null)
						reply.writeInt(LinkTools.exportTableSize(connection));
					return true;
				case BLOCK_FOREVER:
					log(context, "code=13 blocking until death");
					block.await(300, TimeUnit.SECONDS);
					return true;
				case GET_ORDER_TARGET:
					log(context, "code=14 handing out order target");
					if (reply != null)
						reply.writeStrongBinder(orderTarget);
					return true;
				default:
					log(context, "code=" + code + " unknown");
					return false;
			}
		}
	}

	private ChildTransportHost() {
	}
}
