package android.webkit;

import android.atl.ATLLoadedApp;
import android.os.Handler;
import android.webkit.WebView;

public class CookieManager {
	private static final CookieManager INSTANCE = new CookieManager();
	private boolean accept = true;

	public static CookieManager getInstance() {
		// HACK: disable NewPipe's WebView based PoToken generator for now
		if (ATLLoadedApp.getPrimaryApplication().pkg.packageName.equals("org.schabi.newpipe")) {
			throw new RuntimeException("CookieManager not yet fully implemented");
		}
		try { // also handle NewPipe forks which can have a different packagename
			ATLLoadedApp.getPrimaryApplication().loadClass(
			    "org.schabi.newpipe.util.potoken.PoTokenWebView");
			throw new RuntimeException("CookieManager not yet fully implemented");
		} catch (ClassNotFoundException e) {
		}
		return INSTANCE;
	}

	private static Handler callbackHandler(ValueCallback<Boolean> callback) {
		// Handler requires the caller's Looper; null callbacks also work on worker threads.
		return callback == null ? null : new Handler();
	}

	private static void complete(Handler handler, final ValueCallback<Boolean> callback, final boolean result) {
		if (handler != null) handler.post(new Runnable() {
			@Override public void run() { callback.onReceiveValue(result); }
		});
	}

	public void removeAllCookies(ValueCallback<Boolean> callback) {
		Handler handler = callbackHandler(callback);
		complete(handler, callback, nativeRemove(0));
	}

	public void removeSessionCookies(ValueCallback<Boolean> callback) {
		Handler handler = callbackHandler(callback);
		complete(handler, callback, nativeRemove(1));
	}

	public void removeExpiredCookie() { nativeRemove(2); }
	public void removeAllCookie() { nativeRemove(0); }
	public void removeSessionCookie() { nativeRemove(1); }
	// The database backend commits each mutation synchronously.
	public void flush() { nativeFlush(); }
	public native String getCookie(String url);
	public native boolean hasCookies();

	public void setCookie(String url, String value) { setCookie(url, value, null); }
	public synchronized void setCookie(String url, String value, ValueCallback<Boolean> callback) {
		Handler handler = callbackHandler(callback);
		boolean result = accept && url != null && value != null && nativeSet(url, value);
		complete(handler, callback, result);
	}

	public synchronized void setAcceptCookie(boolean accept) { this.accept = accept; }
	public synchronized boolean acceptCookie() { return accept; }
	private static native boolean nativeSet(String url, String value);
	private static native boolean nativeRemove(int mode);
	private static native void nativeFlush();

	public boolean acceptThirdPartyCookies(WebView webview) {
		return false;
	}

	public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {}

	public static void setAcceptFileSchemeCookies(boolean accept) {}
}
