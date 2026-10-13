/* SPDX-License-Identifier: GPL-3.0-only */
#include <gtk/gtk.h>
#include <stdbool.h>
#include "../defines.h"
#include "../util.h"
#include "../jni_exceptions.h"
#include "../android_view_Window.h"
#include "../widgets/WrapperWidget.h"
#include "JavaTouch.h"

/* This fallback owns only gestures not already handled by a GTK input widget
 * or an existing wrapper controller. Ownership and pointer IDs are per window,
 * never process-global. Java performs the unsplit child-target dispatch. */
#define POINTER_LIMIT 32
struct touch_point { GdkEventSequence *sequence; int id; double x, y; };
struct java_touch {
    GtkWindow *window;
    GtkWidget *root;
    jobject callback;
    struct touch_point points[POINTER_LIMIT];
    int count;
    guint32 time;
    guint generation;
    gboolean clearing;
    gboolean ending;
};

static gboolean deliver(struct java_touch *state, int action)
{
    JNIEnv *env = get_jni_env();
    jint ids[POINTER_LIMIT];
    jfloat coords[POINTER_LIMIT * 4];
    for (int i = 0; i < state->count; i++) {
        graphene_point_t local;
        graphene_point_t raw = GRAPHENE_POINT_INIT(state->points[i].x, state->points[i].y);
        if (!gtk_widget_compute_point(GTK_WIDGET(state->window), state->root, &raw, &local))
            local = raw;
        ids[i] = state->points[i].id;
        coords[4*i] = local.x; coords[4*i+1] = local.y;
        coords[4*i+2] = raw.x; coords[4*i+3] = raw.y;
    }
    gboolean handled = FALSE;
    jobject event = NULL;
    jclass clazz = NULL;
    jfloatArray jcoords = NULL;
    jintArray jids = (*env)->NewIntArray(env, state->count);
    if (!jids) goto cleanup;
    jcoords = (*env)->NewFloatArray(env, 4 * state->count);
    if (!jcoords) goto cleanup;
    (*env)->SetIntArrayRegion(env, jids, 0, state->count, ids);
    (*env)->SetFloatArrayRegion(env, jcoords, 0, 4 * state->count, coords);
    event = (*env)->NewObject(env, handle_cache.motion_event.class,
        handle_cache.motion_event.constructor, 0x1002, action, (jlong)state->time, jids, jcoords);
    if (!event) goto cleanup;
    clazz = (*env)->GetObjectClass(env, state->callback);
    if (!clazz) goto cleanup;
    jmethodID method = (*env)->GetMethodID(env, clazz, "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z");
    if (!method) goto cleanup;
    handled = (*env)->CallBooleanMethod(env, state->callback, method, event);
cleanup:
    (*env)->DeleteLocalRef(env, clazz);
    (*env)->DeleteLocalRef(env, event);
    (*env)->DeleteLocalRef(env, jids);
    (*env)->DeleteLocalRef(env, jcoords);
    return handled;
}

static void clear_touch(struct java_touch *state, gboolean cancel)
{
    if (state->clearing) return;
    state->clearing = TRUE;
    state->generation++;
    if (state->callback) {
        if (cancel && state->count && !state->ending) deliver(state, 3 /* CANCEL */);
        JNIEnv *env = get_jni_env();
        (*env)->DeleteGlobalRef(env, state->callback);
        state->callback = NULL;
    }
    g_clear_object(&state->root);
    state->count = 0;
    state->ending = FALSE;
    state->clearing = FALSE;
}

void atl_cancel_java_touch_fallback(GtkWindow *window)
{
    struct java_touch *state = g_object_get_data(G_OBJECT(window), "atl-java-touch");
    if (state) clear_touch(state, TRUE);
}

static void unmap(GtkWidget *window, struct java_touch *state)
{
    clear_touch(state, TRUE);
    atl_jni_handle_uncaught_exception(get_jni_env(), "touch cancellation on unmap");
}
static void dispose_state(gpointer data)
{
    struct java_touch *state = data;
    clear_touch(state, FALSE);
    g_free(state);
}

static gboolean native_input_chain(GtkWidget *picked, GtkWidget *content)
{
    for (GtkWidget *w = picked; w; w = gtk_widget_get_parent(w)) {
        if (GTK_IS_BUTTON(w) || GTK_IS_EDITABLE(w) || GTK_IS_TEXT_VIEW(w)
            || GTK_IS_RANGE(w) || GTK_IS_LIST_VIEW(w)
            || g_str_has_prefix(G_OBJECT_TYPE_NAME(w), "WebKit")) return TRUE;
        if (g_object_get_data(G_OBJECT(w), "on_touch_listener")
            || g_object_get_data(G_OBJECT(w), "on_intercept_touch_listener")
            || g_object_get_data(G_OBJECT(w), "on_click_listener")) return TRUE;
        if (w == content) break;
    }
    return FALSE;
}

static gboolean capture(GtkEventControllerLegacy *controller, GdkEvent *event, struct java_touch *state)
{
    GdkEventType type = gdk_event_get_event_type(event);
    gboolean down = type == GDK_BUTTON_PRESS || type == GDK_TOUCH_BEGIN;
    gboolean up = type == GDK_BUTTON_RELEASE || type == GDK_TOUCH_END;
    gboolean cancel = type == GDK_TOUCH_CANCEL;
    gboolean move = type == GDK_MOTION_NOTIFY || type == GDK_TOUCH_UPDATE;
    if (!down && !up && !cancel && !move) return FALSE;
    if ((type == GDK_BUTTON_PRESS || type == GDK_BUTTON_RELEASE) && gdk_button_event_get_button(event) != 1) return FALSE;
    GdkEventSequence *sequence = gdk_event_get_event_sequence(event);
    int index = -1;
    for (int i = 0; i < state->count; i++)
        if (state->points[i].sequence == sequence) index = i;
    if (!down && index < 0) return FALSE;
    if (down && index >= 0) { clear_touch(state, TRUE); index = -1; }
    double x = 0, y = 0;
    if (!gdk_event_get_position(event, &x, &y) && index >= 0) { x = state->points[index].x; y = state->points[index].y; }
    if (!state->callback) {
        if (!down) return FALSE;
        GtkWidget *content = atl_window_get_content(state->window);
        graphene_point_t point;
        if (!content || !gtk_widget_get_mapped(content)
            || !gtk_widget_compute_point(GTK_WIDGET(state->window), content, &GRAPHENE_POINT_INIT(x,y), &point)) return FALSE;
        GtkWidget *picked = gtk_widget_pick(content, point.x, point.y, GTK_PICK_DEFAULT);
        if (!picked || native_input_chain(picked, content)) return FALSE;
        GtkWidget *root = NULL;
        for (GtkWidget *w = picked; w && !GTK_IS_WINDOW(w); w = gtk_widget_get_parent(w))
            if (WRAPPER_IS_WIDGET(w) && WRAPPER_WIDGET(w)->jobj) root = w;
        jobject callback = g_object_get_data(G_OBJECT(state->window), "jobject");
        if (!root || !callback) return FALSE;
        JNIEnv *env = get_jni_env();
        state->callback = (*env)->NewGlobalRef(env, callback);
        state->root = g_object_ref(root);
    }
    if (index < 0) {
        if (state->count == POINTER_LIMIT) return TRUE;
        int id = 0;
        for (; id < POINTER_LIMIT; id++) {
            gboolean used = FALSE;
            for (int i = 0; i < state->count; i++) used |= state->points[i].id == id;
            if (!used) break;
        }
        index = state->count++;
        state->points[index] = (struct touch_point){sequence, id, x, y};
    }
    state->points[index].x = x; state->points[index].y = y;
    state->time = gdk_event_get_time(event);
    int action = cancel ? 3 : down ? (state->count == 1 ? 0 : 5 | index << 8)
                                : up ? (state->count == 1 ? 1 : 6 | index << 8) : 2;
    state->ending = action == 1 || action == 3;
    guint generation = state->generation;
    gboolean handled = deliver(state, action);
    atl_jni_handle_uncaught_exception(get_jni_env(), "Window.dispatchTouchEvent");
    if (generation != state->generation) return TRUE; // callback replaced or unmapped its window content
    state->ending = FALSE;
    if (down && state->count == 1 && !handled) {
        clear_touch(state, FALSE);
        return FALSE; // Do not steal an unconsumed DOWN from GTK.
    }
    if (cancel) clear_touch(state, FALSE);
    else if (up) {
        for (int i = index; i + 1 < state->count; i++) state->points[i] = state->points[i+1];
        if (--state->count == 0) clear_touch(state, FALSE);
    }
    return TRUE;
}

void atl_install_java_touch_fallback(GtkWindow *window)
{
    if (g_object_get_data(G_OBJECT(window), "atl-java-touch")) return;
    struct java_touch *state = g_new0(struct java_touch, 1);
    state->window = window;
    g_object_set_data_full(G_OBJECT(window), "atl-java-touch", state, dispose_state);
    GtkEventController *controller = gtk_event_controller_legacy_new();
    gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_CAPTURE);
    g_signal_connect(controller, "event", G_CALLBACK(capture), state);
    gtk_widget_add_controller(GTK_WIDGET(window), controller);
    g_signal_connect(window, "unmap", G_CALLBACK(unmap), state);
}
