/* SPDX-License-Identifier: GPL-3.0-only */
#include <gtk/gtk.h>
#include <webkit/webkit.h>
#include "../defines.h"
#include "../util.h"
#include "../jni_exceptions.h"

/* JNI's modified UTF-8 is not WebKit's UTF-8 (notably supplementary characters). */
static jstring message_java_string(JNIEnv *env, const char *text) {
    glong length = 0;
    gunichar2 *chars = g_utf8_to_utf16(text, -1, NULL, &length, NULL);
    if (!chars) return NULL;
    jstring result = (*env)->NewString(env, chars, length);
    g_free(chars);
    return result;
}
static char *message_utf8(JNIEnv *env, jstring value) {
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return NULL;
    char *result = g_utf16_to_utf8(chars, (*env)->GetStringLength(env, value), NULL, NULL, NULL);
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

/* Page scripts cannot access this world's message handler. The injected world
 * reads origin/frame identity itself; the DOM channel carries only message data. */
#define MESSAGE_WORLD "atl-webmessage-isolated-v1"
struct listener_script { WebKitUserScript *isolated, *page; };
struct message_document {
    char *name;
    GQueue queued;
    WebKitScriptMessageReply *waiting;
    JSCContext *context;
};
struct message_state {
    jobject bridge;
    WebKitUserContentManager *manager;
    GHashTable *scripts, *documents;
    gulong message_signal, load_signal;
};

static void finish_wait(struct message_document *doc, const char *text)
{
    if (!doc->waiting) return;
    JSCValue *value = text ? jsc_value_new_string(doc->context, text) : jsc_value_new_null(doc->context);
    webkit_script_message_reply_return_value(doc->waiting, value);
    g_object_unref(value);
    webkit_script_message_reply_unref(doc->waiting);
    doc->waiting = NULL;
    g_clear_object(&doc->context);
}
static void document_free(gpointer ptr)
{
    struct message_document *doc = ptr;
    finish_wait(doc, NULL);
    g_queue_clear_full(&doc->queued, g_free);
    g_free(doc->name);
    g_free(doc);
}
static void script_free(gpointer ptr)
{
    struct listener_script *script = ptr;
    webkit_user_script_unref(script->isolated);
    webkit_user_script_unref(script->page);
    g_free(script);
}
static void state_free(gpointer ptr)
{
    struct message_state *state = ptr;
    g_signal_handler_disconnect(state->manager, state->message_signal);
    webkit_user_content_manager_unregister_script_message_handler(state->manager, "atlWebMessage", MESSAGE_WORLD);
    g_hash_table_destroy(state->documents);
    g_hash_table_destroy(state->scripts);
    (*get_jni_env())->DeleteGlobalRef(get_jni_env(), state->bridge);
    g_object_unref(state->manager);
    g_free(state);
}
static char *string_property(JSCValue *object, const char *name)
{
    JSCValue *value = jsc_value_object_get_property(object, name);
    char *result = jsc_value_is_string(value) ? jsc_value_to_string(value) : NULL;
    g_object_unref(value);
    return result;
}
static gboolean receive_message(WebKitUserContentManager *manager, JSCValue *value,
                                 WebKitScriptMessageReply *reply, struct message_state *state)
{
    if (!jsc_value_is_object(value)) return FALSE;
    g_autofree char *op = string_property(value, "op");
    g_autofree char *name = string_property(value, "name");
    g_autofree char *document = string_property(value, "doc");
    if (!op || !name || !document || !g_hash_table_contains(state->scripts, name)) return FALSE;
    struct message_document *doc = g_hash_table_lookup(state->documents, document);
    if (g_str_equal(op, "gone")) {
        g_hash_table_remove(state->documents, document);
    } else if (g_str_equal(op, "wait")) {
        if (!doc) {
            doc = g_new0(struct message_document, 1);
            doc->name = g_strdup(name);
            g_hash_table_insert(state->documents, g_strdup(document), doc);
        }
        if (doc->waiting) return FALSE;
        doc->waiting = webkit_script_message_reply_ref(reply);
        doc->context = g_object_ref(jsc_value_get_context(value));
        if (!g_queue_is_empty(&doc->queued)) {
            char *text = g_queue_pop_head(&doc->queued);
            finish_wait(doc, text);
            g_free(text);
        }
        return TRUE;
    } else if (g_str_equal(op, "post")) {
        if (!doc || !g_str_equal(doc->name, name)) return FALSE;
        g_autofree char *token = string_property(value, "registration");
        g_autofree char *origin = string_property(value, "origin");
        g_autofree char *text = string_property(value, "data");
        if (!token || !origin || !text) return FALSE;
        JSCValue *main_value = jsc_value_object_get_property(value, "main");
        gboolean main = jsc_value_to_boolean(main_value);
        g_object_unref(main_value);
        JNIEnv *env = get_jni_env();
        if ((*env)->PushLocalFrame(env, 12) < 0) return FALSE;
        jclass clazz = (*env)->GetObjectClass(env, state->bridge);
        jmethodID method = (*env)->GetMethodID(env, clazz, "onMessage",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)V");
        if (method) (*env)->CallVoidMethod(env, state->bridge, method, message_java_string(env, name), message_java_string(env, token),
                                         message_java_string(env, document), message_java_string(env, origin), main, message_java_string(env, text));
        atl_jni_handle_uncaught_exception(env, "WebMessage listener callback");
        (*env)->PopLocalFrame(env, NULL);
    } else return FALSE;
    JSCValue *ack = jsc_value_new_null(jsc_value_get_context(value));
    webkit_script_message_reply_return_value(reply, ack);
    g_object_unref(ack);
    return TRUE;
}
static void message_navigation(WebKitWebView *view, WebKitLoadEvent event, struct message_state *state)
{
    if (event == WEBKIT_LOAD_STARTED) g_hash_table_remove_all(state->documents);
}
JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1installMessageListener(JNIEnv *env, jobject self,
        jlong ptr, jstring jname, jobject bridge, jstring isolated, jstring page)
{
    WebKitWebView *view = _PTR(ptr);
    if (!WEBKIT_IS_WEB_VIEW(view)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "WebView backend disabled");
        return;
    }
    struct message_state *state = g_object_get_data(G_OBJECT(view), "atl-webmessages");
    if (!state) {
        state = g_new0(struct message_state, 1);
        state->bridge = (*env)->NewGlobalRef(env, bridge);
        state->manager = g_object_ref(webkit_web_view_get_user_content_manager(view));
        state->scripts = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, script_free);
        state->documents = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, document_free);
        state->message_signal = g_signal_connect(state->manager, "script-message-with-reply-received::atlWebMessage", G_CALLBACK(receive_message), state);
        webkit_user_content_manager_register_script_message_handler_with_reply(state->manager, "atlWebMessage", MESSAGE_WORLD);
        state->load_signal = g_signal_connect(view, "load-changed", G_CALLBACK(message_navigation), state);
        g_object_set_data_full(G_OBJECT(view), "atl-webmessages", state, state_free);
    }
    g_autofree char *name = message_utf8(env, jname);
    g_autofree char *private_source = message_utf8(env, isolated);
    g_autofree char *public_source = message_utf8(env, page);
    if (!name || !private_source || !public_source) {
        if (!(*env)->ExceptionCheck(env)) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Invalid UTF-16 in message listener");
        return;
    }
    struct listener_script *script = g_new0(struct listener_script, 1);
    script->isolated = webkit_user_script_new_for_world(private_source, WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
            WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, MESSAGE_WORLD, NULL, NULL);
    script->page = webkit_user_script_new(public_source, WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
            WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL);
    webkit_user_content_manager_add_script(state->manager, script->isolated);
    webkit_user_content_manager_add_script(state->manager, script->page);
    g_hash_table_insert(state->scripts, g_strdup(name), script);



}
static gboolean document_named(gpointer key, gpointer value, gpointer name)
{
    return g_str_equal(((struct message_document *)value)->name, name);
}
JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1removeMessageListener(JNIEnv *env, jobject self, jlong ptr, jstring jname)
{
    WebKitWebView *view = _PTR(ptr);
    struct message_state *state = g_object_get_data(G_OBJECT(view), "atl-webmessages");
    if (!state) return;
    g_autofree char *name = message_utf8(env, jname);
    if (!name) return;
    struct listener_script *script = g_hash_table_lookup(state->scripts, name);
    if (script) {
        webkit_user_content_manager_remove_script(state->manager, script->isolated);
        webkit_user_content_manager_remove_script(state->manager, script->page);
        g_hash_table_foreach_remove(state->documents, document_named, (void *)name);
        g_hash_table_remove(state->scripts, name);
    }

}
JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1replyMessage(JNIEnv *env, jobject self, jlong ptr, jstring jdoc, jstring jtext)
{
    WebKitWebView *view = _PTR(ptr);
    struct message_state *state = g_object_get_data(G_OBJECT(view), "atl-webmessages");
    if (!state) return;
    const char *document = (*env)->GetStringUTFChars(env, jdoc, NULL);
    if (!document) return;
    struct message_document *doc = g_hash_table_lookup(state->documents, document);
    if (doc) {
        g_autofree char *text = message_utf8(env, jtext);
        if (!text) { (*env)->ReleaseStringUTFChars(env, jdoc, document); return; }
        if (doc->waiting) finish_wait(doc, text); else g_queue_push_tail(&doc->queued, g_strdup(text));

    }
    (*env)->ReleaseStringUTFChars(env, jdoc, document);
}
