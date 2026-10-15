/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Reused (attribution):
 *  - PR #31 baa9b489, tests/remote-services/TestActivity.java: the background
 *    probe thread driving main-looper callbacks through latches, the
 *    check()/marker reporting style and the watchdog.
 *  - PR #31 baa9b489, tests/remote-services/run.sh: the row runner shape
 *    (dbus-run-session + xvfb-run + scripts/mobile/run.sh, merged capture).
 *
 * New in this file: the lane-D PLAN-4 rows, driven directly against
 * android.atl.ATLProcessHost - the same object the service controller uses -
 * so every assertion exercises the production host/child path. Each row prints
 * exactly one "PROC PASS <row>" line (or "PROC FAIL <row> <why>").
 */
package org.atl.procprobe;

import android.app.Activity;
import android.atl.ATLProcessBackend;
import android.atl.ATLProcessHost;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestActivity extends Activity {
	static final String SCENARIO_ENV = "ATL_PRO_PROBE";
	static final String WATCHDOG_ENV = "ATL_PRO_PROBE_WATCHDOG_MS";
	/*
	 * The host keys a child by the *fully qualified* manifest process name:
	 * ATL's PackageParser resolves ":name" against the package, so the
	 * declaration android:process=":worker" is hosted as
	 * "org.atl.procprobe:worker" (and the same string travels to the child as
	 * ATL_SERVICE_PROCESS and is what the fault seam compares against).
	 */
	static final String WORKER = "org.atl.procprobe:worker";
	static final String SECOND = "org.atl.procprobe:second";
	static final String STALLABLE = "org.atl.procprobe:stallable";
	static final String ISOLATED = "org.atl.procprobe:isolated";

	final ATLProcessHost host = ATLProcessHost.get();
	final Handler main = new Handler();
	volatile int ticks;
	boolean stopping;
	volatile boolean finished;
	String scenario;

	/** One request result; ATLProcessHost delivers it on the main looper. */
	static final class Waiter<T> implements ATLProcessBackend.Callback<T> {
		final CountDownLatch latch = new CountDownLatch(1);
		volatile T result;
		volatile boolean unavailable;
		volatile long nanos;

		public void onResult(T value) {
			result = value;
			nanos = System.nanoTime();
			latch.countDown();
		}

		public void onHostUnavailable() {
			unavailable = true;
			nanos = System.nanoTime();
			latch.countDown();
		}

		boolean await(long ms) throws InterruptedException {
			return latch.await(ms, TimeUnit.MILLISECONDS);
		}

		boolean reached() {
			return latch.getCount() == 0;
		}
	}

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		scenario = System.getenv(SCENARIO_ENV);
		System.out.println("PROBE start scenario=" + scenario + " pid=" + Process.myPid()
		    + " process=" + ProbeState.label() + " apk=" + apkFromCmdline("/proc/self/cmdline"));
		System.out.flush();
		if (scenario == null) {
			fail("none", "no " + SCENARIO_ENV + " selected");
			return;
		}
		final String watchdogRow = scenario;
		main.postDelayed(new Runnable() {
			public void run() {
				if (!finished) {
					System.out.println("PROC FAIL " + watchdogRow + " watchdog expired");
					System.out.flush();
					System.exit(1);
				}
			}
		}, watchdogMs());
		main.postDelayed(new Runnable() {
			public void run() {
				ticks++;
				System.out.println("PROBE TICK " + ticks + " pid=" + Process.myPid()
				    + " process=" + ProbeState.label());
				System.out.flush();
				if (!stopping)
					main.postDelayed(this, 1000);
			}
		}, 1000);
		new Thread(new Runnable() {
			public void run() {
				try {
					runScenario(scenario);
				} catch (Throwable t) {
					t.printStackTrace();
					System.out.println("PROC FAIL " + scenario + " " + t);
					System.out.flush();
					System.exit(1);
				}
			}
		}, "probe").start();
	}

	void runScenario(String row) throws Exception {
		if ("P01".equals(row))
			scenarioP01(row);
		else if ("P02".equals(row))
			scenarioP02(row);
		else if ("P03".equals(row))
			scenarioP03(row);
		else if ("P04identity".equals(row))
			scenarioP04identity(row);
		else if ("P04split".equals(row))
			scenarioP04split(row);
		else
			fail(row, "unknown scenario");
	}

	/*
	 * P01: startService returns to the caller immediately, the loader's main
	 * looper keeps running while the child boots (a main-looper post queued
	 * right after the call runs before the spawn result is delivered), the
	 * child's onStartCommand return value arrives, keepalive ticks continue,
	 * and destroying the last service releases the child.
	 */
	void scenarioP01(String row) throws Exception {
		Waiter<Integer> start = new Waiter<Integer>();
		long t0 = System.nanoTime();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, start);
		long callMs = (System.nanoTime() - t0) / 1000000;
		check(row, callMs < 250, "startService returned without waiting for the child (" + callMs + " ms)");
		final long[] looper = new long[1];
		main.post(new Runnable() {
			public void run() {
				looper[0] = System.nanoTime();
			}
		});
		check(row, start.await(60000), "the child's START result arrived");
		check(row, !start.unavailable, "the process was reachable (no onHostUnavailable)");
		check(row, Integer.valueOf(1).equals(start.result), "SvcWorker.onStartCommand returned 1, got " + start.result);
		check(row, looper[0] != 0, "the loader's main looper kept running during the spawn");
		check(row, looper[0] < start.nanos, "a main-looper post ran before the spawn result: startup is asynchronous");
		check(row, host.isProcessUp(WORKER), ":worker is up after READY");
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitProcessDown(WORKER, 20000), "destroying the last hosted service released the child");
		check(row, awaitTicks(2, 6000), "the loader's main-looper keepalive kept ticking after the release (" + ticks + ")");
		done(row);
	}

	/*
	 * P02: one process stalled before READY (child-side fault seam, 8 s ready
	 * timeout) must not delay an unrelated process; the stalled one is given up
	 * with onHostUnavailable, the other one is up and releases normally.
	 */
	void scenarioP02(String row) throws Exception {
		Waiter<Integer> stall = new Waiter<Integer>();
		long t0 = System.nanoTime();
		host.startService(STALLABLE, SvcStall.class.getName(), intent(SvcStall.class), 1, stall);
		Thread.sleep(300);
		Waiter<Integer> second = new Waiter<Integer>();
		host.startService(SECOND, SvcSecond.class.getName(), intent(SvcSecond.class), 1, second);
		check(row, second.await(30000), ":second reported a result while :stallable was stalled");
		check(row, !second.unavailable, ":second was reachable, not queued behind the stalled spawn");
		check(row, Integer.valueOf(1).equals(second.result), "SvcSecond.onStartCommand returned 1");
		check(row, !stall.reached(), ":stallable did not report ready within its ready timeout");
		long secondMs = (second.nanos - t0) / 1000000;
		check(row, secondMs > 0 && secondMs < 8000,
		    "the unrelated process reported inside :stallable's ready timeout (" + secondMs + " ms)");
		check(row, stall.await(30000) && stall.unavailable, ":stallable reported host-unavailable after its ready timeout");
		check(row, host.isProcessUp(SECOND), ":second is up");
		check(row, !host.isProcessUp(STALLABLE), ":stallable is not up");
		check(row, ticks >= 2, "keepalive ticks continued through the stall (" + ticks + ")");
		host.destroyService(SECOND, SvcSecond.class.getName());
		check(row, awaitProcessDown(SECOND, 20000), ":second was released with its last service");
		done(row);
	}

	/*
	 * P03: readiness is the READY transaction only. The stalled child prints a
	 * READY-shaped line of its own (ATL_PROBE_FAKE_READY) before it would ever
	 * report ready and never sends the transaction; the loader must still time
	 * it out. A normal child has to report ready for the log to carry both
	 * forms of the evidence line (checked by the runner).
	 */
	void scenarioP03(String row) throws Exception {
		Waiter<Integer> worker = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, worker);
		check(row, worker.await(30000) && !worker.unavailable, "a normal child reported its start result");
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitProcessDown(WORKER, 20000), ":worker was released");
		Waiter<Integer> stalled = new Waiter<Integer>();
		host.startService(SECOND, SvcSecond.class.getName(), intent(SvcSecond.class), 1, stalled);
		check(row, stalled.await(30000), ":second reported a result");
		check(row, stalled.unavailable, "a child that printed a READY-shaped line but never sent READY is host-unavailable");
		check(row, stalled.result == null, "no start result was fabricated for the stalled child");
		check(row, !host.isProcessUp(SECOND), ":second is not up");
		done(row);
	}

	/*
	 * P04 (identity): the child's STATUS reply names the loaded package, its own
	 * process, a live pid whose /proc entry and data dir exist, and its open fd
	 * names; the child's cmdline shows the frozen spawn argv
	 * (/proc/self/exe + the loader's own apk + --sdk-int).
	 */
	void scenarioP04identity(String row) throws Exception {
		Waiter<Integer> worker = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, worker);
		check(row, worker.await(30000) && !worker.unavailable, ":worker reported its start result");
		check(row, host.isProcessUp(WORKER), ":worker is up");
		final String[][] status = new String[1][];
		final Throwable[] error = new Throwable[1];
		Thread statusThread = new Thread(new Runnable() {
			public void run() {
				try {
					status[0] = host.statusProcess(WORKER);
				} catch (Throwable t) {
					error[0] = t;
				}
			}
		}, "status");
		statusThread.start();
		statusThread.join(30000);
		check(row, error[0] == null, "STATUS did not fail: " + error[0]);
		check(row, status[0] != null, "STATUS returned a reply");
		check(row, status[0].length > 4, "STATUS reported the child's fd names: " + java.util.Arrays.toString(status[0]));
		check(row, "org.atl.procprobe".equals(status[0][0]), "STATUS package, got " + status[0][0]);
		check(row, WORKER.equals(status[0][1]), "STATUS process, got " + status[0][1]);
		int pid = -1;
		try {
			pid = Integer.parseInt(status[0][2]);
		} catch (RuntimeException e) {
			fail(row, "STATUS pid is not a number: " + status[0][2]);
			return;
		}
		check(row, pid > 0 && pid != Process.myPid(), "STATUS pid " + pid + " is a different process");
		check(row, new File("/proc/" + pid).exists(), "/proc/" + pid + " exists");
		check(row, new File(status[0][3]).isDirectory(), "STATUS dataDir exists: " + status[0][3]);
		String[] child = splitNul(ProbeState.readFile("/proc/" + pid + "/cmdline"));
		check(row, child.length >= 4, "the child's cmdline has the spawn argv: " + java.util.Arrays.toString(child));
		check(row, "/proc/self/exe".equals(child[0]), "argv[0] is /proc/self/exe, got " + child[0]);
		String ownApk = apkFromCmdline("/proc/self/cmdline");
		check(row, ownApk != null && ownApk.equals(child[1]),
		    "the child runs the loader's apk (" + child[1] + " vs loader " + ownApk + ")");
		check(row, "--sdk-int".equals(child[2]), "the child was passed --sdk-int, got " + child[2]);
		check(row, child[3].length() > 0, "the child got an SDK_INT value: " + child[3]);
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitProcessDown(WORKER, 20000), ":worker was released");
		done(row);
	}

	/*
	 * P04 (split refusal): with the split-APK test seam set, every host call
	 * for the package is refused synchronously and no child is spawned.
	 */
	void scenarioP04split(String row) throws Exception {
		check(row, System.getenv("ATL_PROBE_EXTRA_APKS") != null, "the split seam is set for this row");
		boolean refused = false;
		try {
			host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, new Waiter<Integer>());
		} catch (UnsupportedOperationException e) {
			refused = true;
			System.out.println("PROBE split refusal startService: " + e.getMessage());
		}
		check(row, refused, "startService refused a split-APK package synchronously");
		refused = false;
		try {
			host.bindService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), new Waiter<IBinder>());
		} catch (UnsupportedOperationException e) {
			refused = true;
		}
		check(row, refused, "bindService refused a split-APK package synchronously");
		refused = false;
		try {
			host.rebindService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class));
		} catch (UnsupportedOperationException e) {
			refused = true;
		}
		check(row, refused, "rebindService refused a split-APK package synchronously");
		refused = false;
		try {
			host.destroyService(WORKER, SvcWorker.class.getName());
		} catch (UnsupportedOperationException e) {
			refused = true;
		}
		check(row, refused, "destroyService refused a split-APK package synchronously");
		check(row, !host.isProcessUp(WORKER), "no child was spawned for the refused package");
		done(row);
	}

	/* ------------------------------------------------------------- plumbing */

	Intent intent(Class<?> cls) {
		return new Intent("org.atl.procprobe.START")
		    .setComponent(new ComponentName(this, cls))
		    .addCategory("org.atl.procprobe.CATEGORY")
		    .putExtra("probe", "extra-value")
		    .putExtra("probe-int", 7);
	}

	void check(String row, boolean ok, String what) {
		if (!ok)
			fail(row, what);
		System.out.println("PROBE OK " + row + " " + what);
		System.out.flush();
	}

	void fail(String row, String why) {
		finished = true;
		System.out.println("PROC FAIL " + row + " " + why);
		System.out.flush();
		System.exit(1);
	}

	void done(String row) {
		finished = true;
		stopping = true;
		System.out.println("PROC PASS " + row);
		System.out.flush();
		/* Let the released children log their own exit before the loader goes. */
		main.postDelayed(new Runnable() {
			public void run() {
				System.exit(0);
			}
		}, 2500);
	}

	boolean awaitProcessDown(String name, long ms) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			if (!host.isProcessUp(name))
				return true;
			Thread.sleep(100);
		}
		return !host.isProcessUp(name);
	}

	long watchdogMs() {
		try {
			return Long.parseLong(System.getenv(WATCHDOG_ENV));
		} catch (RuntimeException e) {
			return 100000L;
		}
	}

	static String[] splitNul(String text) {
		if (text == null)
			return new String[0];
		String[] parts = text.split("\u0000");
		return parts.length > 0 && parts[parts.length - 1].length() == 0
		    ? java.util.Arrays.copyOf(parts, parts.length - 1) : parts;
	}

	/** Bounded wait for the loader's keepalive loop to reach {@code n} ticks. */
	boolean awaitTicks(int n, long ms) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (ticks < n && System.currentTimeMillis() < deadline)
			Thread.sleep(100);
		return ticks >= n;
	}

	static String apkFromCmdline(String path) {
		for (String arg : splitNul(ProbeState.readFile(path)))
			if (arg.endsWith(".apk"))
				return arg;
		return null;
	}
}
