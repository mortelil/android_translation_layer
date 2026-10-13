#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_view_ViewTreeObserver.h"

/* OMP OsmAnd d6f82b27 identified registration before the window is mapped.
 * Keep one window-owned registration, including pending map/unmap hooks. This
 * also makes removal before mapping and window teardown release the JNI ref.
 * All operations, like Android ViewTreeObserver, run on the UI thread. */
static const char *global_layout_key = "atl-global-layout-registration";
typedef struct {
	GtkWidget *window; /* owns this registration */
	jobject observer;
	GdkFrameClock *clock;
	gulong layout, map, unmap;
} GlobalLayoutRegistration;

static void disconnect_global_layout_clock(GlobalLayoutRegistration *registration)
{
	if (!registration->clock) return;
	g_signal_handler_disconnect(registration->clock, registration->layout);
	g_clear_object(&registration->clock);
	registration->layout = 0;
}

static void free_global_layout_registration(gpointer data)
{
	GlobalLayoutRegistration *registration = data;
	disconnect_global_layout_clock(registration);
	if (g_signal_handler_is_connected(registration->window, registration->map))
		g_signal_handler_disconnect(registration->window, registration->map);
	if (g_signal_handler_is_connected(registration->window, registration->unmap))
		g_signal_handler_disconnect(registration->window, registration->unmap);
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, registration->observer);
	g_free(registration);
}

static void on_global_layout_callback(GdkFrameClock *clock, GtkWidget *window)
{
	GlobalLayoutRegistration *registration = g_object_get_data(G_OBJECT(window), global_layout_key);
	if (!registration) return;
	JNIEnv *env = get_jni_env();
	/* A callback may remove its last listener, freeing the registration. */
	jobject observer = (*env)->NewLocalRef(env, registration->observer);
	(*env)->CallVoidMethod(env, observer, handle_cache.view_tree_observer.dispatchOnGlobalLayout);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	(*env)->DeleteLocalRef(env, observer);
}

static void connect_global_layout_clock(GtkWidget *window, GlobalLayoutRegistration *registration)
{
	if (registration->clock) return;
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);
	if (!clock) return;
	registration->clock = g_object_ref(clock);
	/* GTK's own layout handler was connected first: Java sees allocated sizes. */
	registration->layout = g_signal_connect(clock, "layout", G_CALLBACK(on_global_layout_callback), window);
	gtk_widget_queue_allocate(window);
	gdk_frame_clock_request_phase(clock, GDK_FRAME_CLOCK_PHASE_LAYOUT);
}

static void on_global_layout_unmap(GtkWidget *window, GlobalLayoutRegistration *registration)
{
	disconnect_global_layout_clock(registration);
}

static void on_pre_draw_callback(GdkFrameClock *clock, jobject view_tree_observer)
{
	JNIEnv *env = get_jni_env();
	(*env)->CallBooleanMethod(env, view_tree_observer, handle_cache.view_tree_observer.dispatchOnPreDraw);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
}

extern GtkWidget *window;

void _gdb_force_java_stack_trace(void);

JNIEXPORT void JNICALL Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners(JNIEnv *env, jobject this, jboolean have_listeners)
{
	GtkWidget *window = _PTR(_GET_LONG_FIELD(this, "window"));

	if (!window) {
		return;
	}

	GlobalLayoutRegistration *registration = g_object_get_data(G_OBJECT(window), global_layout_key);
	if (!have_listeners) {
		g_object_set_data(G_OBJECT(window), global_layout_key, NULL);
		return;
	}
	if (registration) return;
	registration = g_new0(GlobalLayoutRegistration, 1);
	registration->window = window;
	registration->observer = (*env)->NewGlobalRef(env, this);
	registration->map = g_signal_connect(window, "map", G_CALLBACK(connect_global_layout_clock), registration);
	registration->unmap = g_signal_connect(window, "unmap", G_CALLBACK(on_global_layout_unmap), registration);
	g_object_set_data_full(G_OBJECT(window), global_layout_key, registration, free_global_layout_registration);
	if (gtk_widget_get_mapped(window)) connect_global_layout_clock(window, registration);
}

JNIEXPORT void JNICALL Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners(JNIEnv *env, jobject this, jboolean have_listeners)
{
	GtkWidget *window = _PTR(_GET_LONG_FIELD(this, "window"));

	if (!window) {
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners: no window\n");
		return;
	}

	gulong signal_handle = _GET_LONG_FIELD(this, "onPreDraw_signal_handle");
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);

	if (have_listeners && !signal_handle) {
		/* Unlike AOSP, our hook gets invoked after all widgets have been drawn. */
		/* Switching to ::layout would fix the timing, but we would miss frames where no layout was requested. */
		/* TODO: Hook into the DecorView snapshot function to get in before widget drawing. */
		signal_handle = g_signal_connect(G_OBJECT(clock), "paint", G_CALLBACK(on_pre_draw_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onPreDraw_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onPreDraw_signal_handle", 0);
	} else {
		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1predraw_1listeners: invalid state: have_listeners: %d, signal_handle: 0x%016w64x\n", have_listeners, signal_handle);
		exit(1);
	}
}
