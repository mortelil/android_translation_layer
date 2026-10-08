#include <gio/gio.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_net_ConnectivityManager.h"

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_isActiveNetworkMetered(JNIEnv *env, jobject this)
{
	return g_network_monitor_get_network_metered(g_network_monitor_get_default());
}

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_nativeGetNetworkAvailable(JNIEnv *env, jobject this)
{
	return g_network_monitor_get_network_available(g_network_monitor_get_default());
}

static void on_network_changed(GNetworkMonitor *self, gboolean network_available, jobject callback)
{
	JNIEnv *env = get_jni_env();
	jmethodID method;
	if (network_available) {
		method = _METHOD(_CLASS(callback), "onAvailable", "(Landroid/net/Network;)V");
	} else {
		method = _METHOD(_CLASS(callback), "onLost", "(Landroid/net/Network;)V");
	}
	jclass network_class = (*env)->FindClass(env, "android/net/Network");
	jmethodID constructor = (*env)->GetMethodID(env, network_class, "<init>", "()V");
	jobject network = (*env)->NewObject(env, network_class, constructor);
	(*env)->CallVoidMethod(env, callback, method, network);
	(*env)->DeleteLocalRef(env, network);
	(*env)->DeleteLocalRef(env, network_class);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void free_callback(gpointer callback, GClosure *closure)
{
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, callback);
}

JNIEXPORT jlong JNICALL Java_android_net_ConnectivityManager_nativeRegisterNetworkCallback(JNIEnv *env, jobject this, jobject request, jobject callback)
{
	return g_signal_connect_data(g_network_monitor_get_default(), "network-changed", G_CALLBACK(on_network_changed), _REF(callback), free_callback, 0);
}

JNIEXPORT void JNICALL Java_android_net_ConnectivityManager_nativeUnregisterNetworkCallback(JNIEnv *env, jobject this, jlong registration)
{
	g_signal_handler_disconnect(g_network_monitor_get_default(), registration);
}
