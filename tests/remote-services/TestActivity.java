/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.remoteservices;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

public class TestActivity extends Activity {
	final Handler main = new Handler();
	final CountDownLatch death = new CountDownLatch(1), disconnected =
								new CountDownLatch(1);
	boolean finished;
	void check(boolean result, String name) {
		if (!result)
			throw new AssertionError(name);
		System.out.println("REMOTE PASS " + name);
	}
	Intent serviceIntent() {
		return new Intent().setComponent(
		    new ComponentName(this, TestService.class));
	}
	public void onCreate(Bundle state) {
		super.onCreate(state);
		main.postDelayed(new Runnable() {
			public void run() {
				if (!finished) {
					System.err.println("REMOTE FAIL timeout");
					System.exit(1);
				}
			}
		}, 60000);
		check(!stopService(serviceIntent()), "stop absent service returns false");
		boolean nonAuto = false;
		try {
			bindService(serviceIntent(), connection, 0);
		} catch (UnsupportedOperationException e) {
			nonAuto = true;
		}
		check(nonAuto, "cold non-auto binding fails explicitly");
		check(((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses().size() == 1, "stop/non-auto do not start a child");
		check(bindService(serviceIntent(), connection, BIND_AUTO_CREATE),
		      "bind accepted");
	}
	final ServiceConnection connection = new ServiceConnection() {
		public void onServiceConnected(ComponentName name, final IBinder service) {
			check(Looper.myLooper() == Looper.getMainLooper(),
			      "connection on main looper");
			new Thread(new Runnable() {
				public void run() {
					try {
						test(service);
						finished = true;
						System.out.println("REMOTE CONTRACTS PASSED");
						System.exit(0);
					} catch (Throwable e) {
						e.printStackTrace();
						System.exit(1);
					}
				}
			}, "probe-client").start();
		}
		public void onServiceDisconnected(ComponentName name) {
			disconnected.countDown();
		}
	};
	Parcel call(IBinder binder, int code, Parcel data) {
		Parcel reply = Parcel.obtain();
		check(binder.transact(code, data, reply, 0), "transaction " + code);
		return reply;
	}
	void test(final IBinder service) throws Exception {
		check(service.queryLocalInterface("anything") == null, "remote proxy");
		check("org.atl.remoteservices.TestService".equals(service.getInterfaceDescriptor()), "remote descriptor");
		check(!service.transact(999, Parcel.obtain(), Parcel.obtain(), 0), "unknown transaction returns false");
		Parcel stats = call(service, 1, Parcel.obtain());
		int pid = stats.readInt();
		check(pid != android.os.Process.myPid(), "different process PID");
		check(stats.readString().equals(getPackageName() + ":worker"),
		      "declared process name");
		check(stats.readInt() == android.os.Process.myPid(), "caller PID");
		check(stats.readInt() == 1 && stats.readInt() == 1, "onCreate/onBind once");
		stats.recycle();
		// Started + bound: removing one of two clients must not destroy the
		// service.
		startService(serviceIntent());
		final CountDownLatch secondConnected = new CountDownLatch(1);
		ServiceConnection second = new ServiceConnection() {
			public void onServiceConnected(ComponentName name, IBinder other) {
				check(other == service, "two clients share Binder identity");
				secondConnected.countDown();
			}
		};
		check(bindService(serviceIntent(), second, BIND_AUTO_CREATE),
		      "second bind accepted");
		check(secondConnected.await(5, TimeUnit.SECONDS), "second bind delivered");
		unbindService(second);
		stats = call(service, 1, Parcel.obtain());
		stats.readInt();
		stats.readString();
		stats.readInt();
		check(stats.readInt() == 1 && stats.readInt() == 1 && stats.readInt() == 1 && stats.readInt() == 0,
		      "shared service lifecycle");
		stats.recycle();
		check(stopService(serviceIntent()), "stop started service while bound");
		Parcel identity = call(service, 9, Parcel.obtain());
		check(identity.readInt() == pid && identity.readInt() == android.os.Process.myPid(), "clear/restore calling identity");
		identity.recycle();
		boolean known = false;
		for (ActivityManager.RunningAppProcessInfo info : ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses())
			if (info.pid == pid && info.pkgList.length == 1 && info.pkgList[0].equals(getPackageName()))
				known = true;
		check(known, "ActivityManager reports owned child");
		final long callingThread = Thread.currentThread().getId();
		final Binder callback = new Binder() {
			protected boolean onTransact(int code, Parcel data, Parcel reply,
			                             int flags) {
				check(Thread.currentThread().getId() == callingThread,
				      "callback reenters waiting thread");
				IBinder returnedService = data.readStrongBinder();
				check(returnedService == service, "proxy identity retained");
				Parcel nested = call(returnedService, 1, Parcel.obtain());
				check(nested.readInt() != android.os.Process.myPid(),
				      "nested call without deadlock");
				nested.recycle();
				reply.writeInt(42);
				return true;
			}
		};
		Parcel args = Parcel.obtain();
		args.writeStrongInterface(new IInterface() {
			public IBinder asBinder() { return callback; }
		});
		Parcel response = call(service, 2, args);
		check(response.readInt() == 42 && response.readStrongBinder() == callback,
		      "callback result and Binder roundtrip identity");
		response.recycle();
		args.recycle();
		Parcel forged = Parcel.obtain();
		forged.writeInt(1);
		forged.setDataPosition(0);
		boolean invalidFd = false;
		try {
			forged.readFileDescriptor();
		} catch (ParcelFormatException expected) {
			invalidFd = true;
		}
		check(invalidFd, "raw integer cannot impersonate a descriptor");
		forged.recycle();
		File file = new File(getCacheDir(), "synthetic-input.txt");
		FileOutputStream out = new FileOutputStream(file);
		out.write(91);
		out.close();
		FileInputStream input = new FileInputStream(file);
		args = Parcel.obtain();
		args.writeFileDescriptor(input.getFD());
		input.close();
		response = call(service, 3, args);
		check(response.readInt() == 91, "descriptor parent to child");
		ParcelFileDescriptor received = response.readFileDescriptor();
		response.recycle();
		FileInputStream returned =
		    new FileInputStream(received.getFileDescriptor());
		check(returned.read() == 73,
		      "descriptor child to parent survives Parcel recycle");
		received.close();
		input.close();
		args.recycle();
		for (int i = 1; i <= 20; ++i) {
			Parcel p = Parcel.obtain();
			p.writeInt(i);
			service.transact(4, p, null, IBinder.FLAG_ONEWAY);
			p.recycle();
		}
		boolean ordered = false;
		for (int i = 0; i < 50; ++i) {
			response = call(service, 5, Parcel.obtain());
			int n = response.readInt();
			response.recycle();
			if (n == 20) {
				ordered = true;
				break;
			}
			Thread.sleep(20);
		}
		check(ordered, "oneway order and completion");
		boolean caught = false;
		try {
			call(service, 6, Parcel.obtain());
		} catch (IllegalArgumentException expected) {
			caught = "synthetic-error".equals(expected.getMessage());
		}
		check(caught, "remote runtime exception");
		unbindService(connection);
		final CountDownLatch rebound = new CountDownLatch(1);
		final IBinder[] replacementBinder = new IBinder[1];
		ServiceConnection replacement = new ServiceConnection() {
			public void onServiceConnected(ComponentName component, IBinder binder) {
				replacementBinder[0] = binder;
				rebound.countDown();
			}
			public void onServiceDisconnected(ComponentName component) {
				disconnected.countDown();
			}
		};
		check(bindService(serviceIntent(), replacement, BIND_AUTO_CREATE),
		      "rebind accepted");
		check(rebound.await(5, TimeUnit.SECONDS), "rebind delivered");
		check(replacementBinder[0] != service,
		      "destroy/recreate yields a new Binder");
		stats = call(replacementBinder[0], 1, Parcel.obtain());
		check(stats.readInt() == pid,
		      "cached process reused after service destruction");
		stats.readString();
		stats.readInt();
		check(stats.readInt() == 2 && stats.readInt() == 2 && stats.readInt() == 1 && stats.readInt() == 1 && stats.readInt() == 1,
		      "last unbind destroys and next bind recreates service");
		stats.recycle();
		check(disconnected.getCount() == 1,
		      "ordinary unbind does not notify disconnection");
		final CountDownLatch nullBinding = new CountDownLatch(1);
		ServiceConnection nullConnection = new ServiceConnection() {
			public void onServiceConnected(ComponentName component, IBinder binder) { throw new AssertionError("Expected null binding"); }
			public void onNullBinding(ComponentName component) { nullBinding.countDown(); }
		};
		check(bindService(serviceIntent().setAction("null"), nullConnection, BIND_AUTO_CREATE), "null bind accepted");
		check(nullBinding.await(5, TimeUnit.SECONDS), "onNullBinding delivered");
		unbindService(nullConnection);
		final boolean[] unlinkedCalled = {false};
		IBinder.DeathRecipient unlinked = new IBinder.DeathRecipient() { public void binderDied() { unlinkedCalled[0] = true; } };
		service.linkToDeath(unlinked, 0);
		check(service.unlinkToDeath(unlinked, 0), "unlink death recipient");
		service.linkToDeath(new IBinder.DeathRecipient() {
			public void binderDied() { death.countDown(); }
		}, 0);
		boolean pendingFailed = false;
		try {
			call(service, 7, Parcel.obtain());
		} catch (Throwable e) {
			pendingFailed = e instanceof DeadObjectException;
		}
		check(pendingFailed, "peer death fails an outstanding transaction");
		check(death.await(5, TimeUnit.SECONDS), "death recipient");
		check(disconnected.await(5, TimeUnit.SECONDS),
		      "service disconnection callback");
		check(!service.isBinderAlive(), "dead proxy state");
		check(!unlinkedCalled[0], "unlinked death recipient not invoked");
		boolean dead = false;
		try {
			service.transact(1, Parcel.obtain(), Parcel.obtain(), 0);
		} catch (Throwable e) {
			dead = e instanceof DeadObjectException;
		}
		check(dead, "dead transaction fails");
		unbindService(replacement);
		final CountDownLatch restarted = new CountDownLatch(1);
		final IBinder[] afterDeath = new IBinder[1];
		ServiceConnection restartConnection = new ServiceConnection() {
			public void onServiceConnected(ComponentName component, IBinder binder) {
				afterDeath[0] = binder;
				restarted.countDown();
			}
		};
		check(bindService(serviceIntent(), restartConnection, BIND_AUTO_CREATE), "explicit rebind after death accepted");
		check(restarted.await(10, TimeUnit.SECONDS), "explicit rebind after death connected");
		stats = call(afterDeath[0], 1, Parcel.obtain());
		check(stats.readInt() != pid, "explicit rebind starts a new process");
		stats.recycle();
		unbindService(restartConnection);
	}
}
