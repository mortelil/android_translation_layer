#include <gtk/gtk.h>
#include <libsecret/secret.h>
#include <gio/gio.h>

#include "../generated_headers/android_security_keystore_AndroidKeyStore.h"

extern GtkWindow *window;

/*
 * libsecret's sync API has no timeout parameter. When org.freedesktop.secrets
 * is not activatable on the session bus (e.g. a private bus created by
 * dbus-run-session) the synchronous D-Bus call blocks until the bus's own
 * activation timeout (~25 s) before failing. We bound the wait with a
 * GCancellable that is cancelled from a timer thread and, unlike the previous
 * code, we no longer drop the gboolean result and the GError: a failed store is
 * reported to Java as a KeyStoreException instead of silently looking like
 * "stored" (that would make AndroidX/Tink silently regenerate a master key
 * whose data can no longer be read). A failed READ against an unavailable
 * backend is reported as "alias absent" - see atl_secret_backend_unavailable().
 */
#define ATL_SECRET_TIMEOUT_MS 10000

typedef struct {
	GCancellable *cancellable;
	guint timeout_ms;
	GMutex mutex;
	GCond cond;
	gboolean done;
} AtlSecretTimeout;

static gpointer atl_secret_timeout_thread(gpointer data)
{
	AtlSecretTimeout *t = data;
	g_mutex_lock(&t->mutex);
	if (!t->done)
		g_cond_wait_until(&t->cond, &t->mutex,
		    g_get_monotonic_time() + (gint64)t->timeout_ms * 1000);
	gboolean cancel = !t->done;
	g_mutex_unlock(&t->mutex);
	if (cancel)
		g_cancellable_cancel(t->cancellable);
	return NULL;
}

static AtlSecretTimeout *atl_secret_timeout_start(GThread **thread_out)
{
	AtlSecretTimeout *t = g_new0(AtlSecretTimeout, 1);
	t->cancellable = g_cancellable_new();
	t->timeout_ms = ATL_SECRET_TIMEOUT_MS;
	g_mutex_init(&t->mutex);
	g_cond_init(&t->cond);
	*thread_out = g_thread_new("atl-secret-timeout", atl_secret_timeout_thread, t);
	return t;
}

static void atl_secret_timeout_stop(AtlSecretTimeout *t, GThread *thread)
{
	g_mutex_lock(&t->mutex);
	t->done = TRUE;
	g_cond_signal(&t->cond);
	g_mutex_unlock(&t->mutex);
	g_thread_join(thread);
	g_cond_clear(&t->cond);
	g_mutex_clear(&t->mutex);
	g_object_unref(t->cancellable);
	g_free(t);
}

/*
 * "There is no Secret Service on this bus" (headless session, private
 * dbus-run-session bus, container without gnome-keyring/kwallet) reaches us as
 * an I/O or D-Bus error: our own GCancellable fires (G_IO_ERROR_CANCELLED,
 * bounded by ATL_SECRET_TIMEOUT_MS) or the D-Bus name lookup/activation fails.
 *
 * That is NOT an item-level error. Android's KeyStore.containsAlias() answers
 * "is this alias present?" and an app that merely probes for an optional entry
 * (e.g. Home Assistant's optional TLS client certificate) must keep working
 * when the backend is simply absent: it has to see "not present", not an
 * UnrecoverableKeyException before its UI is drawn. So READ paths treat these
 * errors as "alias absent" (false / null / empty) and log one WARN line.
 *
 * Genuine per-item errors (locked collection, corrupt/unauthorised item, ...)
 * are not in this set and still throw. WRITE paths stay loud in all cases.
 */
static gboolean atl_secret_backend_unavailable(const GError *error)
{
	if (error == NULL)
		return FALSE;
	if (g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED) ||
	    g_error_matches(error, G_IO_ERROR, G_IO_ERROR_TIMED_OUT))
		return TRUE;
	if (g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SERVICE_UNKNOWN) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_NAME_HAS_NO_OWNER) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_NO_SERVER) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_NO_REPLY) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_TIMED_OUT) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_TIMEOUT) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_DISCONNECTED) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SPAWN_FAILED) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SPAWN_SERVICE_NOT_FOUND) ||
	    g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SPAWN_SERVICE_INVALID))
		return TRUE;
	return FALSE;
}

static void throw_key_store_exception(JNIEnv *env, const char *class_name, const char *alias, GError *error)
{
	char message[512];
	g_snprintf(message, sizeof(message),
	    "AndroidKeyStore: %s failed for alias \"%s\": %s",
	    strstr(class_name, "Unrecoverable") != NULL ? "lookup" : "store",
	    alias != NULL ? alias : "(null)",
	    error != NULL ? error->message : "unknown error");
	(*env)->ThrowNew(env, (*env)->FindClass(env, class_name), message);
}

static const char *resolve_app_id(JNIEnv *env, jstring package_name_jstr, char **package_name_out)
{
	const char *app_id = g_application_get_application_id(G_APPLICATION(gtk_window_get_application(window)));
	if ((app_id == NULL || !strcmp(app_id, "com.example.demo_application")) && package_name_jstr) {
		// fall back to package name
		app_id = *package_name_out = (*env)->GetStringUTFChars(env, package_name_jstr, NULL);
	}
	return app_id;
}

JNIEXPORT void JNICALL Java_android_security_keystore_AndroidKeyStore_nativeSetKey(JNIEnv *env, jclass clazz, jstring package_name_jstr, jstring alias_jstr, jbyteArray key_jobj)
{
	char *package_name = NULL;
	const char *app_id = resolve_app_id(env, package_name_jstr, &package_name);
	const char *alias = (*env)->GetStringUTFChars(env, alias_jstr, NULL);
	jbyte *key = (*env)->GetByteArrayElements(env, key_jobj, NULL);
	jsize key_len = (*env)->GetArrayLength(env, key_jobj);

	SecretSchema schema = {
		app_id,
		SECRET_SCHEMA_NONE,
		{{"alias", SECRET_SCHEMA_ATTRIBUTE_STRING},
	          {NULL, 0}}
	};
	GError *error = NULL;
	GThread *timer_thread = NULL;
	AtlSecretTimeout *timer = atl_secret_timeout_start(&timer_thread);
	SecretValue *value = secret_value_new((gchar *)key, key_len, "encoded");
	gboolean stored = secret_password_store_binary_sync(&schema, NULL, alias, value,
	    timer->cancellable, &error, "alias", alias, NULL);
	atl_secret_timeout_stop(timer, timer_thread);
	secret_value_unref(value);

	if (!stored) {
		(*env)->ReleaseByteArrayElements(env, key_jobj, key, JNI_ABORT);
		if (package_name)
			(*env)->ReleaseStringUTFChars(env, package_name_jstr, package_name);
		throw_key_store_exception(env, "java/security/KeyStoreException", alias, error);
		g_clear_error(&error);
		(*env)->ReleaseStringUTFChars(env, alias_jstr, alias);
		return;
	}
	g_clear_error(&error);

	if (package_name)
		(*env)->ReleaseStringUTFChars(env, package_name_jstr, package_name);
	(*env)->ReleaseStringUTFChars(env, alias_jstr, alias);
	(*env)->ReleaseByteArrayElements(env, key_jobj, key, 0);
}

JNIEXPORT jbyteArray JNICALL Java_android_security_keystore_AndroidKeyStore_nativeGetKey(JNIEnv *env, jclass clazz, jstring package_name_jstr, jstring alias_jstr)
{
	char *package_name = NULL;
	const char *app_id = resolve_app_id(env, package_name_jstr, &package_name);
	const char *alias = (*env)->GetStringUTFChars(env, alias_jstr, NULL);

	SecretSchema schema = {
		app_id,
		SECRET_SCHEMA_NONE,
		{{"alias", SECRET_SCHEMA_ATTRIBUTE_STRING},
	          {NULL, 0}}
	};
	GError *error = NULL;
	GThread *timer_thread = NULL;
	AtlSecretTimeout *timer = atl_secret_timeout_start(&timer_thread);
	SecretValue *value = secret_password_lookup_binary_sync(&schema, timer->cancellable, &error,
	    "alias", alias, NULL);
	atl_secret_timeout_stop(timer, timer_thread);

	if (error != NULL) {
		if (atl_secret_backend_unavailable(error)) {
			/* Reads: no Secret Service means "alias not present". */
			g_warning("AndroidKeyStore: read for alias \"%s\" treated as absent: %s",
			    alias, error->message != NULL ? error->message : "unknown error");
			g_clear_error(&error);
		} else {
			if (package_name)
				(*env)->ReleaseStringUTFChars(env, package_name_jstr, package_name);
			throw_key_store_exception(env, "java/security/UnrecoverableKeyException", alias, error);
			g_clear_error(&error);
			(*env)->ReleaseStringUTFChars(env, alias_jstr, alias);
			return NULL;
		}
	}

	if (package_name)
		(*env)->ReleaseStringUTFChars(env, package_name_jstr, package_name);
	(*env)->ReleaseStringUTFChars(env, alias_jstr, alias);
	if (value) {
		gsize length;
		const gchar *encoded = secret_value_get(value, &length);
		jbyteArray key_jobj = (*env)->NewByteArray(env, length);
		(*env)->SetByteArrayRegion(env, key_jobj, 0, length, (jbyte *)encoded);
		secret_value_unref(value);
		return key_jobj;
	} else {
		return NULL;
	}
}
