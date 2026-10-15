#include <gtk/gtk.h>
#include <stdbool.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_atl_ATLMediaContentProvider.h"

extern GtkWindow *window;

static void file_dialog_callback(GObject *dialog, GAsyncResult *res, gpointer user_data)
{
	GError *error = NULL;
	GFile *file = gtk_file_dialog_select_folder_finish(GTK_FILE_DIALOG(dialog), res, &error);
	char *path = file ? g_file_get_path(file) : NULL;
	if (error && !g_error_matches(error, GTK_DIALOG_ERROR, GTK_DIALOG_ERROR_DISMISSED))
		g_warning("Media directory selection failed: %s", error->message);
	if (g_getenv("ATL_DEBUG_MEDIA"))
		g_message("Media folder chooser: file=%d local_path=%d error=%s", file != NULL, path != NULL, error ? error->message : "none");
	JNIEnv *env = get_jni_env();
	jobject this = (jobject)user_data;
	(*env)->CallVoidMethod(env, this, _METHOD(_CLASS(this), "setSelectedDirectory", "(Ljava/lang/String;)V"), path ? _JSTRING(path) : NULL);
	g_free(path);
	g_clear_object(&file);
	g_clear_error(&error);
	_UNREF(this);
}

JNIEXPORT void JNICALL Java_android_atl_ATLMediaContentProvider_native_1open_1media_1folder(JNIEnv *env, jobject this)
{
	GtkFileDialog *dialog = gtk_file_dialog_new();
	gtk_file_dialog_set_title(GTK_FILE_DIALOG(dialog), "Choose Media Folder");
	gtk_file_dialog_set_modal(GTK_FILE_DIALOG(dialog), TRUE);
	gtk_file_dialog_select_folder(dialog, window, NULL, file_dialog_callback, _REF(this));
	g_object_unref(dialog);
}
