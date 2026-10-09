// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <libsoup/soup.h>
#include <glib/gstdio.h>
#include <sys/stat.h>
#include <string.h>
#include "util.h"

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
	// SoupCookieJarDB commits synchronously in its changed signal handler.
	g_mutex_lock(&cookie_lock);
	ensure_jar(env);
	g_mutex_unlock(&cookie_lock);
}
