#include <gtk/gtk.h>
#include "../android_view_Window.h"
#include <libportal/portal.h>

#include <jni.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"
#include "../jni_exceptions.h"
#include "../generated_headers/android_app_Activity.h"
#include "../generated_headers/android_atl_ATLActivityController.h"
#include "../../main-executable/back_button.h"
#include "android_app_Activity.h"

extern GtkWindow *window;

/* Host-event codes; MUST stay in sync with android.atl.ATLActivityController
 * (HOST_EVENT_START / BACK / WINDOW_CLOSE_REQUEST / WINDOW_READY). The Java
 * controller owns the whole activity stack, so the C layer no longer keeps a
 * backlog: every lifecycle decision enters through hostEvent(). */
#define HOST_EVENT_START               1
#define HOST_EVENT_BACK                2
#define HOST_EVENT_WINDOW_CLOSE_REQUEST 3
#define HOST_EVENT_WINDOW_READY        4

static void controller_host_event(JNIEnv *env, jint code, jobject arg)
{
	(*env)->CallStaticVoidMethod(env, handle_cache.atl_activity_controller.class,
	                             _STATIC_METHOD(handle_cache.atl_activity_controller.class, "hostEvent", "(ILjava/lang/Object;)V"),
	                             code, arg);
}

static GtkWidget *get_decor_view(JNIEnv *env, jobject activity)
{
	jobject window_jobj = _GET_OBJ_FIELD(activity, "window", "Landroid/view/Window;");
	jobject decor_view_jobj = _GET_OBJ_FIELD(window_jobj, "decorView", "Landroid/view/ViewGroup;");
	GtkWidget *decor_view = GTK_WIDGET(_PTR(_GET_LONG_FIELD(decor_view_jobj, "widget")));
	return gtk_widget_get_parent(decor_view);
}

static gboolean delayed_close_window(gpointer window)
{
	if (GTK_IS_WINDOW(window))
		gtk_window_close(GTK_WINDOW(window));
	return G_SOURCE_REMOVE;
}

/* -- GTK window operations the controller asks for over JNI -- */

JNIEXPORT void JNICALL Java_android_atl_ATLActivityController_nativeDecorAdd(JNIEnv *env, jclass class, jobject activity)
{
	if ((*env)->ExceptionCheck(env))
		return;

	GtkWidget *decor_view = get_decor_view(env, activity);
	if ((*env)->ExceptionCheck(env))
		return;

	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_add_child(stack, decor_view);
}

JNIEXPORT void JNICALL Java_android_atl_ATLActivityController_nativeDecorRemove(JNIEnv *env, jclass class, jobject activity)
{
	if ((*env)->ExceptionCheck(env))
		return;

	GtkWidget *decor_view = get_decor_view(env, activity);
	if ((*env)->ExceptionCheck(env))
		return;

	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_remove(stack, decor_view);
}

JNIEXPORT void JNICALL Java_android_atl_ATLActivityController_nativeDecorShow(JNIEnv *env, jclass class, jobject activity)
{
	if ((*env)->ExceptionCheck(env))
		return;

	GtkWidget *decor_view = get_decor_view(env, activity);
	if ((*env)->ExceptionCheck(env))
		return;

	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_set_visible_child(stack, decor_view);
}

JNIEXPORT void JNICALL Java_android_atl_ATLActivityController_nativeCloseIfLast(JNIEnv *env, jclass class, jint remaining, jlong native_window)
{
	if ((*env)->ExceptionCheck(env))
		return;

	/* The controller only reports an empty stack here; closing the GTK window
	 * kills the session (a window close-request still runs activity_close_all
	 * first, via app_exit). */
	if (remaining == 0 && native_window)
		g_idle_add(delayed_close_window, _PTR(native_window));
}

JNIEXPORT void JNICALL Java_android_atl_ATLActivityController_nativeSetBackSensitive(JNIEnv *env, jclass class, jboolean sensitive)
{
	if ((*env)->ExceptionCheck(env))
		return;

	back_button_set_sensitive(sensitive);
}

/* -- the four launcher seam entry points main.c/back_button.c consume.
 *    Signatures are frozen; each one is now a single host event. -- */

void activity_start(JNIEnv *env, jobject activity_object)
{
	/* Returns with the exception pending: main.c:325/417/843 call
	 * atl_jni_handle_uncaught_exception() afterwards. */
	controller_host_event(env, HOST_EVENT_START, activity_object);
}

void activity_window_ready(void)
{
	JNIEnv *env = get_jni_env();

	controller_host_event(env, HOST_EVENT_WINDOW_READY, NULL);
	if ((*env)->ExceptionCheck(env))
		atl_jni_handle_uncaught_exception(env, "Activity.onWindowFocusChanged");
}

void current_activity_back_pressed(void)
{
	JNIEnv *env = get_jni_env();

	controller_host_event(env, HOST_EVENT_BACK, NULL);
	if ((*env)->ExceptionCheck(env))
		atl_jni_handle_uncaught_exception(env, "Activity.onBackPressed");
}

void activity_close_all(void)
{
	JNIEnv *env = get_jni_env();

	/* Must NOT close the GTK window: app_exit does that after this returns. */
	controller_host_event(env, HOST_EVENT_WINDOW_CLOSE_REQUEST, NULL);
	if ((*env)->ExceptionCheck(env))
		atl_jni_handle_uncaught_exception(env, "Activity shutdown");
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeOpenURI(JNIEnv *env, jclass class, jstring uriString)
{
	static XdpPortal *portal = NULL;
	if (!portal) {
		portal = xdp_portal_new();
	}

	const char *uri = (*env)->GetStringUTFChars(env, uriString, NULL);
	xdp_portal_open_uri(portal, NULL, uri, XDP_OPEN_URI_FLAG_NONE, NULL, NULL, NULL);
	(*env)->ReleaseStringUTFChars(env, uriString, uri);
}

extern GtkWindow *window; // TODO: get this in a better way

struct filechooser_callback_data {
	jobject activity;
	jint request_code;
	jint action;
};

#define RESULT_OK       -1
#define RESULT_CANCELED 0

static void file_dialog_callback(GObject *source_object, GAsyncResult *res, gpointer data)
{
	struct filechooser_callback_data *d = data;
	GtkFileDialog *dialog = GTK_FILE_DIALOG(source_object);
	GFile *(*const finish_functions[])(GtkFileDialog *, GAsyncResult *, GError **) = {
		gtk_file_dialog_open_finish,
		gtk_file_dialog_save_finish,
		gtk_file_dialog_select_folder_finish,
	};

	GFile *file = finish_functions[d->action](dialog, res, NULL);
	JNIEnv *env = get_jni_env();
	jmethodID fileChooserResultCallback = _METHOD(handle_cache.activity.class, "fileChooserResultCallback", "(IIILjava/lang/String;)V");
	if ((*env)->ExceptionCheck(env))
		atl_jni_handle_uncaught_exception(env, "Activity.fileChooserResultCallback lookup");

	if (file) {
		char *uri = g_file_get_uri(file);

		jstring uri_string = _JSTRING(uri);
		atl_jni_handle_uncaught_exception(env, "Activity file URI");
		(*env)->CallVoidMethod(env, d->activity, fileChooserResultCallback, d->request_code, RESULT_OK, d->action, uri_string);
		(*env)->DeleteLocalRef(env, uri_string);
		if ((*env)->ExceptionCheck(env))
			atl_jni_handle_uncaught_exception(env, "Activity.fileChooserResultCallback");

		g_free(uri);
		g_object_unref(file);
	} else {
		(*env)->CallVoidMethod(env, d->activity, fileChooserResultCallback, d->request_code, RESULT_CANCELED, d->action, NULL);
		if ((*env)->ExceptionCheck(env))
			atl_jni_handle_uncaught_exception(env, "Activity.fileChooserResultCallback");
	}
	free(d);
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeFileChooser(JNIEnv *env, jobject this, jint action, jstring type_jstring, jstring filename_jstring, jint request_code)
{
	const char *chooser_title = ((char *[]){"Open File", "Save File", "Select Folder"})[action];
	GtkFileDialog *dialog = gtk_file_dialog_new();
	gtk_file_dialog_set_title(GTK_FILE_DIALOG(dialog), chooser_title);

	const char *type = type_jstring ? (*env)->GetStringUTFChars(env, type_jstring, NULL) : NULL;
	if (type && !strchr(type, '*')) {
		GtkFileFilter *filter = gtk_file_filter_new();
		gtk_file_filter_add_mime_type(filter, type);
		gtk_file_filter_set_name(filter, type);
		gtk_file_dialog_set_default_filter(GTK_FILE_DIALOG(dialog), filter);
		(*env)->ReleaseStringUTFChars(env, type_jstring, type);
	}
	const char *filename = filename_jstring ? (*env)->GetStringUTFChars(env, filename_jstring, NULL) : NULL;
	if (filename) {
		gtk_file_dialog_set_initial_name(GTK_FILE_DIALOG(dialog), filename);
		(*env)->ReleaseStringUTFChars(env, filename_jstring, filename);
	}

	struct filechooser_callback_data *callback_data = malloc(sizeof(struct filechooser_callback_data));
	callback_data->activity = _REF(this);
	callback_data->request_code = request_code;
	callback_data->action = action;
	void (*const file_dialog_functions[])(GtkFileDialog *, GtkWindow *, GCancellable *, GAsyncReadyCallback, gpointer) = {
		gtk_file_dialog_open,
		gtk_file_dialog_save,
		gtk_file_dialog_select_folder,
	};
	file_dialog_functions[action](dialog, window, NULL, file_dialog_callback, callback_data);
}

JNIEXPORT jboolean JNICALL Java_android_app_Activity_isInMultiWindowMode(JNIEnv *env, jobject this)
{
	return !gtk_window_is_maximized(window);
}
