package android.webkit;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.ViewGroup;

public class WebView extends ViewGroup {

	/** ATL's WebView implementation lives in the framework loader. Optional
	 * Chromium support-library classes are absent and must not be advertised.
	 */
	public static ClassLoader getWebViewClassLoader() {
		return WebView.class.getClassLoader();
	}


	private WebViewClient webViewClient;
	private WebSettings settings;
	// AOSP: WebView keeps the current page URL and the URL it was originally requested with.
	// The WebKitGTK backend reports both through internalLoadChanged (see below); null means
	// "no page loaded yet", never a fabricated value.
	private String currentUrl;
	private String originalUrl;
	private JavascriptBridge javascriptBridge;
	private boolean destroyed;
	private void checkJavascriptThread() {
		if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
			throw new IllegalStateException("WebView JavaScript API must run on the UI thread");
		if (destroyed) throw new IllegalStateException("WebView destroyed");
	}

	public WebView(Context context) {
		this(context, null);
	}

	public WebView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public WebSettings getSettings() {
		// AOSP returns one stable instance per WebView; the instance is bound to this view
		// so its native methods reach this view's real WebKitSettings.
		if (settings == null)
			settings = new WebSettings(this);
		return settings;
	}

	public void setDownloadListener(DownloadListener downloadListener) {}

	public void setScrollBarStyle(int scrollBarStyle) {}

	public void setWebViewClient(WebViewClient webViewClient) {
		this.webViewClient = webViewClient;
	}

	// to be used by native code
	void internalLoadChanged(int loadState, String url) {
		// WebKitGTK reports this WebKitWebView's current URI with every load event:
		// WEBKIT_LOAD_STARTED (0) carries the URI a new main-frame load was requested with,
		// and the following WEBKIT_LOAD_REDIRECTED (1) / WEBKIT_LOAD_COMMITTED (2) /
		// WEBKIT_LOAD_FINISHED (3) events carry the URI the load actually reached (a redirect
		// replaces it). Keep both, like AOSP's getUrl()/getOriginalUrl() pair.
		if (loadState == /*WEBKIT_LOAD_STARTED*/ 0)
			originalUrl = url;
		currentUrl = url;

		if (loadState == /*WEBKIT_LOAD_STARTED*/ 0 && webViewClient != null) {
			webViewClient.onPageStarted(this, url);
		} else if (loadState == /*WEBKIT_LOAD_FINISHED*/ 3 && webViewClient != null) {
			webViewClient.onPageFinished(this, url);
		}
	}

	/** AOSP: the URL of the current page, or null if no page has been loaded. */
	public String getUrl() {
		return currentUrl;
	}

	/** AOSP: the URL the current page was originally requested with (before redirects). */
	public String getOriginalUrl() {
		return originalUrl;
	}

	boolean internalShouldOverrideUrlLoading(String url) {
		if (webViewClient != null)
			return webViewClient.shouldOverrideUrlLoading(this, url);
		else
			return false;
	}

	public void setVerticalScrollBarEnabled(boolean enabled) {}
	public void setVerticalScrollbarOverlay(boolean overlay) {}
	public void setInitialScale(int scaleInPercent) {}

	public void addJavascriptInterface(Object object, String name) {
		checkJavascriptThread();
		if (object == null) return;
		if (name == null) throw new IllegalArgumentException("name is null");
		if (javascriptBridge == null) javascriptBridge = new JavascriptBridge();
		native_setJavascriptBridge(widget, javascriptBridge, javascriptBridge.update(name, object));
	}

	public void setWebChromeClient(WebChromeClient dummy) {}

	public void removeAllViews() {}

	public void destroy() {
		if (destroyed) return;
		checkJavascriptThread();
		destroyed = true;
		if (javascriptBridge != null) javascriptBridge.destroy();
		native_destroyJavascript(widget);
	}

	public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
		if ("base64".equals(encoding)) {
			data = new String(Base64.decode(data, 0));
		}
		if (mimeType != null && mimeType.contains(";")) {
			mimeType = mimeType.substring(0, mimeType.indexOf(";"));
		}
		// webkit doesn't allow overwriting the file:// uri scheme. So we replace it with the android-asset:// scheme
		data = data.replace("file:///android_asset/", "android-asset:///assets/");
		native_loadDataWithBaseURL(widget, baseUrl, data, mimeType, encoding);
	}

	public void loadUrl(String url) {
		if (url.startsWith("javascript:")) {
			evaluateJavascript(url.substring(11), null);
			return;
		}
		// webkit doesn't allow overwriting the file:// uri scheme. So we replace it with the android-asset:// scheme
		url = url.replace("file:///android_asset/", "android-asset:///assets/");
		native_loadUrl(widget, url);
	}

	public void stopLoading() {}

	// to be used by native code
	AssetManager internalGetAssetManager() {
		return getContext().getResources().getAssets();
	}

	public void onPause() {}

	public void onResume() {}

	public boolean isPaused() {
		return false;
	}

	public void resumeTimers() {}

	public void loadData(String data, String mimeType, String encoding) {
		loadDataWithBaseURL("about:blank", data, mimeType, encoding, "about:blank");
	}

	public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
		checkJavascriptThread();
		if (script == null) throw new IllegalArgumentException("script is null");
		ValueCallback<String> delivery = resultCallback == null ? null : new ValueCallback<String>() {
			@Override public void onReceiveValue(final String value) {
				new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
					@Override public void run() { resultCallback.onReceiveValue(value); }
				});
			}
		};
		native_evaluateJavascript(widget, script, delivery);
	}

	public static void setWebContentsDebuggingEnabled(boolean enabled) {}

	public void clearFormData() {}

	public void clearHistory() {}

	public void clearMatches() {}

	public void clearSslPreferences() {}

	public void clearCache(boolean includeDiskFiles) {}

	// directly accessed by androidx WebViewGlueCommunicator to get ClassLoader
	private static Object getFactory() {
		return new Object();
	}

	public void removeJavascriptInterface(String name) {
		checkJavascriptThread();
		if (javascriptBridge != null)
			native_setJavascriptBridge(widget, javascriptBridge, javascriptBridge.update(name, null));
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	private native void native_loadDataWithBaseURL(long widget, String baseUrl, String data, String mimeType, String encoding);
	private native void native_loadUrl(long widget, String url);
	private native void native_evaluateJavascript(long widget, String script, ValueCallback<String> callback);
	private native void native_setJavascriptBridge(long widget, JavascriptBridge bridge, String script);
	private native void native_destroyJavascript(long widget);
}
