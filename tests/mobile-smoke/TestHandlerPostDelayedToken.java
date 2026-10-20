// SPDX-License-Identifier: GPL-3.0-only
final class TestHandlerPostDelayedToken {
	private TestHandlerPostDelayedToken() {}

	static void run(final Runnable completion) {
		final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
		final Object cancelledToken = new Object();
		final Object retainedToken = new Object();
		final boolean[] cancelledCallbackRan = { false };
		final boolean[] retainedCallbackRan = { false };

		if (!handler.postDelayed(new Runnable() {
			public void run() { cancelledCallbackRan[0] = true; }
		}, cancelledToken, 50)) {
			throw new AssertionError("postDelayed with token should enqueue a callback");
		}
		if (!handler.postDelayed(new Runnable() {
			public void run() { retainedCallbackRan[0] = true; }
		}, retainedToken, 25)) {
			throw new AssertionError("postDelayed with a distinct token should enqueue a callback");
		}

		handler.removeCallbacksAndMessages(cancelledToken);
		handler.postDelayed(new Runnable() {
			public void run() {
				try {
					if (cancelledCallbackRan[0]) {
						throw new AssertionError("removeCallbacksAndMessages(token) did not cancel its callback");
					}
					if (!retainedCallbackRan[0]) {
						throw new AssertionError("Removing one token cancelled a callback with another token");
					}
					System.out.println("PASS: Handler.postDelayed(Runnable, token, delay) and token cancellation");
					completion.run();
				} catch (Throwable failure) {
					failure.printStackTrace();
					System.exit(1);
				}
			}
		}, 100);
	}
}
