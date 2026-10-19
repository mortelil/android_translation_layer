#include "../jni_exceptions.h"
#include <gtk/gtk.h>
#include "../android_view_Window.h"
#include <jni.h>

#include "../defines.h"
#include "../util.h"
#include "../widgets/WrapperWidget.h" /* atl_widget_present_tree(): attach before present */
#include "../generated_headers/android_app_Dialog.h"

/* main app window */
extern GtkWindow *window;

static gboolean on_close_request(GtkWidget *dialog, jobject jobj)
{
	printf("on_close_request\n");
	JNIEnv *env = get_jni_env();
	jmethodID dismiss = _METHOD(_CLASS(jobj), "cancelFromUser", "()V");
	(*env)->CallVoidMethod(env, jobj, dismiss);
	return TRUE;
}

/* state for Dialog.setCanceledOnTouchOutside() */
struct touch_outside {
	GtkWindow *dialog;
	GtkEventController *controller;
	jobject jobj;
};

static gboolean on_touch_outside(GtkEventControllerLegacy *controller, GdkEvent *event, struct touch_outside *outside)
{
	GdkEventType event_type = gdk_event_get_event_type(event);
	if ((event_type == GDK_BUTTON_PRESS || event_type == GDK_TOUCH_BEGIN) && gtk_widget_get_visible(GTK_WIDGET(outside->dialog))) {
		JNIEnv *env = get_jni_env();
		jmethodID dismiss = _METHOD(_CLASS(outside->jobj), "cancelFromUser", "()V");
		(*env)->CallVoidMethod(env, outside->jobj, dismiss);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
		return TRUE;
	} else {
		return FALSE;
	}
}

static void remove_touch_outside(GtkWindow *dialog)
{
	struct touch_outside *outside = g_object_get_data(G_OBJECT(dialog), "touch-outside");
	if (!outside)
		return;

	JNIEnv *env = get_jni_env();
	gtk_widget_remove_controller(GTK_WIDGET(window), outside->controller);
	g_object_unref(outside->controller);
	_UNREF(outside->jobj);
	g_free(outside);
	g_object_set_data(G_OBJECT(dialog), "touch-outside", NULL);
}

JNIEXPORT jlong JNICALL Java_android_app_Dialog_nativeInit(JNIEnv *env, jobject this, jlong decor_view_ptr)
{
	GtkWidget *decor_view = GTK_WIDGET(_PTR(decor_view_ptr));
	GtkWidget *dialog = gtk_window_new();
	gtk_window_set_transient_for(GTK_WINDOW(dialog), window);
	atl_window_set_content(env, GTK_WINDOW(dialog), gtk_widget_get_parent(decor_view));
	g_signal_connect(GTK_WINDOW(dialog), "close-request", G_CALLBACK(on_close_request), _REF(this));
	return _INTPTR(g_object_ref(dialog));
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetTitle(JNIEnv *env, jobject this, jlong ptr, jstring title)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	const char *nativeTitle = (*env)->GetStringUTFChars(env, title, NULL);
	gtk_window_set_title(dialog, nativeTitle);
	(*env)->ReleaseStringUTFChars(env, title, nativeTitle);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetContentView(JNIEnv *env, jobject this, jlong ptr, jlong widget_ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));

	atl_window_set_content(env, dialog, gtk_widget_get_parent(widget));
}

struct pending_present {
	GtkWindow *dialog;
};

static gboolean present_dialog(gpointer data)
{
	struct pending_present *pending = data;
	GtkWindow *dialog = pending->dialog;

	if (dialog) {
		g_object_ref(dialog);
		g_object_remove_weak_pointer(G_OBJECT(dialog), (gpointer *)&pending->dialog);
		g_object_set_data(G_OBJECT(dialog), "atl-present-scheduled", NULL);
		/* a hide/close before the deferred present wins over the pending show */
		if (g_object_get_data(G_OBJECT(dialog), "atl-wants-present")) {

			/* Attach before presenting: GTK can allocate (and therefore
			 * measure) a window's content before it notifies about the
			 * visibility change, which would measure a ComposeView that
			 * is still unattached (r12-probe/atl-ui-stderr.log:390 -> :403).
			 * AOSP's traversal attaches first and measures after
			 * (android/view/ViewRootImpl.java:4017, then :4099). */
			atl_widget_present_tree(GTK_WIDGET(dialog));
			atl_jni_handle_uncaught_exception(get_jni_env(), "Dialog attach");
			if (g_object_get_data(G_OBJECT(dialog), "atl-wants-present")) gtk_window_present(dialog);
			g_object_set_data(G_OBJECT(dialog), "atl-wants-present", NULL);
		}
		g_object_unref(dialog);
	}
	g_free(pending);
	return G_SOURCE_REMOVE;
}

/* AOSP's addView() only schedules the first traversal for the next frame
 * (ViewRootImpl.setView -> requestLayout -> scheduleTraversals,
 * core/java/android/view/ViewRootImpl.java:4010-4017), so the views see
 * onAttachedToWindow() - and are measured - after Dialog.show() returned.
 * androidx' DialogFragment sets ViewTreeLifecycleOwner on the decor view right
 * after mDialog.show() returns, and both the ComposeView lookup of the window
 * recomposer and the ViewTreeLifecycleOwner observer fail if that happens after
 * the view was attached or measured. gtk_window_present() allocates the window,
 * which measures the whole Java view tree immediately, so present on the next
 * main loop iteration instead. */
JNIEXPORT void JNICALL Java_android_app_Dialog_nativeShow(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	struct pending_present *pending;

	g_object_set_data(G_OBJECT(dialog), "atl-wants-present", GINT_TO_POINTER(1));
	if (g_object_get_data(G_OBJECT(dialog), "atl-present-scheduled"))
		return;

	pending = g_new0(struct pending_present, 1);
	pending->dialog = dialog;
	g_object_add_weak_pointer(G_OBJECT(dialog), (gpointer *)&pending->dialog);
	g_object_set_data(G_OBJECT(dialog), "atl-present-scheduled", GINT_TO_POINTER(1));
	/* Run before GTK measurement and the Android message source (priority 120). */
	g_idle_add_full(G_PRIORITY_HIGH_IDLE, present_dialog, pending, NULL);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeClose(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	remove_touch_outside(dialog);
	g_object_set_data(G_OBJECT(dialog), "atl-wants-present", NULL);
	atl_widget_dismiss_tree(GTK_WIDGET(dialog));
	gtk_widget_set_visible(GTK_WIDGET(dialog), FALSE);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeHide(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	g_object_set_data(G_OBJECT(dialog), "atl-wants-present", NULL);
	gtk_widget_set_visible(GTK_WIDGET(dialog), FALSE);
}

JNIEXPORT jboolean JNICALL Java_android_app_Dialog_nativeIsShowing(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	return gtk_widget_is_visible(GTK_WIDGET(dialog));
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetCanceledOnTouchOutside(JNIEnv *env, jobject this, jlong ptr, jboolean cancel)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	remove_touch_outside(dialog);
	if (!cancel)
		return;

	struct touch_outside *outside = g_new0(struct touch_outside, 1);
	outside->dialog = dialog;
	outside->jobj = _REF(this);

	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	/* capture phase on the toplevel runs before the view tree's own touch controllers */
	gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_CAPTURE);
	g_signal_connect(controller, "event", G_CALLBACK(on_touch_outside), outside);
	gtk_widget_add_controller(GTK_WIDGET(window), controller);
	outside->controller = controller;

	g_object_set_data(G_OBJECT(dialog), "touch-outside", outside);
}
