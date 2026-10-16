/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * DNS-SD / mDNS backend for android.net.nsd.NsdManager.
 *
 * Uses the host's DNS-SD stack through libavahi-client. The library is a hard
 * build dependency; when the daemon is unreachable at start time the start
 * call fails (avahi_client_new with flags=0 returns NULL / AVAHI_ERR_NO_DAEMON)
 * so the Java layer can report onStartDiscoveryFailed instead of silently
 * starting and going quiet.
 *
 * Threading: one AvahiThreadedPoll owns the D-Bus connection and dispatches all
 * avahi callbacks on its own thread. Every avahi API call made from a Java
 * thread is wrapped in avahi_threaded_poll_lock()/unlock(). Events are returned
 * to Java as static method calls carrying the operation handle; the Java layer
 * drops any event whose handle it no longer tracks (stopped discovery /
 * completed resolve), which isolates operation generations.
 *
 * The service type is always the caller-supplied DNS-SD type; nothing about a
 * specific application is hard-coded here.
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <avahi-client/client.h>
#include <avahi-client/lookup.h>
#include <avahi-common/thread-watch.h>
#include <avahi-common/error.h>
#include <avahi-common/strlst.h>
#include <avahi-common/address.h>

#include "../defines.h"
#include "../util.h"

/* ---- Java callback bridge ------------------------------------------------ */

static jclass g_nsd_class = NULL;
static jmethodID g_m_service_found;
static jmethodID g_m_service_lost;
static jmethodID g_m_resolve_result;
static jmethodID g_m_backend_failed;
static int g_bridge_ready = 0;

static JNIEnv *nsd_attach(void)
{
	JNIEnv *env = NULL;
	if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
		if ((*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL) != JNI_OK)
			return NULL;
	}
	return env;
}

static int nsd_bridge_init(JNIEnv *env, jclass clazz)
{
	if (g_bridge_ready)
		return 1;
	if (!g_nsd_class)
		g_nsd_class = (*env)->NewGlobalRef(env, clazz);
	g_m_service_found = (*env)->GetStaticMethodID(env, clazz, "onNativeServiceFound",
			"(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");
	g_m_service_lost = (*env)->GetStaticMethodID(env, clazz, "onNativeServiceLost",
			"(JLjava/lang/String;Ljava/lang/String;)V");
	g_m_resolve_result = (*env)->GetStaticMethodID(env, clazz, "onNativeResolveResult",
			"(J[B[BLjava/lang/String;I)V");
	g_m_backend_failed = (*env)->GetStaticMethodID(env, clazz, "onNativeBackendFailed",
			"()V");
	if (!g_m_service_found || !g_m_service_lost || !g_m_resolve_result || !g_m_backend_failed) {
		android_log_printf(ANDROID_LOG_ERROR, "NsdManager",
				"failed to resolve NsdManager callback methods");
		return 0;
	}
	g_bridge_ready = 1;
	return 1;
}

static void java_service_found(long handle, const char *name, const char *type,
		const char *domain, int iface, int proto)
{
	JNIEnv *env = nsd_attach();
	if (!env)
		return;
	jstring jname = _JSTRING(name ? name : "");
	jstring jtype = _JSTRING(type ? type : "");
	jstring jdomain = _JSTRING(domain ? domain : "");
	(*env)->CallStaticVoidMethod(env, g_nsd_class, g_m_service_found,
			(jlong)handle, jname, jtype, jdomain, (jint)iface, (jint)proto);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, jname);
	(*env)->DeleteLocalRef(env, jtype);
	(*env)->DeleteLocalRef(env, jdomain);
}

static void java_service_lost(long handle, const char *name, const char *type)
{
	JNIEnv *env = nsd_attach();
	if (!env)
		return;
	jstring jname = _JSTRING(name ? name : "");
	jstring jtype = _JSTRING(type ? type : "");
	(*env)->CallStaticVoidMethod(env, g_nsd_class, g_m_service_lost,
			(jlong)handle, jname, jtype);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, jname);
	(*env)->DeleteLocalRef(env, jtype);
}

static void java_resolve_result(long handle, jbyteArray address, int port,
		jbyteArray txt, const char *hostname, int error)
{
	JNIEnv *env = nsd_attach();
	if (!env)
		return;
	jstring jhost = hostname ? _JSTRING(hostname) : NULL;
	(*env)->CallStaticVoidMethod(env, g_nsd_class, g_m_resolve_result,
			(jlong)handle, address, (jint)port, txt, jhost, (jint)error);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	if (jhost)
		(*env)->DeleteLocalRef(env, jhost);
	if (address)
		(*env)->DeleteLocalRef(env, address);
	if (txt)
		(*env)->DeleteLocalRef(env, txt);
}

static void java_backend_failed(void)
{
	JNIEnv *env = nsd_attach();
	if (!env)
		return;
	(*env)->CallStaticVoidMethod(env, g_nsd_class, g_m_backend_failed);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* ---- backend state ------------------------------------------------------- */

static AvahiThreadedPoll *g_nsd_poll = NULL;
static AvahiClient *g_client = NULL;
static int g_client_running = 0;
static int g_failure_notified = 0;
static pthread_mutex_t g_init_lock = PTHREAD_MUTEX_INITIALIZER;

struct discovery_op {
	jlong handle;
	AvahiServiceBrowser *browser;
	char *service_type;
	struct discovery_op *next;
};

struct resolve_op {
	jlong handle;
	AvahiServiceResolver *resolver;
	struct resolve_op *next;
};

static struct discovery_op *g_discoveries = NULL;
static struct resolve_op *g_resolves = NULL;

static void clear_ops(void)
{
	struct discovery_op *d = g_discoveries, *dn;
	while (d) { dn = d->next; free(d->service_type); free(d); d = dn; }
	g_discoveries = NULL;
	struct resolve_op *r = g_resolves, *rn;
	while (r) { rn = r->next; free(r); r = rn; }
	g_resolves = NULL;
}

static struct discovery_op *unlink_discovery_locked(jlong handle)
{
	struct discovery_op **pp = &g_discoveries;
	while (*pp) {
		if ((*pp)->handle == handle) {
			struct discovery_op *found = *pp;
			*pp = found->next;
			found->next = NULL;
			return found;
		}
		pp = &(*pp)->next;
	}
	return NULL;
}

static struct resolve_op *unlink_resolve_locked(struct resolve_op *op)
{
	struct resolve_op **pp = &g_resolves;
	while (*pp) {
		if (*pp == op) {
			*pp = op->next;
			op->next = NULL;
			return op;
		}
		pp = &(*pp)->next;
	}
	return NULL;
}

/* ---- avahi callbacks ----------------------------------------------------- */

static void client_cb(AvahiClient *c, AvahiClientState state, void *userdata)
{
	(void)c; (void)userdata;
	switch (state) {
	case AVAHI_CLIENT_S_RUNNING:
		g_client_running = 1;
		break;
	case AVAHI_CLIENT_FAILURE:
		/* The client only ever reaches these states after a successful
		 * connect (flags=0), so this is a lost daemon, not startup. */
		g_client_running = 0;
		if (!g_failure_notified) {
			g_failure_notified = 1;
			java_backend_failed();
		}
		break;
	default:
		break;
	}
}

static jbyteArray build_address(JNIEnv *env, const AvahiAddress *a)
{
	if (!a)
		return NULL;
	if (a->proto == AVAHI_PROTO_INET) {
		jbyteArray arr = (*env)->NewByteArray(env, 4);
		(*env)->SetByteArrayRegion(env, arr, 0, 4,
				(const jbyte *)&a->data.ipv4.address);
		return arr;
	}
	if (a->proto == AVAHI_PROTO_INET6) {
		jbyteArray arr = (*env)->NewByteArray(env, 16);
		(*env)->SetByteArrayRegion(env, arr, 0, 16,
				(const jbyte *)a->data.ipv6.address);
		return arr;
	}
	return NULL;
}

/* Build the TXT record as a NUL-separated list of "key=value" entries so that
 * arbitrary (possibly binary) values survive; the Java layer splits it. */
static jbyteArray build_txt(JNIEnv *env, AvahiStringList *txt)
{
	AvahiStringList *l;
	size_t total = 0, off = 0;
	for (l = txt; l; l = avahi_string_list_get_next(l))
		total += avahi_string_list_get_size(l) + 1;
	if (total == 0)
		return NULL;
	jbyteArray arr = (*env)->NewByteArray(env, (jsize)total);
	jbyte *buf = (*env)->GetByteArrayElements(env, arr, NULL);
	for (l = txt; l; l = avahi_string_list_get_next(l)) {
		size_t sz = avahi_string_list_get_size(l);
		memcpy(buf + off, avahi_string_list_get_text(l), sz);
		off += sz;
		buf[off++] = 0;
	}
	(*env)->ReleaseByteArrayElements(env, arr, buf, 0);
	return arr;
}

static void resolve_cb(AvahiServiceResolver *r, AvahiIfIndex interface,
		AvahiProtocol protocol, AvahiResolverEvent event, const char *name,
		const char *type, const char *domain, const char *host_name,
		const AvahiAddress *a, uint16_t port, AvahiStringList *txt,
		AvahiLookupResultFlags flags, void *userdata)
{
	(void)interface; (void)protocol; (void)name; (void)type; (void)domain; (void)flags;
	struct resolve_op *op = userdata;
	int error = (event == AVAHI_RESOLVER_FOUND) ? 0 : 1;

	JNIEnv *env = nsd_attach();
	jbyteArray addr = NULL, txtarr = NULL;
	if (env && op) {
		if (!error) {
			addr = build_address(env, a);
			txtarr = build_txt(env, txt);
		}
		java_resolve_result(op->handle, addr, port, txtarr,
				host_name, error);
	}

	unlink_resolve_locked(op);
	avahi_service_resolver_free(r);
	free(op);
}

static void browse_cb(AvahiServiceBrowser *b, AvahiIfIndex interface,
		AvahiProtocol protocol, AvahiBrowserEvent event, const char *name,
		const char *type, const char *domain, AvahiLookupResultFlags flags,
		void *userdata)
{
	(void)flags;
	struct discovery_op *op = userdata;
	switch (event) {
	case AVAHI_BROWSER_NEW:
		java_service_found(op->handle, name, type, domain,
				(int)interface, (int)protocol);
		break;
	case AVAHI_BROWSER_REMOVE:
		java_service_lost(op->handle, name, type);
		break;
	case AVAHI_BROWSER_FAILURE:
		g_client_running = 0;
		unlink_discovery_locked(op->handle);
		avahi_service_browser_free(b);
		free(op->service_type);
		free(op);
		if (!g_failure_notified) {
			g_failure_notified = 1;
			java_backend_failed();
		}
		break;
	case AVAHI_BROWSER_ALL_FOR_NOW:
	case AVAHI_BROWSER_CACHE_EXHAUSTED:
	default:
		break;
	}
}

/* ---- lifecycle ----------------------------------------------------------- */

/* Create (or recover) the poll thread + client. Callable from any Java thread;
 * never from the poll thread. Returns 1 when the client is RUNNING. */
static int nsd_ensure_backend(void)
{
	pthread_mutex_lock(&g_init_lock);

	if (g_nsd_poll && g_client_running) {
		pthread_mutex_unlock(&g_init_lock);
		return 1;
	}

	if (g_nsd_poll) {
		/* Tear down a dead backend from this (non-poll) thread. */
		avahi_threaded_poll_lock(g_nsd_poll);
		if (g_client) {
			avahi_client_free(g_client);
			g_client = NULL;
		}
		avahi_threaded_poll_unlock(g_nsd_poll);
		clear_ops();
		avahi_threaded_poll_stop(g_nsd_poll);
		avahi_threaded_poll_free(g_nsd_poll);
		g_nsd_poll = NULL;
	}

	int error = 0;
	AvahiThreadedPoll *poll = avahi_threaded_poll_new();
	if (!poll) {
		pthread_mutex_unlock(&g_init_lock);
		return 0;
	}
	/* flags=0: fail immediately when the daemon is unreachable rather than
	 * reporting a healthy client that will never deliver anything. */
	AvahiClient *client = avahi_client_new(avahi_threaded_poll_get(poll), 0,
			client_cb, NULL, &error);
	if (!client) {
		android_log_printf(ANDROID_LOG_WARN, "NsdManager",
				"avahi_client_new failed: %s", avahi_strerror(error));
		avahi_threaded_poll_free(poll);
		pthread_mutex_unlock(&g_init_lock);
		return 0;
	}

	g_nsd_poll = poll;
	g_client = client;
	g_client_running = (avahi_client_get_state(client) == AVAHI_CLIENT_S_RUNNING);
	g_failure_notified = 0;
	clear_ops();

	if (!g_client_running) {
		android_log_printf(ANDROID_LOG_WARN, "NsdManager",
				"avahi daemon not running");
		avahi_client_free(g_client);
		g_client = NULL;
		avahi_threaded_poll_free(g_nsd_poll);
		g_nsd_poll = NULL;
		pthread_mutex_unlock(&g_init_lock);
		return 0;
	}

	avahi_threaded_poll_start(poll);
	pthread_mutex_unlock(&g_init_lock);
	return 1;
}

/* ---- JNI entry points ---------------------------------------------------- */

JNIEXPORT jboolean JNICALL Java_android_net_nsd_NsdManager_nativeIsAvailable(
		JNIEnv *env, jclass clazz)
{
	if (!nsd_bridge_init(env, clazz))
		return JNI_FALSE;
	return nsd_ensure_backend() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_android_net_nsd_NsdManager_nativeStartDiscovery(
		JNIEnv *env, jclass clazz, jlong handle, jstring jtype)
{
	if (!nsd_bridge_init(env, clazz))
		return JNI_FALSE;
	if (!nsd_ensure_backend())
		return JNI_FALSE;

	const char *type = (*env)->GetStringUTFChars(env, jtype, NULL);
	struct discovery_op *op = calloc(1, sizeof(*op));
	op->handle = handle;
	op->service_type = strdup(type);
	(*env)->ReleaseStringUTFChars(env, jtype, type);

	avahi_threaded_poll_lock(g_nsd_poll);
	if (g_client_running)
		op->browser = avahi_service_browser_new(g_client, AVAHI_IF_UNSPEC,
				AVAHI_PROTO_UNSPEC, op->service_type, NULL, 0,
				browse_cb, op);
	if (op->browser) {
		op->next = g_discoveries;
		g_discoveries = op;
	}
	avahi_threaded_poll_unlock(g_nsd_poll);

	if (!op->browser) {
		free(op->service_type);
		free(op);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_android_net_nsd_NsdManager_nativeStopDiscovery(
		JNIEnv *env, jclass clazz, jlong handle)
{
	(void)env; (void)clazz;
	if (!g_nsd_poll)
		return;
	avahi_threaded_poll_lock(g_nsd_poll);
	struct discovery_op *op = unlink_discovery_locked(handle);
	if (op) {
		if (op->browser)
			avahi_service_browser_free(op->browser);
		free(op->service_type);
		free(op);
	}
	avahi_threaded_poll_unlock(g_nsd_poll);
}

JNIEXPORT jboolean JNICALL Java_android_net_nsd_NsdManager_nativeResolve(
		JNIEnv *env, jclass clazz, jlong handle, jstring jname, jstring jtype,
		jstring jdomain, jint iface, jint proto)
{
	if (!nsd_bridge_init(env, clazz))
		return JNI_FALSE;
	if (!nsd_ensure_backend())
		return JNI_FALSE;

	const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
	const char *type = (*env)->GetStringUTFChars(env, jtype, NULL);
	const char *domain = jdomain ? (*env)->GetStringUTFChars(env, jdomain, NULL) : NULL;
	if (domain && domain[0] == '\0')
		domain = NULL;

	struct resolve_op *op = calloc(1, sizeof(*op));
	op->handle = handle;

	avahi_threaded_poll_lock(g_nsd_poll);
	if (g_client_running)
		op->resolver = avahi_service_resolver_new(g_client,
				(AvahiIfIndex)iface, (AvahiProtocol)proto, name, type,
				domain, AVAHI_PROTO_UNSPEC, 0, resolve_cb, op);
	if (op->resolver) {
		op->next = g_resolves;
		g_resolves = op;
	}
	avahi_threaded_poll_unlock(g_nsd_poll);

	(*env)->ReleaseStringUTFChars(env, jname, name);
	(*env)->ReleaseStringUTFChars(env, jtype, type);
	if (jdomain && domain)
		(*env)->ReleaseStringUTFChars(env, jdomain, domain);

	if (!op->resolver) {
		free(op);
		return JNI_FALSE;
	}
	return JNI_TRUE;
}
