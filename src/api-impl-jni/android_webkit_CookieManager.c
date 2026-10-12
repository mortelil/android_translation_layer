// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <webkit/webkit.h>
#include "WebViewSession.h"
#include <libsoup/soup.h>
#include <glib/gstdio.h>
#include <sys/stat.h>
#include <string.h>
#include "util.h"

/* WebKit is the authority when experimental WebView is enabled. All WebViews
 * in one ATL process share this app-private session, never the desktop default.
 * Only GTK's main thread may create/access the session. */
extern GThread *main_thread_id;
static WebKitNetworkSession *web_session;

WebKitNetworkSession *atl_webview_session(void)
{
    if (web_session) return web_session;
    const char *base = get_app_data_dir();
    g_autofree char *data = base ? g_build_filename(base, "atl-webview", NULL) : NULL;
    g_autofree char *cache = data ? g_build_filename(data, "cache", NULL) : NULL;
    if (!data || g_mkdir_with_parents(data, 0700) || g_chmod(data, 0700)) return NULL;
    web_session = webkit_network_session_new(data, cache);
    g_autofree char *cookies = g_build_filename(data, "cookies.sqlite", NULL);
    WebKitCookieManager *manager = webkit_network_session_get_cookie_manager(web_session);
    webkit_cookie_manager_set_persistent_storage(manager, cookies, WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
    webkit_cookie_manager_set_accept_policy(manager, WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY);
    return web_session;
}

typedef enum { COOKIE_GET, COOKIE_ALL, COOKIE_SET, COOKIE_DELETE, COOKIE_POLICY } CookieOperation;
typedef struct {
    CookieOperation op;
    const char *url;
    SoupCookie *cookie;
    gboolean accept, success, done;
    GList *cookies;
    GError *error;
    GCancellable *cancel;
    GMutex mutex;
    GCond cond;
} CookieCall;

static void cookie_complete(CookieCall *call)
{
    g_mutex_lock(&call->mutex);
    call->done = TRUE;
    g_cond_signal(&call->cond);
    g_mutex_unlock(&call->mutex);
}

static void cookie_result(GObject *object, GAsyncResult *result, gpointer data)
{
    CookieCall *call = data;
    WebKitCookieManager *manager = WEBKIT_COOKIE_MANAGER(object);
    switch (call->op) {
    case COOKIE_GET:
        call->cookies = webkit_cookie_manager_get_cookies_finish(manager, result, &call->error); break;
    case COOKIE_ALL:
        call->cookies = webkit_cookie_manager_get_all_cookies_finish(manager, result, &call->error); break;
    case COOKIE_SET:
        call->success = webkit_cookie_manager_add_cookie_finish(manager, result, &call->error); break;
    case COOKIE_DELETE:
        call->success = webkit_cookie_manager_delete_cookie_finish(manager, result, &call->error); break;
    default: g_assert_not_reached();
    }
    cookie_complete(call);
}

static gboolean cookie_start(gpointer data)
{
    CookieCall *call = data;
    WebKitNetworkSession *session = atl_webview_session();
    if (!session) {
        call->error = g_error_new_literal(G_IO_ERROR, G_IO_ERROR_FAILED, "Cookie session unavailable");
        cookie_complete(call);
        return G_SOURCE_REMOVE;
    }
    WebKitCookieManager *manager = webkit_network_session_get_cookie_manager(session);
    switch (call->op) {
    case COOKIE_GET: webkit_cookie_manager_get_cookies(manager, call->url, call->cancel, cookie_result, call); break;
    case COOKIE_ALL: webkit_cookie_manager_get_all_cookies(manager, call->cancel, cookie_result, call); break;
    case COOKIE_SET: webkit_cookie_manager_add_cookie(manager, call->cookie, call->cancel, cookie_result, call); break;
    case COOKIE_DELETE: webkit_cookie_manager_delete_cookie(manager, call->cookie, call->cancel, cookie_result, call); break;
    case COOKIE_POLICY:
        webkit_cookie_manager_set_accept_policy(manager, call->accept ?
            WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY : WEBKIT_COOKIE_POLICY_ACCEPT_NEVER);
        call->success = TRUE;
        cookie_complete(call);
        break;
    }
    return G_SOURCE_REMOVE;
}

static gboolean cookie_timeout(gpointer data)
{
    g_cancellable_cancel(((CookieCall *)data)->cancel);
    return G_SOURCE_REMOVE;
}

/* Android's synchronous APIs must also work on its main thread. There we pump
 * GLib until WebKit's async reply arrives (reentrant, as other GTK bridges are).
 * Workers wait on a condition and never dispatch GTK themselves. Do not hold a
 * Java monitor across this call: the nested loop can deliver Java callbacks. */
static gboolean cookie_call(JNIEnv *env, CookieCall *call)
{
    g_mutex_init(&call->mutex);
    g_cond_init(&call->cond);
    call->cancel = g_cancellable_new();
    GSource *timeout = g_timeout_source_new_seconds(15);
    g_source_set_callback(timeout, cookie_timeout, call, NULL);
    g_source_attach(timeout, g_main_context_default());
    if (g_thread_self() == main_thread_id) {
        cookie_start(call);
        while (!call->done) g_main_context_iteration(NULL, TRUE);
    } else {
        GSource *source = g_idle_source_new();
        g_source_set_callback(source, cookie_start, call, NULL);
        g_source_attach(source, g_main_context_default());
        g_source_unref(source);
        g_mutex_lock(&call->mutex);
        while (!call->done) g_cond_wait(&call->cond, &call->mutex);
        g_mutex_unlock(&call->mutex);
    }
    g_source_destroy(timeout);
    g_source_unref(timeout);
    g_object_unref(call->cancel);
    g_cond_clear(&call->cond);
    g_mutex_clear(&call->mutex);
    if (call->error) {
        // Error text, URLs and cookie contents may contain credentials.
        g_printerr("ATL WebView: cookie operation %d failed (code %d; details redacted)\n", call->op, call->error->code);
        g_clear_error(&call->error);
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "WebView cookie operation failed");
        return FALSE;
    }
    return TRUE;
}

JNIEXPORT void JNICALL Java_android_webkit_CookieManager_nativeAccept(JNIEnv *env, jclass cls, jboolean accept)
{
    if (!getenv("ATL_UGLY_ENABLE_WEBVIEW")) return;
    CookieCall call = { .op = COOKIE_POLICY, .accept = accept };
    cookie_call(env, &call);
}

/* This HTTP cookie store is separate from the experimental WebKit WebView.
 * libsoup owns parsing, expiry, host-only/domain/path matching and persistence.
 * Serialize access because callers include OkHttp worker threads. */
static GMutex cookie_lock;
static SoupCookieJar *jar;

static gboolean ensure_jar(JNIEnv *env)
{
	if (jar) return TRUE;
	const char *base = get_app_data_dir();
	g_autofree char *dir = base ? g_build_filename(base, "atl-cookies", NULL) : NULL;
	if (!dir || g_mkdir_with_parents(dir, 0700) != 0 || g_chmod(dir, 0700) != 0) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Cannot create app cookie directory");
		return FALSE;
	}
	g_autofree char *path = g_build_filename(dir, "cookies.sqlite", NULL);
	jar = soup_cookie_jar_db_new(path, FALSE);
	return jar != NULL;
}

static GUri *parse_url(const char *url)
{
	GUri *uri = g_uri_parse(url, SOUP_HTTP_URI_FLAGS, NULL);
	if (uri && g_uri_get_host(uri) &&
	    (!g_ascii_strcasecmp(g_uri_get_scheme(uri), "http") || !g_ascii_strcasecmp(g_uri_get_scheme(uri), "https")))
		return uri;
	if (uri) g_uri_unref(uri);
	return NULL;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_CookieManager_nativeSet(JNIEnv *env, jclass cls, jstring url, jstring value)
{
	const char *u = (*env)->GetStringUTFChars(env, url, NULL);
	if (!u) return FALSE;
	GUri *uri = parse_url(u);
	(*env)->ReleaseStringUTFChars(env, url, u);
	if (!uri) return FALSE;
	const char *v = (*env)->GetStringUTFChars(env, value, NULL);
	if (!v) { g_uri_unref(uri); return FALSE; }
	SoupCookie *cookie = soup_cookie_parse(v, uri);
	// Reject partitioned cookies until the store can preserve their isolation.
	g_auto(GStrv) attributes = g_strsplit(v, ";", -1);
	gboolean partitioned = FALSE;
	for (int i = 1; attributes[i]; i++) {
		char *eq = strchr(attributes[i], '=');
		if (eq) *eq = 0;
		if (!g_ascii_strcasecmp(g_strstrip(attributes[i]), "Partitioned")) partitioned = TRUE;
	}
	(*env)->ReleaseStringUTFChars(env, value, v);
	gboolean accepted = FALSE;
	if (cookie && !partitioned) {
		const char *domain = soup_cookie_get_domain(cookie);
		const char *host = g_uri_get_host(uri);
		gboolean secure = !g_ascii_strcasecmp(g_uri_get_scheme(uri), "https");
		if (domain && soup_cookie_domain_matches(cookie, host) &&
		    !(domain[0] == '.' && soup_tld_domain_is_public_suffix(domain + 1)) &&
		    (!soup_cookie_get_secure(cookie) || secure)) {
			if (getenv("ATL_UGLY_ENABLE_WEBVIEW")) {
                CookieCall call = { .op = COOKIE_SET, .cookie = cookie };
                accepted = cookie_call(env, &call) && call.success;
            } else {
            g_mutex_lock(&cookie_lock);
			if (ensure_jar(env)) {
				// add_cookie_full performs prefix and secure-cookie overwrite checks.
				SoupCookie *expected = soup_cookie_copy(cookie);
				soup_cookie_jar_add_cookie_full(jar, cookie, uri, uri);
				cookie = NULL;
				GDateTime *expiry = soup_cookie_get_expires(expected);
				gboolean expired = expiry && g_date_time_to_unix(expiry) <= g_get_real_time() / G_USEC_PER_SEC;
				GSList *cookies = soup_cookie_jar_all_cookies(jar);
				gboolean found = FALSE;
				for (GSList *it = cookies; it; it = it->next) {
					SoupCookie *stored = it->data;
					if (!strcmp(soup_cookie_get_name(stored), soup_cookie_get_name(expected)) &&
					    !strcmp(soup_cookie_get_domain(stored), soup_cookie_get_domain(expected)) &&
					    !strcmp(soup_cookie_get_path(stored), soup_cookie_get_path(expected))) {
						found = TRUE;
						accepted = soup_cookie_equal(stored, expected);
					}
				}
				if (expired) accepted = !found;
				soup_cookies_free(cookies);
				soup_cookie_free(expected);
			}
			g_mutex_unlock(&cookie_lock);
            }
		}
	}
	if (cookie) soup_cookie_free(cookie);
	g_uri_unref(uri);
	return accepted;
}

JNIEXPORT jstring JNICALL Java_android_webkit_CookieManager_getCookie(JNIEnv *env, jobject self, jstring url)
{
	if (!url) return NULL;
	const char *u = (*env)->GetStringUTFChars(env, url, NULL);
	if (!u) return NULL;
	GUri *uri = parse_url(u);
	(*env)->ReleaseStringUTFChars(env, url, u);
	if (!uri) return NULL;
    if (getenv("ATL_UGLY_ENABLE_WEBVIEW")) {
        g_autofree char *uri_text = g_uri_to_string(uri);
        CookieCall call = { .op = COOKIE_GET, .url = uri_text };
        gboolean ok = cookie_call(env, &call);
        GString *header = g_string_new(NULL);
        for (GList *it = call.cookies; it; it = it->next) {
            SoupCookie *cookie = it->data;
            if (header->len) g_string_append(header, "; ");
            g_string_append_printf(header, "%s=%s", soup_cookie_get_name(cookie), soup_cookie_get_value(cookie));
        }
        jstring result = ok && header->len ? (*env)->NewStringUTF(env, header->str) : NULL;
        g_string_free(header, TRUE);
        g_list_free_full(call.cookies, (GDestroyNotify)soup_cookie_free);
        g_uri_unref(uri);
        return result;
    }
	g_mutex_lock(&cookie_lock);
	char *cookies = ensure_jar(env) ? soup_cookie_jar_get_cookies(jar, uri, TRUE) : NULL;
	g_mutex_unlock(&cookie_lock);
	g_uri_unref(uri);
	jstring result = cookies ? (*env)->NewStringUTF(env, cookies) : NULL;
	g_free(cookies);
	return result;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_CookieManager_nativeRemove(JNIEnv *env, jclass cls, jint mode)
{
    if (getenv("ATL_UGLY_ENABLE_WEBVIEW")) {
        CookieCall all = { .op = COOKIE_ALL };
        if (!cookie_call(env, &all)) return FALSE;
        gboolean removed = FALSE;
        for (GList *it = all.cookies; it; it = it->next) {
            SoupCookie *cookie = it->data;
            GDateTime *expiry = soup_cookie_get_expires(cookie);
            if (mode == 0 || (mode == 1 && !expiry) ||
                (mode == 2 && expiry && g_date_time_to_unix(expiry) <= g_get_real_time()/G_USEC_PER_SEC)) {
                CookieCall call = { .op = COOKIE_DELETE, .cookie = cookie };
                if (!cookie_call(env, &call)) break;
                removed |= call.success;
            }
        }
        g_list_free_full(all.cookies, (GDestroyNotify)soup_cookie_free);
        return removed;
    }
	g_mutex_lock(&cookie_lock);
	GSList *cookies = ensure_jar(env) ? soup_cookie_jar_all_cookies(jar) : NULL;
	gboolean removed = FALSE;
	for (GSList *it = cookies; it; it = it->next) {
		SoupCookie *cookie = it->data;
		GDateTime *expiry = soup_cookie_get_expires(cookie);
		if (mode == 0 || (mode == 1 && !expiry) ||
		    (mode == 2 && expiry && g_date_time_to_unix(expiry) <= g_get_real_time() / G_USEC_PER_SEC)) {
			soup_cookie_jar_delete_cookie(jar, cookie);
			removed = TRUE;
		}
	}
	soup_cookies_free(cookies);
	g_mutex_unlock(&cookie_lock);
	return removed;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_CookieManager_hasCookies(JNIEnv *env, jobject self)
{
    if (getenv("ATL_UGLY_ENABLE_WEBVIEW")) {
        CookieCall call = { .op = COOKIE_ALL };
        gboolean result = cookie_call(env, &call) && call.cookies != NULL;
        g_list_free_full(call.cookies, (GDestroyNotify)soup_cookie_free);
        return result;
    }
	Java_android_webkit_CookieManager_nativeRemove(env, NULL, 2);
	if ((*env)->ExceptionCheck(env)) return FALSE;
	g_mutex_lock(&cookie_lock);
	GSList *cookies = soup_cookie_jar_all_cookies(jar);
	gboolean result = cookies != NULL;
	soup_cookies_free(cookies);
	g_mutex_unlock(&cookie_lock);
	return result;
}

JNIEXPORT void JNICALL Java_android_webkit_CookieManager_nativeFlush(JNIEnv *env, jclass cls)
{
	if (getenv("ATL_UGLY_ENABLE_WEBVIEW")) {
        // WebKit persists non-session cookies automatically but exposes no
        // synchronous disk-flush API. Do not claim Android's durability guarantee.
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/UnsupportedOperationException"), "WebKit does not expose synchronous cookie flush");
        return;
    }
	// SoupCookieJarDB commits synchronously in its changed signal handler.
	g_mutex_lock(&cookie_lock);
	ensure_jar(env);
	g_mutex_unlock(&cookie_lock);
}
