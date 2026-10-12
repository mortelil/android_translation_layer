/* SPDX-License-Identifier: GPL-3.0-only */
#include <gtk/gtk.h>
#include <webkit/webkit.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "../util.h"

/* A private prompt channel supplies synchronous JS return values while Java
 * runs on a serial worker and the GTK thread remains available. No dialog is
 * shown for this channel. The document-scoped capability lives in a closure;
 * Java validates it and exposes only public @JavascriptInterface methods. */
typedef struct {
    jobject bridge;
    jmethodID invoke;
    GThreadPool *worker;
    GCancellable *cancel;
    gboolean destroyed;
} JavascriptState;

static gchar *java_utf8(JNIEnv *env, jstring value)
{
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return NULL;
    gchar *text = g_utf16_to_utf8(chars, (*env)->GetStringLength(env, value), NULL, NULL, NULL);
    (*env)->ReleaseStringChars(env, value, chars);
    if (!text) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Invalid UTF-16 JavaScript string");
    return text;
}

static jstring java_string(JNIEnv *env, const gchar *text)
{
    glong len;
    gunichar2 *chars = g_utf8_to_utf16(text, -1, NULL, &len, NULL);
    if (!chars) return (*env)->NewStringUTF(env, "null");
    jstring result = (*env)->NewString(env, chars, len);
    g_free(chars);
    return result;
}

static void state_free(gpointer data)
{
    JavascriptState *state = data;
    if (state->worker) g_thread_pool_free(state->worker, FALSE, FALSE);
    if (state->bridge) {
        JNIEnv *env = get_jni_env();
        (*env)->DeleteGlobalRef(env, state->bridge);
    }
    g_object_unref(state->cancel);
    g_free(state);
}

static JavascriptState *state_for(WebKitWebView *view)
{
    JavascriptState *state = g_object_get_data(G_OBJECT(view), "atl-javascript");
    if (!state) {
        state = g_new0(JavascriptState, 1);
        state->cancel = g_cancellable_new();
        g_object_set_data_full(G_OBJECT(view), "atl-javascript", state, state_free);
    }
    return state;
}

typedef struct {
    WebKitWebView *view;
    WebKitScriptDialog *dialog;
    gchar *request, *response;
} BridgeCall;

static void detach_worker(gpointer unused)
{
    (void)unused;
    (*jvm)->DetachCurrentThread(jvm);
}
static GPrivate attached_worker = G_PRIVATE_INIT(detach_worker);

static gboolean bridge_complete(gpointer data)
{
    BridgeCall *call = data;
    webkit_script_dialog_prompt_set_text(call->dialog, call->response ? call->response :
        "{\"error\":\"Java bridge unavailable\"}");
    webkit_script_dialog_close(call->dialog);
    webkit_script_dialog_unref(call->dialog);
    g_object_unref(call->view);
    g_free(call->request);
    g_free(call->response);
    g_free(call);
    return G_SOURCE_REMOVE;
}

static void bridge_worker(gpointer data, gpointer unused)
{
    (void)unused;
    BridgeCall *call = data;
    JavascriptState *state = g_object_get_data(G_OBJECT(call->view), "atl-javascript");
    JNIEnv *env = NULL;
    int status = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, NULL);
        if (status == JNI_OK) g_private_set(&attached_worker, GINT_TO_POINTER(1));
    }
    if (status == JNI_OK) {
        if ((*env)->PushLocalFrame(env, 16) < 0) {
            (*env)->ExceptionClear(env);
            g_idle_add_full(G_PRIORITY_DEFAULT, bridge_complete, call, NULL);
            return;
        }
        jstring input = java_string(env, call->request);
        jstring response = (*env)->CallObjectMethod(env, state->bridge, state->invoke, input);
        if ((*env)->ExceptionCheck(env)) {
            // Exception details may include secrets; propagate a generic error.
            (*env)->ExceptionClear(env);
        } else if (response) call->response = java_utf8(env, response);
        (*env)->PopLocalFrame(env, NULL);
    }
    // Always enqueue: g_main_context_invoke can run inline on a worker.
    g_idle_add_full(G_PRIORITY_DEFAULT, bridge_complete, call, NULL);
}

static gboolean script_dialog(WebKitWebView *view, WebKitScriptDialog *dialog, gpointer unused)
{
    (void)unused;
    if (webkit_script_dialog_get_dialog_type(dialog) != WEBKIT_SCRIPT_DIALOG_PROMPT ||
        g_strcmp0(webkit_script_dialog_get_message(dialog), "__ATL_JAVA_BRIDGE__")) return FALSE;
    JavascriptState *state = state_for(view);
    const char *request = webkit_script_dialog_prompt_get_default_text(dialog);
    if (state->destroyed || !state->bridge || !request || strlen(request) > 1024 * 1024) {
        webkit_script_dialog_prompt_set_text(dialog, "{\"error\":\"Java bridge unavailable\"}");
        return TRUE;
    }
    BridgeCall *call = g_new0(BridgeCall, 1);
    call->view = g_object_ref(view);
    call->dialog = webkit_script_dialog_ref(dialog);
    call->request = g_strdup(request);
    g_thread_pool_push(state->worker, call, NULL);
    return TRUE;
}

typedef struct { jobject callback; } Evaluation;

static void evaluation_done(GObject *source, GAsyncResult *result, gpointer data)
{
    Evaluation *evaluation = data;
    GError *error = NULL;
    JSCValue *value = webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source), result, &error);
    gchar *json = value ? jsc_value_to_json(value, 0) : NULL;
    if (error && !g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED))
        g_printerr("ATL WebView: JavaScript evaluation failed (code %d; details redacted)\n", error->code);
    JNIEnv *env = get_jni_env();
    if (evaluation->callback) {
        (*env)->PushLocalFrame(env, 8);
        jclass type = (*env)->GetObjectClass(env, evaluation->callback);
        jmethodID receive = (*env)->GetMethodID(env, type, "onReceiveValue", "(Ljava/lang/Object;)V");
        (*env)->CallVoidMethod(env, evaluation->callback, receive, java_string(env, json ? json : "null"));
        (*env)->PopLocalFrame(env, NULL);
        (*env)->DeleteGlobalRef(env, evaluation->callback);
    }
    g_clear_object(&value);
    g_clear_error(&error);
    g_free(json);
    g_free(evaluation);
}

static WebKitWebView *require_webview(JNIEnv *env, jlong widget)
{
    if (!getenv("ATL_UGLY_ENABLE_WEBVIEW") || !WEBKIT_IS_WEB_VIEW((gpointer)(intptr_t)widget)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "WebView backend is disabled");
        return NULL;
    }
    return (WebKitWebView *)(intptr_t)widget;
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1evaluateJavascript(JNIEnv *env, jobject self,
    jlong widget, jstring script, jobject callback)
{
    (void)self;
    WebKitWebView *view = require_webview(env, widget);
    if (!view) return;
    gchar *text = java_utf8(env, script);
    if (!text) return;
    Evaluation *evaluation = g_new0(Evaluation, 1);
    if (callback) evaluation->callback = (*env)->NewGlobalRef(env, callback);
    webkit_web_view_evaluate_javascript(view, text, -1, NULL, NULL,
        state_for(view)->cancel, evaluation_done, evaluation);
    g_free(text);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1setJavascriptBridge(JNIEnv *env, jobject self,
    jlong widget, jobject bridge, jstring script)
{
    (void)self;
    WebKitWebView *view = require_webview(env, widget);
    if (!view) return;
    JavascriptState *state = state_for(view);
    if (!state->bridge) {
        state->bridge = (*env)->NewGlobalRef(env, bridge);
        jclass type = (*env)->GetObjectClass(env, bridge);
        state->invoke = (*env)->GetMethodID(env, type, "invoke", "(Ljava/lang/String;)Ljava/lang/String;");
        (*env)->DeleteLocalRef(env, type);
        GError *error = NULL;
        state->worker = g_thread_pool_new(bridge_worker, NULL, 1, TRUE, &error);
        if (!state->worker) {
            (*env)->DeleteGlobalRef(env, state->bridge);
            state->bridge = NULL;
            g_clear_error(&error);
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Could not start JavaScript bridge worker");
            return;
        }
        g_signal_connect(view, "script-dialog", G_CALLBACK(script_dialog), NULL);
    }
    gchar *text = java_utf8(env, script);
    if (!text) return;
    WebKitUserContentManager *manager = webkit_web_view_get_user_content_manager(view);
    webkit_user_content_manager_remove_all_scripts(manager);
    WebKitUserScript *injection = webkit_user_script_new(text, WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL);
    webkit_user_content_manager_add_script(manager, injection);
    webkit_user_script_unref(injection);
    g_free(text);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_native_1destroyJavascript(JNIEnv *env, jobject self, jlong widget)
{
    (void)env; (void)self;
    if (!getenv("ATL_UGLY_ENABLE_WEBVIEW")) return;
    WebKitWebView *view = (WebKitWebView *)(intptr_t)widget;
    JavascriptState *state = state_for(view);
    state->destroyed = TRUE;
    g_cancellable_cancel(state->cancel);
    webkit_user_content_manager_remove_all_scripts(webkit_web_view_get_user_content_manager(view));
    webkit_web_view_stop_loading(view);
}
