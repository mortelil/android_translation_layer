package android.webkit;

/**
 * ATL WebSettings implementation.
 *
 * <p>An instance is bound to one {@link WebView} (AOSP: a WebSettings is per-WebView) and
 * delegates to that WebView's real WebKitGTK 6.0 {@code WebKitSettings} through native
 * methods. The backend is the only authority for the values that are reported back, so a
 * setter/getter round trip observes what the engine actually holds.
 *
 * <p>Gating: {@code WebView}'s native constructor only creates a real
 * {@code WebKitWebView} when the environment variable {@code ATL_UGLY_ENABLE_WEBVIEW} is
 * set; otherwise it degrades to a plain {@code View}, i.e. there is no settings object at
 * all. In that (default) mode every setter below is a no-op and the getters report the
 * backend's absence (false) or the engine default user agent - the honest state, never a
 * fabricated value. {@link #getDefaultUserAgent(android.content.Context)} and
 * {@link #getUserAgentString()} still return the real WebKitGTK engine user agent string
 * because that value comes from the linked engine, not from a live view.
 *
 * <p>Settings without a WebKitGTK 6.0 equivalent are explicit, documented no-ops
 * ({@link #NOT_SUPPORTED}), never pretend implementations.
 */
public class WebSettings {

	/** Settings this backend has no equivalent for; reported once per method, never faked. */
	private static final String NOT_SUPPORTED =
		" is NOT_SUPPORTED (no WebKitGTK 6.0 equivalent); the call is ignored";

	private static final java.util.HashSet<String> notSupportedReported = new java.util.HashSet<String>();

	private static void reportNotSupported(String method) {
		synchronized (notSupportedReported) {
			if (notSupportedReported.add(method))
				System.err.println("ATL WebSettings: " + method + NOT_SUPPORTED);
		}
	}

	public static enum LayoutAlgorithm {
		NORMAL,
		NARROW_COLUMNS,
	}

	public static enum RenderPriority {
		HIGH,
	}

	private final WebView webView;

	WebSettings(WebView webView) {
		this.webView = webView;
	}

	/** Native handle of the bound WebView (0 when detached); only meaningful to native code. */
	private long widget() {
		return webView != null ? webView.widget : 0;
	}

	/* ------------------------------------------------------------------ *
	 * Implemented against WebKitGTK 6.0 WebKitSettings (real backend).
	 * ------------------------------------------------------------------ */

	public static String getDefaultUserAgent(android.content.Context context) {
		return nativeGetDefaultUserAgent();
	}

	public String getUserAgentString() {
		return nativeGetUserAgent(widget());
	}

	public void setUserAgentString(String userAgentString) {
		nativeSetUserAgent(widget(), userAgentString);
	}

	public void setJavaScriptEnabled(boolean javaScriptEnabled) {
		nativeSetJavaScriptEnabled(widget(), javaScriptEnabled);
	}

	public boolean getJavaScriptEnabled() {
		return nativeGetJavaScriptEnabled(widget());
	}

	public void setDomStorageEnabled(boolean flag) {
		nativeSetDomStorageEnabled(widget(), flag);
	}

	public boolean getDomStorageEnabled() {
		return nativeGetDomStorageEnabled(widget());
	}

	public void setDatabaseEnabled(boolean enabled) {
		nativeSetDatabaseEnabled(widget(), enabled);
	}

	public boolean getDatabaseEnabled() {
		return nativeGetDatabaseEnabled(widget());
	}

	public void setLoadsImagesAutomatically(boolean loadsImagesAutomatically) {
		nativeSetLoadsImagesAutomatically(widget(), loadsImagesAutomatically);
	}

	public boolean getLoadsImagesAutomatically() {
		return nativeGetLoadsImagesAutomatically(widget());
	}

	public void setMediaPlaybackRequiresUserGesture(boolean require) {
		nativeSetMediaPlaybackRequiresUserGesture(widget(), require);
	}

	public boolean getMediaPlaybackRequiresUserGesture() {
		return nativeGetMediaPlaybackRequiresUserGesture(widget());
	}

	public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
		nativeSetJavaScriptCanOpenWindowsAutomatically(widget(), flag);
	}

	public boolean getJavaScriptCanOpenWindowsAutomatically() {
		return nativeGetJavaScriptCanOpenWindowsAutomatically(widget());
	}

	/** Backed by WebKitSettings:allow-file-access-from-file-urls. */
	public void setAllowFileAccessFromFileURLs(boolean allow) {
		nativeSetAllowFileAccessFromFileURLs(widget(), allow);
	}

	public boolean getAllowFileAccessFromFileURLs() {
		return nativeGetAllowFileAccessFromFileURLs(widget());
	}

	/** Backed by WebKitSettings:allow-universal-access-from-file-urls. */
	public void setAllowUniversalAccessFromFileURLs(boolean allow) {
		nativeSetAllowUniversalAccessFromFileURLs(widget(), allow);
	}

	public boolean getAllowUniversalAccessFromFileURLs() {
		return nativeGetAllowUniversalAccessFromFileURLs(widget());
	}

	/**
	 * Tells the WebView whether it needs to set a node to have focus when it is focused.
	 * Default true. Real effect: when the flag is set, the native side focuses the first
	 * editable node of the loaded document (on load-finished and when GTK focus enters the
	 * view); when it is false no node is focused, the view keeps focus itself.
	 * AOSP declares no getter for this setting.
	 */
	public void setNeedInitialFocus(boolean flag) {
		nativeSetNeedInitialFocus(widget(), flag);
	}

	/* ------------------------------------------------------------------ *
	 * NOT_SUPPORTED: no WebKitGTK 6.0 equivalent. Explicit no-ops.
	 * ------------------------------------------------------------------ */

	/**
	 * NOT_SUPPORTED: WebKitGTK has no per-view switch that disables {@code file://}
	 * loads; it only exposes allow-file-access-from-file-urls. The getter reports the
	 * real backend state (file access is always available on ATL), not the requested one.
	 */
	public void setAllowFileAccess(boolean allowFileAccess) {
		reportNotSupported("setAllowFileAccess(" + allowFileAccess + ")");
	}

	public boolean getAllowFileAccess() {
		return true;
	}

	/** NOT_SUPPORTED: no network-blocking setting in WebKitSettings; the getter reports the real state. */
	public void setBlockNetworkLoads(boolean block) {
		reportNotSupported("setBlockNetworkLoads(" + block + ")");
	}

	public boolean getBlockNetworkLoads() {
		return false;
	}

	/** NOT_SUPPORTED: WebKitGTK only offers auto_load_images for all images; there is no network-image-only switch. */
	public void setBlockNetworkImage(boolean block) {
		reportNotSupported("setBlockNetworkImage(" + block + ")");
	}

	public boolean getBlockNetworkImage() {
		return false;
	}

	/** NOT_SUPPORTED: multiple windows are controlled by the WebKitWebView "create" signal, not by a setting. */
	public void setSupportMultipleWindows(boolean supportMultipleWindows) {
		reportNotSupported("setSupportMultipleWindows(" + supportMultipleWindows + ")");
	}

	public boolean supportMultipleWindows() {
		return false;
	}

	/** NOT_SUPPORTED: the WebKitGTK offline web application cache setting has no effect (ApplicationCache is gone). */
	public void setAppCacheEnabled(boolean enabled) {
		reportNotSupported("setAppCacheEnabled(" + enabled + ")");
	}

	public void setAppCachePath(String path) {
		reportNotSupported("setAppCachePath(" + path + ")");
	}

	public void setAppCacheMaxSize(long size) {
		reportNotSupported("setAppCacheMaxSize(" + size + ")");
	}

	/* ------------------------------------------------------------------ *
	 * Remaining API-compatibility stubs (outside the implemented subset).
	 * ------------------------------------------------------------------ */

	public void setSavePassword(boolean savePassword) {}

	public void setGeolocationEnabled(boolean enabled) {}

	public void setCacheMode(int dummy) {}

	public void setLayoutAlgorithm(LayoutAlgorithm layoutAlgorithm) {}

	public void setBuiltInZoomControls(boolean builtInZoomControls) {}

	public void setDisplayZoomControls(boolean displayZoomControls) {}

	public void setSupportZoom(boolean supportZoom) {}

	public void setUseWideViewPort(boolean useWideViewPort) {}

	public void setTextZoom(int textZoom) {}

	public void setLoadWithOverviewMode(boolean overview) {}

	public void setRenderPriority(RenderPriority priority) {}

	public void setMixedContentMode(int mode) {}

	public void setDatabasePath(String path) {}

	public void setGeolocationDatabasePath(String path) {}

	public void setSaveFormData(boolean save) {}

	public void setAllowContentAccess(boolean allow) {}

	/* ------------------------------------------------------------------ *
	 * Native backend (src/api-impl-jni/widgets/android_webkit_WebView.c).
	 * Every call is a no-op when there is no real WebKitWebView behind the handle.
	 * ------------------------------------------------------------------ */

	private static native String nativeGetDefaultUserAgent();
	private static native String nativeGetUserAgent(long widget);
	private static native void nativeSetUserAgent(long widget, String userAgentString);
	private static native void nativeSetJavaScriptEnabled(long widget, boolean enabled);
	private static native boolean nativeGetJavaScriptEnabled(long widget);
	private static native void nativeSetDomStorageEnabled(long widget, boolean enabled);
	private static native boolean nativeGetDomStorageEnabled(long widget);
	private static native void nativeSetDatabaseEnabled(long widget, boolean enabled);
	private static native boolean nativeGetDatabaseEnabled(long widget);
	private static native void nativeSetLoadsImagesAutomatically(long widget, boolean enabled);
	private static native boolean nativeGetLoadsImagesAutomatically(long widget);
	private static native void nativeSetMediaPlaybackRequiresUserGesture(long widget, boolean require);
	private static native boolean nativeGetMediaPlaybackRequiresUserGesture(long widget);
	private static native void nativeSetJavaScriptCanOpenWindowsAutomatically(long widget, boolean enabled);
	private static native boolean nativeGetJavaScriptCanOpenWindowsAutomatically(long widget);
	private static native void nativeSetAllowFileAccessFromFileURLs(long widget, boolean allow);
	private static native boolean nativeGetAllowFileAccessFromFileURLs(long widget);
	private static native void nativeSetAllowUniversalAccessFromFileURLs(long widget, boolean allow);
	private static native boolean nativeGetAllowUniversalAccessFromFileURLs(long widget);
	private static native void nativeSetNeedInitialFocus(long widget, boolean flag);
}
