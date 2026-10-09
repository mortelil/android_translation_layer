// SPDX-License-Identifier: GPL-3.0-only
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.os.Looper;

public class TestCookies {
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
	private static boolean contains(CookieManager cm, String url, String value) {
		String cookies = cm.getCookie(url);
		return cookies != null && java.util.Arrays.asList(cookies.split("; ")).contains(value);
	}
	public static void run() throws Exception {
		final CookieManager cm = CookieManager.getInstance();
		check(cm == CookieManager.getInstance(), "CookieManager singleton");
		cm.removeAllCookies(null);
		check(!cm.hasCookies(), "empty cookie store");
		cm.setCookie("https://a.example.test/dir/page", "host=one; HttpOnly", null);
		check(contains(cm, "https://a.example.test/dir/next", "host=one"), "default path and HttpOnly retrieval");
		check(cm.getCookie("https://b.a.example.test/dir/next") == null, "host-only isolation");
		check(cm.getCookie("https://a.example.test/elsewhere") == null, "path isolation");
		cm.setCookie("https://a.example.test/", "domain=two; Domain=example.test; Path=/; Max-Age=3600");
		check(contains(cm, "https://b.example.test/", "domain=two"), "parent domain");
		check(cm.getCookie("https://other.test/") == null, "unrelated domain isolation");
		cm.setCookie("https://a.example.test/", "bad=value; Domain=other.test");
		check(cm.getCookie("https://other.test/") == null, "reject cross-domain cookie");
		cm.setCookie("https://a.example.test/", "secure=yes; Secure; Path=/");
		check(contains(cm, "https://a.example.test/", "secure=yes"), "secure cookie stored");
		check(!contains(cm, "http://a.example.test/", "secure=yes"), "secure cookie hidden on HTTP");
		cm.setCookie("http://a.example.test/", "insecure=no; Secure");
		check(!contains(cm, "https://a.example.test/", "insecure=no"), "reject Secure from HTTP");
		cm.setCookie("https://a.example.test/", "secure=gone; Max-Age=0; Path=/; Secure");
		check(!contains(cm, "https://a.example.test/", "secure=yes"), "expiry deletes matching cookie");
		cm.removeSessionCookies(null);
		check(!contains(cm, "https://a.example.test/dir/next", "host=one"), "session deletion");
		check(contains(cm, "https://a.example.test/", "domain=two"), "persistent cookie survives session deletion");
		cm.flush();
		cm.setAcceptCookie(false);
		cm.setCookie("https://a.example.test/", "disabled=yes");
		check(!contains(cm, "https://a.example.test/", "disabled=yes"), "accept policy");
		cm.setAcceptCookie(true);
		final Throwable[] error = new Throwable[1];
		Thread worker = new Thread(new Runnable() {
			@Override public void run() {
				try {
					cm.setCookie("https://callback.example.test/", "worker=ok", null);
					Looper.prepare();
					final Thread caller = Thread.currentThread();
					final int[] calls = {0};
					cm.setCookie("https://callback.example.test/", "callback=ok", new ValueCallback<Boolean>() {
						@Override public void onReceiveValue(Boolean success) {
							try {
								check(success && caller == Thread.currentThread(), "callback result and calling Looper");
								calls[0]++;
							} finally { Looper.myLooper().quit(); }
						}
					});
					check(calls[0] == 0, "callback must be queued");
					Looper.loop();
					check(calls[0] == 1, "callback exactly once");
				} catch (Throwable failure) { error[0] = failure; }
			}
		});
		worker.start(); worker.join(5000);
		check(!worker.isAlive(), "cookie callback timeout");
		if (error[0] != null) throw new AssertionError(error[0]);
		cm.removeAllCookies(null);
		check(!cm.hasCookies(), "all cookies deleted");
		System.out.println("PASS: cookies, domain/path isolation, HTTPS, expiry, policy and callback Looper");
	}
}
