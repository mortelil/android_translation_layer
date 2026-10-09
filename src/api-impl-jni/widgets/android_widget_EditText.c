#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"
#include "../graphics/AndroidTextAttributes.h"

static void changed_cb(GtkEditable *self, gpointer data);
static void free_changed_data(gpointer data, GClosure *closure)
{
	JNIEnv *env = get_jni_env();
	(*env)->DeleteWeakGlobalRef(env, data);
}

#include "../generated_headers/android_widget_EditText.h"

JNIEXPORT jlong JNICALL Java_android_widget_EditText_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *gtk_text = gtk_text_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), gtk_text);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);
	wrapper_widget_register_invalidation_listener(WRAPPER_WIDGET(wrapper));
	g_signal_connect_data(gtk_text, "changed", G_CALLBACK(changed_cb),
	                      (*env)->NewWeakGlobalRef(env, this), free_changed_data, 0);
	return _INTPTR(gtk_text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getText(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_entry_buffer_get_text(gtk_text_get_buffer(gtk_text));
	glong length;
	gunichar2 *utf16 = g_utf8_to_utf16(text, -1, NULL, &length, NULL);
	jstring result = (*env)->NewString(env, utf16, length);
	g_free(utf16);
	return result;
}

struct changed_callback_data {
	jobject this;
	jobject listener;
	jmethodID listener_method;
	jmethodID getText;
};

static void changed_cb(GtkEditable *self, gpointer data)
{
	JNIEnv *env = get_jni_env();
	if ((*env)->ExceptionCheck(env)) return;
	jobject owner = (*env)->NewLocalRef(env, data);
	if (!owner) return;
	glong length;
	gunichar2 *utf16 = g_utf8_to_utf16(gtk_editable_get_text(self), -1, NULL, &length, NULL);
	jstring text = (*env)->NewString(env, utf16, length);
	g_free(utf16);
	jclass cls = (*env)->GetObjectClass(env, owner);
	jmethodID changed = (*env)->GetMethodID(env, cls, "onNativeTextChanged", "(Ljava/lang/String;)V");
	if (changed && !(*env)->ExceptionCheck(env))
		(*env)->CallVoidMethod(env, owner, changed, text);
	// Do not make further Java calls when a watcher throws. Preserve the
	// exception for the enclosing Java/GTK dispatch boundary.
	(*env)->DeleteLocalRef(env, cls);
	(*env)->DeleteLocalRef(env, text);
	(*env)->DeleteLocalRef(env, owner);
}

#define IME_ACTION_SEARCH 3
#define KEYCODE_ENTER     66

static void on_activate(GtkText *gtk_text, struct changed_callback_data *d)
{
	JNIEnv *env = get_jni_env();

	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor, (jlong)0, (jlong)0, IME_ACTION_SEARCH, KEYCODE_ENTER, 0, 0);
	(*env)->CallBooleanMethod(env, d->listener, d->listener_method, d->this, 0, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setOnEditorActionListener(JNIEnv *env, jobject this, jlong widget_ptr, jobject listener)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));

	if (!listener)
		return;

	struct changed_callback_data *callback_data = malloc(sizeof(struct changed_callback_data));
	callback_data->this = _WEAK_REF(this);
	callback_data->listener = _REF(listener);
	callback_data->listener_method = _METHOD(_CLASS(listener), "onEditorAction", "(Landroid/widget/TextView;ILandroid/view/KeyEvent;)Z");

	g_signal_handlers_disconnect_matched(gtk_text, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, on_activate, NULL);
	g_signal_connect(gtk_text, "activate", G_CALLBACK(on_activate), callback_data);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	char *text = atl_text_to_utf8(env, text_jstr);
	if (!text) return;
	gtk_entry_buffer_set_text(gtk_text_get_buffer(GTK_TEXT(_PTR(widget_ptr))), text, -1);
	g_free(text);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setHint(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	const char *text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	gtk_text_set_placeholder_text(GTK_TEXT(_PTR(widget_ptr)), text);
	(*env)->ReleaseStringUTFChars(env, text_jstr, text);
}

JNIEXPORT jstring JNICALL Java_android_widget_EditText_native_1getHint(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkText *gtk_text = GTK_TEXT(_PTR(widget_ptr));
	const char *text = gtk_text_get_placeholder_text(gtk_text);
	return _JSTRING(text);
}

JNIEXPORT jint JNICALL Java_android_widget_EditText_native_1getSelection(JNIEnv *env, jobject this, jlong ptr, jboolean end)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(ptr));
	int start_pos, end_pos;
	if (!gtk_editable_get_selection_bounds(editable, &start_pos, &end_pos))
		start_pos = end_pos = gtk_editable_get_position(editable);
	const char *text = gtk_editable_get_text(editable);
	const char *limit = g_utf8_offset_to_pointer(text, end ? end_pos : start_pos);
	int utf16 = 0;
	for (const char *p = text; p < limit; p = g_utf8_next_char(p))
		utf16 += g_utf8_get_char(p) > 0xffff ? 2 : 1;
	return utf16;
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setSelection(JNIEnv *env, jobject this, jlong ptr, jint start, jint end)
{
	gtk_editable_select_region(GTK_EDITABLE(_PTR(ptr)), start, end);
}
