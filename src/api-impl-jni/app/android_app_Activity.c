#include <gtk/gtk.h>
#include "../android_view_Window.h"
#include <libportal/portal.h>

#include <jni.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"
#include "../jni_exceptions.h"
#include "../generated_headers/android_app_Activity.h"
#include "../../main-executable/back_button.h"
#include "android_app_Activity.h"

extern GtkWindow *window;

static GList *activity_backlog = NULL;
static jobject activity_current = NULL;

static GtkWidget *get_decor_view(JNIEnv *env, jobject activity)
{
	jobject window_jobj = _GET_OBJ_FIELD(activity, "window", "Landroid/view/Window;");
	jobject decor_view_jobj = _GET_OBJ_FIELD(window_jobj, "decorView", "Landroid/view/ViewGroup;");
	GtkWidget *decor_view = GTK_WIDGET(_PTR(_GET_LONG_FIELD(decor_view_jobj, "widget")));
	return gtk_widget_get_parent(decor_view);
}

static void activity_close(JNIEnv *env, jobject activity)
{
	// in case some exception was left unhandled in native code, handle it here so we don't confuse it with an exception thrown by onDestroy
	// (and so the get_decor_view() lookups below never run with a pending exception)
	if ((*env)->ExceptionCheck(env))
		return;

	GtkWidget *decor_view = get_decor_view(env, activity);
	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_remove(stack, decor_view);

	/* -- run the activity's onDestroy -- */
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onDestroy);
	if ((*env)->ExceptionCheck(env))
		return;
}

static void activity_unfocus(JNIEnv *env, jobject activity)
{
	if (!_GET_BOOL_FIELD(activity, "paused")) {
		(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPause);
		if ((*env)->ExceptionCheck(env))
			return;
	}

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onStop);
	if ((*env)->ExceptionCheck(env))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onWindowFocusChanged, false);
	if ((*env)->ExceptionCheck(env))
		return;
}

static void activity_focus(JNIEnv *env, jobject activity)
{
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.performStart);
	if ((*env)->ExceptionCheck(env))
		return;
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	GtkWidget *decor_view = get_decor_view(env, activity);
	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_set_visible_child(stack, decor_view);

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.performResume);
	if ((*env)->ExceptionCheck(env))
		return;
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPostResume);
	if ((*env)->ExceptionCheck(env))
		return;
	if (_GET_BOOL_FIELD(activity, "finishing"))
		return;

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onWindowFocusChanged, true);
	if ((*env)->ExceptionCheck(env))
		return;
}

static void activity_update_current(JNIEnv *env)
{
	jobject activity_new = activity_backlog ? g_list_first(activity_backlog)->data : NULL;

	if (activity_current != activity_new) {
		if (activity_current)
			activity_unfocus(env, activity_current);
		if ((*env)->ExceptionCheck(env))
			return;
		activity_current = NULL;

		if (activity_new)
			activity_focus(env, activity_new);
		if ((*env)->ExceptionCheck(env))
			return;
		if (activity_new && _GET_BOOL_FIELD(activity_new, "finishing"))
			return;

		activity_current = activity_new;
	}

	if (activity_current != NULL) {
		jclass current_activity_class = (*env)->GetObjectClass(env, activity_current);
		if (!current_activity_class)
			return;
		jmethodID current_activity_on_back_pressed_method_id = (*env)->GetMethodID(env, current_activity_class, "onBackPressed", "()V");
		(*env)->DeleteLocalRef(env, current_activity_class);
		if ((*env)->ExceptionCheck(env))
			return;

		if (g_list_length(activity_backlog) > 1 || handle_cache.activity.onBackPressed != current_activity_on_back_pressed_method_id) {
			back_button_set_sensitive(true);
		} else {
			back_button_set_sensitive(false);
		}
	}
}

void activity_window_ready(void)
{
	JNIEnv *env = get_jni_env();

	for (GList *l = activity_backlog; l != NULL; l = l->next) {
		(*env)->CallVoidMethod(env, l->data, handle_cache.activity.onWindowFocusChanged, true);
		if ((*env)->ExceptionCheck(env))
			atl_jni_handle_uncaught_exception(env, "Activity.onWindowFocusChanged");
	}
}

void current_activity_back_pressed(void)
{
	JNIEnv *env = get_jni_env();

	jclass current_activity_class = (*env)->GetObjectClass(env, activity_current);
	atl_jni_handle_uncaught_exception(env, "Activity.onBackPressed class");
	jmethodID current_activity_on_back_pressed_method_id = (*env)->GetMethodID(env, current_activity_class, "onBackPressed", "()V");
	(*env)->DeleteLocalRef(env, current_activity_class);
	if ((*env)->ExceptionCheck(env))
		atl_jni_handle_uncaught_exception(env, "Activity.onBackPressed lookup");

	// Either a new activity was added to the backlog or the current activity's onBackPressed method was changed
	if (g_list_length(activity_backlog) > 1 || handle_cache.activity.onBackPressed != current_activity_on_back_pressed_method_id) {
		(*env)->CallVoidMethod(env, activity_current, handle_cache.activity.onBackPressed);
		if ((*env)->ExceptionCheck(env))
			atl_jni_handle_uncaught_exception(env, "Activity.onBackPressed");
	} else {
		back_button_set_sensitive(false);
	}
}

void activity_close_all(void)
{
	GList *activities, *l;
	JNIEnv *env = get_jni_env();
	// local backup of the backlog
	activities = activity_backlog;
	// deactivate all activities
	activity_backlog = NULL;
	activity_update_current(env);
	atl_jni_handle_uncaught_exception(env, "Activity shutdown");
	// destroy all activities
	for (l = activities; l != NULL; l = l->next) {
		activity_close(env, l->data);
		atl_jni_handle_uncaught_exception(env, "Activity.onDestroy");
		_UNREF(l->data);
	}
	g_list_free(activities);
}

static gboolean delayed_close_window(gpointer window)
{
	if (activity_backlog == NULL && GTK_IS_WINDOW(window))
		gtk_window_close(GTK_WINDOW(window));
	return G_SOURCE_REMOVE;
}

void activity_start(JNIEnv *env, jobject activity_object)
{
	if ((*env)->ExceptionCheck(env))
		return;
	if (activity_current)
		activity_unfocus(env, activity_current);
	if ((*env)->ExceptionCheck(env))
		return;
	activity_current = NULL;
	/* Called both from GTK and Java nativeStartActivity. Return pending to the
	 * caller; only the outer native-owned entrypoint may declare it uncaught. */
	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.performCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		return;

	if (_GET_BOOL_FIELD(activity_object, "finishing")) { // finish() was called before the activity was created
		return;
	}

	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onPostCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		return;

	jobject retained_activity = _REF(activity_object);
	if (!retained_activity)
		return;
	activity_backlog = g_list_prepend(activity_backlog, retained_activity);

	GtkWidget *decor_view = get_decor_view(env, activity_object);
	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	gtk_stack_add_child(stack, decor_view);

	activity_update_current(env);
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeFinish(JNIEnv *env, jobject this, jlong window)
{
	GList *l;
	jobject removed_activity = NULL;
	for (l = activity_backlog; l != NULL; l = l->next) {
		if ((*env)->IsSameObject(env, this, l->data)) {
			removed_activity = l->data;
			activity_backlog = g_list_delete_link(activity_backlog, l);
			break;
		}
	}
	activity_update_current(env);
	if (removed_activity) {
		if (!(*env)->ExceptionCheck(env))
			activity_close(env, removed_activity);
		_UNREF(removed_activity);
	}
	if ((*env)->ExceptionCheck(env))
		return;
	if (activity_backlog == NULL && window)
		g_idle_add(delayed_close_window, _PTR(window));
}

JNIEXPORT void JNICALL Java_android_app_Activity_nativeStartActivity(JNIEnv *env, jclass class, jobject activity)
{
	activity_start(env, activity);
}

JNIEXPORT jboolean JNICALL Java_android_app_Activity_nativeResumeActivity(JNIEnv *env, jclass class, jclass activity_class, jobject intent)
{
	GList *l;
	GList *activities_to_close = NULL;
	jboolean found = JNI_FALSE;
	for (l = activity_backlog; l != NULL; l = l->next) {
		jclass backlog_class = _CLASS(l->data);
		if (!backlog_class)
			return JNI_FALSE;
		jboolean matches = (*env)->IsSameObject(env, activity_class, backlog_class);
		(*env)->DeleteLocalRef(env, backlog_class);
		if (matches) {
			if (l != activity_backlog) {
				activities_to_close = activity_backlog;
				activity_backlog = l;
				l->prev->next = NULL;
				l->prev = NULL;
			}
            /* Android never delivers a new intent while the target is resumed.
             * A top activity stays started: pause/new-intent/resume, no recreate. */
            gboolean resume_top = activity_current == l->data && !_GET_BOOL_FIELD(l->data, "paused");
            if (resume_top) {
                (*env)->CallVoidMethod(env, l->data, handle_cache.activity.onPause);
                if ((*env)->ExceptionCheck(env)) goto cleanup;
            }
			/* -- run the activity's onNewIntent -- */
			(*env)->CallVoidMethod(env, l->data, handle_cache.activity.onNewIntent, intent);
			if ((*env)->ExceptionCheck(env))
				goto cleanup;
            if (resume_top && !_GET_BOOL_FIELD(l->data, "finishing")) {
                (*env)->CallVoidMethod(env, l->data, handle_cache.activity.performResume);
                if ((*env)->ExceptionCheck(env)) goto cleanup;
                (*env)->CallVoidMethod(env, l->data, handle_cache.activity.onPostResume);
                if ((*env)->ExceptionCheck(env)) goto cleanup;
            }
			found = JNI_TRUE;
			break;
		}
	}
	activity_update_current(env);

cleanup:
	for (l = activities_to_close; l != NULL; l = l->next) {
		if (!(*env)->ExceptionCheck(env))
			activity_close(env, l->data);
		_UNREF(l->data);
	}
	g_list_free(activities_to_close);

	return found;
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

JNIEXPORT jboolean JNICALL Java_android_app_Activity_isTaskRoot(JNIEnv *env, jobject this)
{
	jobject root_activity = activity_backlog ? g_list_last(activity_backlog)->data : NULL;
	// NULL means that we are currently creating the root activity, so no other activity can exist yet
	return root_activity == NULL || (*env)->IsSameObject(env, this, root_activity);
}
