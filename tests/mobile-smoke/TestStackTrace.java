// SPDX-License-Identifier: GPL-3.0-only
public final class TestStackTrace {
	private static void check() {
		Throwable first = new Throwable();
		Throwable second = new Throwable();
		StackTraceElement a = first.getStackTrace()[0];
		StackTraceElement b = second.getStackTrace()[0];
		if (!"TestStackTrace".equals(a.getClassName()) || !"check".equals(a.getMethodName()) ||
		    !"TestStackTrace.java".equals(a.getFileName()) || a.getLineNumber() <= 0 ||
		    b.getLineNumber() != a.getLineNumber() + 1 || a.isNativeMethod())
			throw new AssertionError("Incorrect Java stack source location: " + a + " / " + b);
	}

	public static void run() throws InterruptedException {
		for (int i = 0; i < 2000; i++) check();
		final Throwable[] failure = new Throwable[2];
		Thread[] threads = new Thread[2];
		for (int i = 0; i < threads.length; i++) {
			final int index = i;
			threads[i] = new Thread(new Runnable() {
				public void run() {
					try { for (int j = 0; j < 200; j++) check(); }
					catch (Throwable error) { failure[index] = error; }
				}
			});
			threads[i].start();
		}
		for (Thread thread : threads) thread.join();
		for (Throwable error : failure)
			if (error != null) throw new AssertionError("Worker stack trace failed", error);
		System.out.println("PASS: repeated and concurrent Java stack traces retain method, file and line locations");
	}
}
