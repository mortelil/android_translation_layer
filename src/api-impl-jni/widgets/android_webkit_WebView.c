#include <gio/gunixinputstream.h>
#include <gtk/gtk.h>
#include <webkit/webkit.h>

#include <androidfw/androidfw_c_api.h>

#include "../defines.h"
#include "../util.h"

#include "../AssetInputStream.h"
#include "WrapperWidget.h"

#include "../generated_headers/android_view_View.h"
#include "../generated_headers/android_webkit_WebView.h"
#include "../generated_headers/android_webkit_WebSettings.h"

/*
 * android.webkit.WebSettings backend.
 *
 * Settings are delegated to the WebKitSettings object of the bound WebKitWebView. The
 * WebView only creates a real WebKitWebView when ATL_UGLY_ENABLE_WEBVIEW is set; without
 * it (the default) the handle is a plain View widget, so every call below is a no-op and
 * the getters return the backend's absence (FALSE) or the engine default user agent.
 */

/* NULL unless the handle really is a WebKitWebView (i.e. the webview is enabled). */
static WebKitSettings *webview_settings_from_ptr(jlong widget_ptr)
{
	GtkWidget *widget = _PTR(widget_ptr);
	if (widget == NULL || !WEBKIT_IS_WEB_VIEW(widget))
		return NULL;
	return webkit_web_view_get_settings(WEBKIT_WEB_VIEW(widget));
}

static jstring default_user_agent_string(JNIEnv *env)
{
	WebKitSettings *settings = webkit_settings_new();
	const gchar *user_agent = webkit_settings_get_user_agent(settings);
	jstring result = _JSTRING(user_agent ? user_agent : "");
	g_object_unref(settings);
	return result;
}

/*
 * setNeedInitialFocus: AOSP default is true; there is no getter. Stored on the web view
 * (unset == true), 1 == true, 2 == false (so that FALSE is distinguishable from unset).
 */
#define ATL_NEED_INITIAL_FOCUS_KEY "atl-need-initial-focus"

static gboolean web_view_need_initial_focus(WebKitWebView *web_view)
{
	gpointer value = g_object_get_data(G_OBJECT(web_view), ATL_NEED_INITIAL_FOCUS_KEY);
	return value == NULL || GPOINTER_TO_INT(value) == 1;
}

/*
 * Focuses the first editable node of the current document, unless an editable node
 * already has focus. This is what "needs to set a node to have focus" means for a real
 * engine: with the flag false the injection is skipped and nothing is focused.
 */
static const char atl_initial_focus_script[] =
	"(function(){"
	"var a=document.activeElement;"
	"if(a&&a!==document.body&&a!==document.documentElement&&"
	"(/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)||a.isContentEditable))return false;"
	"var els=document.querySelectorAll("
	"'input:not([type=hidden]):not([disabled]),textarea:not([disabled]):not([readonly]),"
	"select:not([disabled]),[contenteditable=\"true\"],[contenteditable=\"\"]');"
	"for(var i=0;i<els.length;i++){var el=els[i];el.focus();"
	"try{if(el.setSelectionRange)el.setSelectionRange(el.value.length,el.value.length);}catch(e){}"
	"return true;}"
	"return false;})()";

static void apply_initial_focus(WebKitWebView *web_view)
{
	webkit_web_view_evaluate_javascript(web_view, atl_initial_focus_script, -1,
			NULL, NULL, NULL, NULL, NULL);
}

static void web_view_focus_enter(GtkEventControllerFocus *controller, gpointer user_data)
{
	GtkWidget *widget = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(controller));
	if (widget && WEBKIT_IS_WEB_VIEW(widget) && web_view_need_initial_focus(WEBKIT_WEB_VIEW(widget)))
		apply_initial_focus(WEBKIT_WEB_VIEW(widget));
}


static void asset_uri_scheme_request_cb(WebKitURISchemeRequest *request, gpointer user_data)
{
	const gchar *path = webkit_uri_scheme_request_get_path(request);
	if (path == NULL)
		return;
	path += 1; // remove the leading '/'
	JNIEnv *env = get_jni_env();
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(webkit_uri_scheme_request_get_web_view(request))));
	jobject asset_manager_obj = (*env)->CallObjectMethod(env, wrapper->jobj, handle_cache.webview.internalGetAssetManager);
	struct AssetManager *asset_manager = _PTR(_GET_LONG_FIELD(asset_manager_obj, "mObject"));
	struct Asset *asset = AssetManager_openNonAsset(asset_manager, path, ACCESS_STREAMING);
	GInputStream *stream = asset_input_stream_new(asset);
	webkit_uri_scheme_request_finish(request, stream, Asset_getLength(asset), NULL);
	g_object_unref(stream);
}

static void web_view_load_changed(WebKitWebView *web_view, WebKitLoadEvent load_event, gpointer user_data)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(GTK_WIDGET(web_view)));
	JNIEnv *env = get_jni_env();
	(*env)->CallVoidMethod(env, wrapper->jobj, handle_cache.webview.internalLoadChanged, load_event, _JSTRING(webkit_web_view_get_uri(web_view)));
	/* setNeedInitialFocus(true) (the AOSP default): give the page's first editable node focus. */
	if (load_event == WEBKIT_LOAD_FINISHED && web_view_need_initial_focus(web_view))
		apply_initial_focus(web_view);
}

static gboolean decide_policy(WebKitWebView *web_view, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type, WrapperWidget *wrapper)
{
	if (type == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
		WebKitNavigationPolicyDecision *nav = WEBKIT_NAVIGATION_POLICY_DECISION(decision);
		WebKitNavigationAction *action = webkit_navigation_policy_decision_get_navigation_action(nav);
		WebKitURIRequest *request = webkit_navigation_action_get_request(action);
		const gchar *uri = webkit_uri_request_get_uri(request);

		JNIEnv *env = get_jni_env();
		jboolean result = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.webview.internalShouldOverrideUrlLoading, _JSTRING(uri));
		if (result)
			webkit_policy_decision_ignore(decision);
		else
			webkit_policy_decision_use(decision);
		return result;
	}
	return FALSE;
}

JNIEXPORT jlong JNICALL Java_android_webkit_WebView_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	/*
	 * many apps use webview just for fingerprinting or displaying ads, which seems like
	 * a waste of resources even if we deal with fingerprinting and ads in some other way
	 * in the future.
	 */
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return Java_android_view_View_native_1constructor(env, this, context, attrs);

	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *webview = webkit_web_view_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), webview);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);
	wrapper_widget_register_invalidation_listener(WRAPPER_WIDGET(wrapper));
	webkit_web_context_register_uri_scheme(webkit_web_view_get_context(WEBKIT_WEB_VIEW(webview)), "android-asset", asset_uri_scheme_request_cb, NULL, NULL);
	g_signal_connect(G_OBJECT(webview), "load-changed", G_CALLBACK(web_view_load_changed), NULL);
	g_signal_connect(webview, "decide-policy", G_CALLBACK(decide_policy), wrapper);
	/* setNeedInitialFocus also applies when GTK focus enters the view (AOSP: on requestFocus). */
	GtkEventController *focus_controller = gtk_event_controller_focus_new();
	g_signal_connect(G_OBJECT(focus_controller), "enter", G_CALLBACK(web_view_focus_enter), NULL);
	gtk_widget_add_controller(webview, focus_controller);
	return _INTPTR(webview);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1loadUrl(JNIEnv *env, jobject this, jlong widget_ptr, jstring url)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return;

	WebKitWebView *webview = _PTR(widget_ptr);
	webkit_web_view_load_uri(webview, _CSTRING(url));
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1loadDataWithBaseURL(JNIEnv *env, jobject this, jlong widget_ptr, jstring base_url, jstring data_jstr, jstring mime_type, jstring encoding)
{
	if (!getenv("ATL_UGLY_ENABLE_WEBVIEW"))
		return;

	WebKitWebView *webview = _PTR(widget_ptr);
	jsize data_len = (*env)->GetStringUTFLength(env, data_jstr);
	jsize data_jlen = (*env)->GetStringLength(env, data_jstr);
	char *data = malloc(data_len + 1); // + 1 for NUL
	(*env)->GetStringUTFRegion(env, data_jstr, 0, data_jlen, data);
	webkit_web_view_load_bytes(webview, g_bytes_new(data, data_len), mime_type ? _CSTRING(mime_type) : "text/html", encoding ? _CSTRING(encoding) : NULL, base_url ? _CSTRING(base_url) : NULL);
}

/*
 * android.webkit.WebSettings native backend.
 * Implemented: user agent (the real WebKitGTK engine UA), JavaScript, DOM storage,
 * HTML5 database, loads-images, media-playback-requires-user-gesture,
 * JavaScript-opens-windows, file/universal access from file URLs, needInitialFocus.
 * Settings with no WebKitGTK 6.0 equivalent stay no-ops in WebSettings.java.
 */

JNIEXPORT jstring JNICALL Java_android_webkit_WebSettings_nativeGetDefaultUserAgent(JNIEnv *env, jclass clazz)
{
	return default_user_agent_string(env);
}

JNIEXPORT jstring JNICALL Java_android_webkit_WebSettings_nativeGetUserAgent(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (!settings)
		return default_user_agent_string(env);
	const gchar *user_agent = webkit_settings_get_user_agent(settings);
	return _JSTRING(user_agent ? user_agent : "");
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetUserAgent(JNIEnv *env, jclass clazz, jlong widget, jstring user_agent)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (!settings)
		return;

	if (user_agent == NULL) {
		/* AOSP: null restores the default user agent. */
		WebKitSettings *defaults = webkit_settings_new();
		const gchar *default_user_agent = webkit_settings_get_user_agent(defaults);
		webkit_settings_set_user_agent(settings, default_user_agent);
		g_object_unref(defaults);
		return;
	}

	const char *c_user_agent = _CSTRING(user_agent);
	webkit_settings_set_user_agent(settings, c_user_agent);
	(*env)->ReleaseStringUTFChars(env, user_agent, c_user_agent);
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetJavaScriptEnabled(JNIEnv *env, jclass clazz, jlong widget, jboolean enabled)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_enable_javascript(settings, enabled);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetJavaScriptEnabled(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_enable_javascript(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetDomStorageEnabled(JNIEnv *env, jclass clazz, jlong widget, jboolean enabled)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_enable_html5_local_storage(settings, enabled);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetDomStorageEnabled(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_enable_html5_local_storage(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetDatabaseEnabled(JNIEnv *env, jclass clazz, jlong widget, jboolean enabled)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_enable_html5_database(settings, enabled);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetDatabaseEnabled(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_enable_html5_database(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetLoadsImagesAutomatically(JNIEnv *env, jclass clazz, jlong widget, jboolean enabled)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_auto_load_images(settings, enabled);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetLoadsImagesAutomatically(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_auto_load_images(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetMediaPlaybackRequiresUserGesture(JNIEnv *env, jclass clazz, jlong widget, jboolean require)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_media_playback_requires_user_gesture(settings, require);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetMediaPlaybackRequiresUserGesture(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_media_playback_requires_user_gesture(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetJavaScriptCanOpenWindowsAutomatically(JNIEnv *env, jclass clazz, jlong widget, jboolean enabled)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_javascript_can_open_windows_automatically(settings, enabled);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetJavaScriptCanOpenWindowsAutomatically(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_javascript_can_open_windows_automatically(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetAllowFileAccessFromFileURLs(JNIEnv *env, jclass clazz, jlong widget, jboolean allow)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_allow_file_access_from_file_urls(settings, allow);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetAllowFileAccessFromFileURLs(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_allow_file_access_from_file_urls(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetAllowUniversalAccessFromFileURLs(JNIEnv *env, jclass clazz, jlong widget, jboolean allow)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	if (settings)
		webkit_settings_set_allow_universal_access_from_file_urls(settings, allow);
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebSettings_nativeGetAllowUniversalAccessFromFileURLs(JNIEnv *env, jclass clazz, jlong widget)
{
	WebKitSettings *settings = webview_settings_from_ptr(widget);
	return settings ? webkit_settings_get_allow_universal_access_from_file_urls(settings) : FALSE;
}

JNIEXPORT void JNICALL Java_android_webkit_WebSettings_nativeSetNeedInitialFocus(JNIEnv *env, jclass clazz, jlong widget, jboolean flag)
{
	GtkWidget *w = _PTR(widget);
	if (w == NULL || !WEBKIT_IS_WEB_VIEW(w))
		return;
	/* 1 == true, 2 == false; unset means the AOSP default (true). */
	g_object_set_data(G_OBJECT(w), ATL_NEED_INITIAL_FOCUS_KEY, GINT_TO_POINTER(flag ? 1 : 2));
	if (flag) {
		/* apply immediately if a document is already loaded, otherwise on load-finished */
		const gchar *uri = webkit_web_view_get_uri(WEBKIT_WEB_VIEW(w));
		if (uri != NULL)
			apply_initial_focus(WEBKIT_WEB_VIEW(w));
	}
}
