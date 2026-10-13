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

/* AOSP ViewRootImpl.performTraversals() calls
 * mAttachInfo.mTreeObserver.dispatchOnPreDraw() before drawing the frame and
 * skips the draw ("Try again", scheduleTraversals()) when a listener returns
 * false (android-17.0.0_r1, 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d).
 * The equivalent boundary in ATL is the root snapshot: the whole window
 * content is presented through one ATLScaleRoot, so the observer is
 * dispatched from atl_scale_root_snapshot() (android_view_Window.c) before
 * any of the hierarchy is snapshotted. Like the global-layout registration,
 * the observer is owned by the GtkWindow, so window teardown releases the JNI
 * ref; all operations run on the UI thread, as on Android. */
static const char *pre_draw_key = "atl-predraw-observer";

static void free_pre_draw_observer(gpointer data)
{
	JNIEnv *env = get_jni_env();
	(*env)->DeleteGlobalRef(env, (jobject)data);
}

static gboolean pre_draw_queue_draw_idle(gpointer data)
{
	gtk_widget_queue_draw(GTK_WIDGET(data));
	g_object_unref(data);
	return G_SOURCE_REMOVE;
}

/* Queue a redraw for a future event-loop pass. A pre-draw listener runs inside
 * the GtkSnapshot pass, where queueing a draw re-entrantly is not allowed;
 * this mirrors wrapper_widget_queue_draw(). */
static void queue_pre_draw_frame(GtkWidget *window)
{
	g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, pre_draw_queue_draw_idle, g_object_ref(window), NULL);
}

/* Dispatch the window's OnPreDraw listeners; returns FALSE when a listener
 * cancelled the draw, in which case the caller must not present the content of
 * this frame. */
gboolean atl_view_tree_observer_dispatch_pre_draw(GtkWidget *window)
{
	jobject observer = g_object_get_data(G_OBJECT(window), pre_draw_key);
	if (!observer) return TRUE;

	JNIEnv *env = get_jni_env();
	/* A listener may remove the last registration, freeing the window data and
	 * the global ref while we are calling into Java. */
	jobject local = (*env)->NewLocalRef(env, observer);
	jboolean cancel = (*env)->CallBooleanMethod(env, local, handle_cache.view_tree_observer.dispatchOnPreDraw);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	(*env)->DeleteLocalRef(env, local);

	if (cancel) {
		/* AOSP: do not draw this frame, schedule another traversal. */
		queue_pre_draw_frame(window);
		return FALSE;
	}
	return TRUE;
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
		return;
	}

	if (!have_listeners) {
		/* Destroy-notify releases the observer's global ref. */
		g_object_set_data(G_OBJECT(window), pre_draw_key, NULL);
		return;
	}
	if (g_object_get_data(G_OBJECT(window), pre_draw_key)) return;
	g_object_set_data_full(G_OBJECT(window), pre_draw_key, (*env)->NewGlobalRef(env, this), free_pre_draw_observer);
	/* The window may be idle; queue one frame so the new listener runs. */
	queue_pre_draw_frame(window);
}
