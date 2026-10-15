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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
		else if ("P05".equals(row))
			scenarioP05(row);
		else if ("P06".equals(row))
			scenarioP06(row);
		else if ("P07".equals(row))
			scenarioP07(row);
		else if ("P08".equals(row))
			scenarioP08(row);
		else if ("P09".equals(row))
			scenarioP09(row);
		else if ("P10".equals(row))
			scenarioP10(row);
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

	/*
	 * P05: the child's boot order is AOSP handleBindApplication's: the
	 * Application is attached first, then the process' own providers are
	 * created, then Application.onCreate runs. The child inherits the loader's
	 * stdout, so both sides are in the row log the runner redirected; the probe
	 * reads that same file and asserts the child-side offsets strictly
	 * increase, that Application.onCreate saw exactly one provider created and
	 * created before it ran, and that the child-side ordering assertion did not
	 * fire. The runner repeats the ordering and absence checks on the same log.
	 */
	void scenarioP05(String row) throws Exception {
		Waiter<Integer> worker = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, worker);
		check(row, worker.await(60000), ":worker reported its start result");
		check(row, !worker.unavailable, ":worker was reachable (no onHostUnavailable)");
		check(row, Integer.valueOf(1).equals(worker.result), "SvcWorker.onStartCommand returned 1, got " + worker.result);
		check(row, host.isProcessUp(WORKER), ":worker is up after READY");
		String path = requireLogPath(row);
		String text = attemptText(readWholeFile(path), row);
		int attach = lastChildLine(text, "ATL_PROBE_APP_ATTACH", WORKER);
		int provider = lastChildLine(text, "ATL_PROBE_PROVIDER_ONCREATE", WORKER);
		int app = lastChildLine(text, "ATL_PROBE_APP_ONCREATE", WORKER);
		check(row, attach >= 0, "the child logged ATL_PROBE_APP_ATTACH (application attach)");
		check(row, provider >= 0, "the child logged ATL_PROBE_PROVIDER_ONCREATE");
		check(row, app >= 0, "the child logged ATL_PROBE_APP_ONCREATE");
		check(row, attach < provider, "application attach precedes provider onCreate in the child (" + attach + " < " + provider + ")");
		check(row, provider < app, "provider onCreate precedes Application.onCreate in the child (" + provider + " < " + app + ")");
		int childPid = pidOfLine(text, "ATL_PROBE_APP_ATTACH", WORKER);
		check(row, childPid > 0 && childPid != Process.myPid(), "the child-side lines come from another pid: " + childPid);
		check(row, pidOfLine(text, "ATL_PROBE_PROVIDER_ONCREATE", WORKER) == childPid
		    && pidOfLine(text, "ATL_PROBE_APP_ONCREATE", WORKER) == childPid,
		    "attach, provider and Application.onCreate all ran in that one child (pid " + childPid + ")");
		String appLine = lineContaining(text, "ATL_PROBE_APP_ONCREATE", WORKER);
		check(row, appLine != null && appLine.contains("providers_created=1"),
		    "Application.onCreate saw exactly one provider created: " + appLine);
		check(row, appLine != null && appLine.contains("provider_oncreate=true"),
		    "the provider had already run when Application.onCreate ran: " + appLine);
		check(row, !text.contains("ATL_PROBE_CHILD_ORDER_FAIL providers did not run"),
		    "the child raised no child-order-failure marker");
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitProcessDown(WORKER, 20000), ":worker was released with its last service");
		done(row);
	}

	/*
	 * P06: the child-side crash-before-ready fault makes the child fail
	 * initialization (ATL_SERVICE_PROCESS_INIT_FAILED, exit 11); the loader must
	 * report onHostUnavailable, never a fabricated result. Then, in the same run,
	 * a service whose onBind() returns null is a *successful* binding: the
	 * callback is onResult(null), which is a different event from
	 * onHostUnavailable, and the child is released with its last component.
	 */
	void scenarioP06(String row) throws Exception {
		Waiter<Integer> crashed = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, crashed);
		check(row, crashed.await(60000), ":worker (crash-before-ready) reported a terminal callback");
		check(row, crashed.unavailable, "a child that died before READY is host-unavailable, not a result");
		check(row, crashed.result == null, "no start result was fabricated for the crashed child");
		check(row, !host.isProcessUp(WORKER), ":worker is not up after its initialization failure");
		String text = attemptText(readWholeFile(requireLogPath(row)), row);
		check(row, text.contains("ATL_SERVICE_PROCESS_INIT_FAILED"),
		    "the child logged its own ATL_SERVICE_PROCESS_INIT_FAILED");
		Waiter<IBinder> bind = new Waiter<IBinder>();
		host.bindService(SECOND, SvcNullBind.class.getName(), intent(SvcNullBind.class), bind);
		check(row, bind.await(60000), ":second reported its bind callback");
		check(row, !bind.unavailable, "a null onBind() is a delivered result, not host-unavailable");
		check(row, bind.result == null, "onResult(null) carried no binder (got " + bind.result + ")");
		check(row, host.isProcessUp(SECOND), ":second is up after the null binding");
		host.destroyService(SECOND, SvcNullBind.class.getName());
		check(row, awaitProcessDown(SECOND, 20000), "the child was released with its last component");
		done(row);
	}

	/*
	 * P07: a hosted callback that throws ends the child with exit 10; the death
	 * is reported with the dying child's generation, the next spawn of the same
	 * process name is the next generation, and every dead child is reaped (its
	 * /proc entry is gone). The loader's own open-fd count must not grow across
	 * the three spawn/teardown cycles.
	 */
	void scenarioP07(String row) throws Exception {
		final DeathRecorder deaths = new DeathRecorder();
		host.setProcessDeathListener(deaths);
		int fdsBefore = fdCount();
		check(row, fdsBefore > 0, "the loader's open-fd count before the cycle: " + fdsBefore);
		int[] generations = new int[3];
		for (int cycle = 0; cycle < 3; cycle++) {
			Waiter<Integer> crash = new Waiter<Integer>();
			host.startService(WORKER, SvcCrash.class.getName(), intent(SvcCrash.class), cycle + 1, crash);
			check(row, crash.await(60000), "crash " + (cycle + 1) + " reported a terminal callback");
			check(row, crash.unavailable && crash.result == null,
			    "crash " + (cycle + 1) + " was host-unavailable with no fabricated result");
			check(row, deaths.awaitCount(cycle + 1, 30000), "death event " + (cycle + 1) + " was reported to the listener");
			generations[cycle] = deaths.generation(cycle);
			check(row, WORKER.equals(deaths.name(cycle)),
			    "death event " + (cycle + 1) + " names " + WORKER + ", got " + deaths.name(cycle));
		}
		check(row, generations[0] >= 1, "the first child's generation is " + generations[0]);
		check(row, generations[1] == generations[0] + 1 && generations[2] == generations[1] + 1,
		    "generations are monotonic g, g+1, g+2 for one process name: " + generations[0] + ", "
		    + generations[1] + ", " + generations[2]);
		String text = attemptText(readWholeFile(requireLogPath(row)), row);
		List<Integer> pids = pidsIn(text, "ATL_SERVICE_PROCESS_CALLBACK_FAILED");
		check(row, pids.size() == 3, "the child logged ATL_SERVICE_PROCESS_CALLBACK_FAILED for 3 children: " + pids);
		for (int i = 0; i < pids.size(); i++) {
			int pid = pids.get(i).intValue();
			check(row, pid != Process.myPid(), "dead child " + pid + " is a child process");
			check(row, awaitProcGone(pid, 20000), "dead child " + pid + " was reaped: /proc/" + pid + " is gone");
		}
		check(row, !host.isProcessUp(WORKER), ":worker is not up after three crashes");
		int fdsAfter = fdCount();
		check(row, fdsAfter <= fdsBefore,
		    "the loader's open-fd count did not grow over the cycle (" + fdsBefore + " -> " + fdsAfter + ")");
		done(row);
	}

	/*
	 * P08: a closing window must not take a legitimately running service with it.
	 * After finish() the loader's keepalive keeps ticking (the per-live-child hold
	 * is what keeps the launcher alive with no window), both children stay up, and
	 * destroying :worker's last service drops only that child: its pid disappears
	 * from /proc while :second's hold keeps the loader itself alive with no window
	 * at all, and :worker's own exit 0 is runner-checked from this log.
	 */
	void scenarioP08(String row) throws Exception {
		Waiter<Integer> worker = new Waiter<Integer>();
		Waiter<Integer> second = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, worker);
		host.startService(SECOND, SvcSecond.class.getName(), intent(SvcSecond.class), 1, second);
		check(row, worker.await(60000) && !worker.unavailable && Integer.valueOf(1).equals(worker.result),
		    ":worker reported its start result");
		check(row, second.await(60000) && !second.unavailable && Integer.valueOf(1).equals(second.result),
		    ":second reported its start result with the window still open");
		String[] status = host.statusProcess(WORKER);
		check(row, status != null, "STATUS answered while :worker is up");
		int pid = status == null ? -1 : pidField("pid=" + status[2]);
		check(row, pid > 0 && new File("/proc/" + pid).exists(), "the child pid " + pid + " is live before the window closes");
		final int before = ticks;
		main.post(new Runnable() {
			public void run() {
				finish();
			}
		});
		check(row, awaitTicks(before + 2, 10000),
		    "keepalive ticks continued after finish() with live services (" + before + " -> " + ticks + ")");
		check(row, host.isProcessUp(WORKER) && host.isProcessUp(SECOND), "both children are still up with no window");
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitProcessDown(WORKER, 20000), ":worker was released with its last service");
		check(row, awaitProcGone(pid, 20000), "the child pid " + pid + " disappeared from /proc");
		check(row, host.isProcessUp(SECOND), ":second's live service kept the loader alive after :worker died");
		done(row);
		host.destroyService(SECOND, SvcSecond.class.getName());
	}

	/*
	 * P09: two services of one manifest process share one child pid; destroying
	 * one of them keeps the child; a live binding on the second one then holds
	 * the child while the first one's instance is destroyed too, and the
	 * binding's release is what releases the child (exit 0, runner-checked). The
	 * row only ever destroys an instance that is not bound (a bound instance is
	 * still a live component), so the unbind is delivered to a live instance. An
	 * isolated service is refused synchronously for both startService and
	 * bindService and spawns nothing.
	 */
	void scenarioP09(String row) throws Exception {
		String path = requireLogPath(row);
		Waiter<Integer> one = new Waiter<Integer>();
		Waiter<Integer> two = new Waiter<Integer>();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, one);
		host.startService(WORKER, SvcWorker2.class.getName(), intent(SvcWorker2.class), 2, two);
		check(row, one.await(60000) && !one.unavailable && Integer.valueOf(1).equals(one.result),
		    "SvcWorker reported its start result");
		check(row, two.await(60000) && !two.unavailable && Integer.valueOf(1).equals(two.result),
		    "SvcWorker2 reported its start result");
		String[] status = host.statusProcess(WORKER);
		check(row, status != null, "STATUS answered for the shared process");
		int pid = status == null ? -1 : Integer.parseInt(status[2]);
		check(row, pid > 0, "the shared child pid is " + pid);
		String text = attemptText(readWholeFile(path), row);
		check(row, pidOfLine(text, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker ", WORKER) == pid
		    && pidOfLine(text, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker2 ", WORKER) == pid,
		    "both services ran in the one child pid " + pid);
		/* first destroy: the second service of the pair, in the same child */
		host.destroyService(WORKER, SvcWorker2.class.getName());
		check(row, awaitLogLine(path, "ATL_PROBE_SERVICE_ONDESTROY class=org.atl.procprobe.SvcWorker2 ", WORKER, 20000),
		    "the child destroyed the service it shares its process with");
		check(row, host.isProcessUp(WORKER), "the child stayed up while its other service was hosted (pid " + pid + ")");
		Waiter<IBinder> bind = new Waiter<IBinder>();
		host.bindService(WORKER, SvcWorker2.class.getName(), intent(SvcWorker2.class), bind);
		check(row, bind.await(60000), "the bind of SvcWorker2 reported a result");
		check(row, !bind.unavailable && bind.result != null, "SvcWorker2 delivered a binder: " + bind.result);
		/* second destroy: the other service, while the binding above is held */
		host.destroyService(WORKER, SvcWorker.class.getName());
		check(row, awaitLogLine(path, "ATL_PROBE_SERVICE_ONDESTROY class=org.atl.procprobe.SvcWorker ", WORKER, 20000),
		    "the child destroyed the other service");
		check(row, host.isProcessUp(WORKER), "the child is still up with its other service destroyed, held by the binding");
		Waiter<Boolean> unbind = new Waiter<Boolean>();
		host.unbindService(WORKER, SvcWorker2.class.getName(), intent(SvcWorker2.class), unbind);
		check(row, unbind.await(60000), "the unbind of SvcWorker2 reported a result");
		check(row, !unbind.unavailable, "the unbind reached the live bound instance (no onHostUnavailable)");
		check(row, awaitLogLine(path, "ATL_PROBE_SERVICE_ONUNBIND class=org.atl.procprobe.SvcWorker2 ", WORKER, 20000),
		    "the child saw the unbind of SvcWorker2");
		/*
		 * The host is mechanism-only: a binding that goes away drops the binding
		 * count but keeps the component's hosted record, and the record's release
		 * is the controller's policy step (a record neither started nor bound is
		 * destroyed). A lane-D probe therefore drives that step too, exactly as
		 * the controller does for a dead record, and only then may the child go.
		 */
		host.destroyService(WORKER, SvcWorker2.class.getName());
		check(row, awaitProcessDown(WORKER, 30000), "releasing the binding's component released the child");
		check(row, awaitProcGone(pid, 20000), "the child pid " + pid + " disappeared from /proc");
		boolean refused = false;
		try {
			host.startService(ISOLATED, SvcIsolated.class.getName(), intent(SvcIsolated.class), 1, new Waiter<Integer>());
		} catch (UnsupportedOperationException e) {
			refused = true;
			System.out.println("PROBE isolated refusal startService: " + e.getMessage());
		}
		check(row, refused, "startService refused the isolated service synchronously");
		refused = false;
		try {
			host.bindService(ISOLATED, SvcIsolated.class.getName(), intent(SvcIsolated.class), new Waiter<IBinder>());
		} catch (UnsupportedOperationException e) {
			refused = true;
			System.out.println("PROBE isolated refusal bindService: " + e.getMessage());
		}
		check(row, refused, "bindService refused the isolated service synchronously");
		check(row, !host.isProcessUp(ISOLATED), "no child was spawned for the isolated process");
		done(row);
	}

	/*
	 * P10: three process names start concurrently while one of them stalls before
	 * READY (child-side seam, 8 s ready timeout). The unrelated ones report; the
	 * shared :worker child serves two services in one pid; the stalled one ends
	 * host-unavailable and never becomes up; keepalive ticks continue through the
	 * stall. Releasing everything leaves no child behind.
	 */
	void scenarioP10(String row) throws Exception {
		String path = requireLogPath(row);
		Waiter<Integer> one = new Waiter<Integer>();
		Waiter<Integer> two = new Waiter<Integer>();
		Waiter<Integer> second = new Waiter<Integer>();
		Waiter<Integer> stall = new Waiter<Integer>();
		long t0 = System.nanoTime();
		host.startService(WORKER, SvcWorker.class.getName(), intent(SvcWorker.class), 1, one);
		host.startService(WORKER, SvcWorker2.class.getName(), intent(SvcWorker2.class), 2, two);
		host.startService(STALLABLE, SvcStall.class.getName(), intent(SvcStall.class), 1, stall);
		Thread.sleep(300);
		host.startService(SECOND, SvcSecond.class.getName(), intent(SvcSecond.class), 1, second);
		check(row, second.await(30000), ":second reported a result while :stallable was stalled");
		check(row, !second.unavailable && Integer.valueOf(1).equals(second.result), ":second is up and running");
		long secondMs = (second.nanos - t0) / 1000000;
		check(row, secondMs > 0 && secondMs < 8000,
		    "an unrelated process reported inside :stallable's ready timeout (" + secondMs + " ms)");
		check(row, one.await(30000) && !one.unavailable && Integer.valueOf(1).equals(one.result),
		    "SvcWorker reported its start result");
		check(row, two.await(30000) && !two.unavailable && Integer.valueOf(1).equals(two.result),
		    "SvcWorker2 reported its start result");
		String[] status = host.statusProcess(WORKER);
		check(row, status != null, "STATUS answered for the shared :worker child");
		int workerPid = status == null ? -1 : Integer.parseInt(status[2]);
		String text = attemptText(readWholeFile(path), row);
		check(row, pidOfLine(text, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker ", WORKER) == workerPid
		    && pidOfLine(text, "ATL_PROBE_SERVICE_ONSTART class=org.atl.procprobe.SvcWorker2 ", WORKER) == workerPid,
		    ":worker serves two services in one pid " + workerPid);
		check(row, !stall.reached(), ":stallable reported nothing while it was stalled");
		check(row, stall.await(30000) && stall.unavailable && stall.result == null,
		    ":stallable ended host-unavailable with no fabricated result");
		check(row, !host.isProcessUp(STALLABLE), ":stallable is not up");
		check(row, host.isProcessUp(WORKER) && host.isProcessUp(SECOND), "the non-stalled processes are up");
		check(row, ticks >= 2, "keepalive ticks continued through the stall (" + ticks + ")");
		String[] secondStatus = host.statusProcess(SECOND);
		int secondPid = secondStatus == null ? -1 : Integer.parseInt(secondStatus[2]);
		host.destroyService(WORKER, SvcWorker.class.getName());
		host.destroyService(WORKER, SvcWorker2.class.getName());
		host.destroyService(SECOND, SvcSecond.class.getName());
		check(row, awaitProcessDown(WORKER, 30000), "the shared :worker child was released with its last service");
		check(row, awaitProcessDown(SECOND, 30000), ":second was released");
		check(row, workerPid > 0 && awaitProcGone(workerPid, 20000), "the :worker child pid is gone from /proc");
		check(row, secondPid > 0 && awaitProcGone(secondPid, 20000), "the :second child pid is gone from /proc");
		check(row, !host.isProcessUp(STALLABLE), ":stallable stayed down");
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

	/*
	 * Rows that check the child side read the loader's own log: the child
	 * inherits the loader's stdout/stderr, so the runner's redirect target (the
	 * file named by ATL_PROBE_LOG) carries both sides of a spawn.
	 */

	/** The row's own log path, set by the row in run-matrix.sh. */
	String requireLogPath(String row) throws IOException {
		String path = System.getenv("ATL_PROBE_LOG");
		check(row, path != null, "the row ran with ATL_PROBE_LOG set");
		check(row, new File(path).isFile(), "the row log is a readable file: " + path);
		return path;
	}

	/**
	 * The log from this attempt's own PROBE start line on: a re-run appends to
	 * the same file, so only lines after the loader's last scenario banner
	 * belong to the attempt being asserted.
	 */
	static String attemptText(String text, String row) {
		int at = text.lastIndexOf("PROBE start scenario=" + row + " ");
		return at < 0 ? text : text.substring(at);
	}

	/** Whole file as ISO-8859-1 (one char per byte, so offsets are byte offsets). */
	static String readWholeFile(String path) throws IOException {
		FileInputStream in = new FileInputStream(path);
		try {
			byte[] data = new byte[4096];
			StringBuilder text = new StringBuilder();
			int n;
			while ((n = in.read(data)) > 0)
				text.append(new String(data, 0, n, "ISO-8859-1"));
			return text.toString();
		} finally {
			in.close();
		}
	}

	/** Offset of the last line containing both {@code marker} and {@code process=<name>}, or -1. */
	static int lastChildLine(String text, String marker, String processName) {
		int found = -1;
		for (int i = 0; i <= text.length(); ) {
			int nl = text.indexOf('\n', i);
			String line = nl < 0 ? text.substring(i) : text.substring(i, nl);
			if (line.contains(marker) && line.contains("process=" + processName))
				found = i;
			if (nl < 0)
				break;
			i = nl + 1;
		}
		return found;
	}

	/** That line's text, or null. */
	static String lineContaining(String text, String marker, String processName) {
		int at = lastChildLine(text, marker, processName);
		if (at < 0)
			return null;
		int nl = text.indexOf('\n', at);
		return nl < 0 ? text.substring(at) : text.substring(at, nl);
	}

	/** The pid= field of the last child line carrying {@code marker}, or -1. */
	static int pidOfLine(String text, String marker, String processName) {
		String line = lineContaining(text, marker, processName);
		return line == null ? -1 : pidField(line);
	}

	static int pidField(String line) {
		int at = line.indexOf("pid=");
		if (at < 0)
			return -1;
		int end = at + 4;
		while (end < line.length() && Character.isDigit(line.charAt(end)))
			end++;
		try {
			return Integer.parseInt(line.substring(at + 4, end));
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Every distinct pid named by a line containing {@code marker}, in log order. */
	static List<Integer> pidsIn(String text, String marker) {
		List<Integer> pids = new ArrayList<Integer>();
		for (int i = 0; i <= text.length(); ) {
			int nl = text.indexOf('\n', i);
			String line = nl < 0 ? text.substring(i) : text.substring(i, nl);
			if (line.contains(marker)) {
				int pid = pidField(line);
				if (pid > 0 && !pids.contains(Integer.valueOf(pid)))
					pids.add(Integer.valueOf(pid));
			}
			if (nl < 0)
				break;
			i = nl + 1;
		}
		return pids;
	}

	static int fdCount() {
		String[] fds = new File("/proc/self/fd").list();
		return fds == null ? -1 : fds.length;
	}

	/** Bounded wait for a reaped child: its /proc entry is gone. */
	boolean awaitProcGone(int pid, long ms) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		for (;;) {
			if (!new File("/proc/" + pid).exists())
				return true;
			if (System.currentTimeMillis() >= deadline)
				return false;
			Thread.sleep(100);
		}
	}

	/** Bounded wait for this attempt's log to carry a child line matching marker+process. */
	boolean awaitLogLine(String path, String marker, String processName, long ms) throws IOException, InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		for (;;) {
			String text = attemptText(readWholeFile(path), scenario);
			if (lastChildLine(text, marker, processName) >= 0)
				return true;
			if (System.currentTimeMillis() >= deadline)
				return false;
			Thread.sleep(100);
		}
	}

	/** Main-looper process-death events of this row, in arrival order. */
	static final class DeathRecorder implements ATLProcessBackend.ProcessDeathListener {
		private final List<String> names = new ArrayList<String>();
		private final List<Integer> generations = new ArrayList<Integer>();

		public synchronized void onProcessDied(String processName, int generation) {
			names.add(processName);
			generations.add(Integer.valueOf(generation));
		}

		synchronized int count() {
			return names.size();
		}

		synchronized String name(int index) {
			return index < names.size() ? names.get(index) : null;
		}

		synchronized int generation(int index) {
			return index < generations.size() ? generations.get(index).intValue() : -1;
		}

		boolean awaitCount(int n, long ms) throws InterruptedException {
			long deadline = System.currentTimeMillis() + ms;
			while (count() < n && System.currentTimeMillis() < deadline)
				Thread.sleep(50);
			return count() >= n;
		}
	}
}
