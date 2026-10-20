#include <gtk/gtk.h>
#include <gst/gst.h>
#include <gst/app/gstappsink.h>
#include <gst/video/video.h>
#include "../util.h"
#include "../widgets/android_view_SurfaceView.h"
#include "../generated_headers/android_media_MediaPlayer.h"

/* One playbin per player. GStreamer owns demux/decoder threads and A/V clock.
 * GTK presentation is confined to the main context. Pending frames and bus
 * watches hold references so reset/release cannot leave callbacks using freed
 * native state. Java events carry the reset generation as a second barrier. */
typedef struct {
    gint refs, closed, preparing, seeking;
    GstElement *pipeline, *sink;
    GstStructure *headers;
    guint bus_watch;
    jweak owner;
    jmethodID post_event;
    jint token;
    GMutex frames;
    GdkTexture *pending;
    gboolean scheduled;
    GWeakRef widget;
    int width, height;
} Player;

static Player *player_ref(Player *p) { g_atomic_int_inc(&p->refs); return p; }
static void player_unref(gpointer data)
{
    Player *p = data;
    if (!g_atomic_int_dec_and_test(&p->refs)) return;
    JNIEnv *env = get_jni_env();
    (*env)->DeleteWeakGlobalRef(env, p->owner);
    g_clear_object(&p->pending);
    g_weak_ref_clear(&p->widget);
    if (p->headers) gst_structure_free(p->headers);
    g_mutex_clear(&p->frames);
    g_free(p);
}
static void event(Player *p, int what, int a, int b)
{
    if (g_atomic_int_get(&p->closed)) return;
    JNIEnv *env = NULL;
    gboolean attached = (*jvm)->GetEnv(jvm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **) &env, NULL) != JNI_OK) return;
    jobject owner = (*env)->NewLocalRef(env, p->owner);
    if (!owner) { if (attached) (*jvm)->DetachCurrentThread(jvm); return; }
    (*env)->CallVoidMethod(env, owner, p->post_event, p->token, what, a, b);
    (*env)->DeleteLocalRef(env, owner);
    if (attached) (*jvm)->DetachCurrentThread(jvm);
}
static gboolean present(gpointer data)
{
    Player *p = data;
    g_mutex_lock(&p->frames);
    GdkTexture *texture = p->pending;
    p->pending = NULL;
    p->scheduled = FALSE;
    g_mutex_unlock(&p->frames);
    SurfaceViewWidget *view = g_weak_ref_get(&p->widget);
    if (!g_atomic_int_get(&p->closed) && view && texture) {
        surface_view_widget_set_texture(view, texture, FALSE); // transfers reference
        texture = NULL;
        for (GtkWidget *w = gtk_widget_get_parent(GTK_WIDGET(view)); w; w = gtk_widget_get_parent(w))
            gtk_widget_queue_draw(w);
    }
    g_clear_object(&texture);
    g_clear_object(&view);
    return G_SOURCE_REMOVE;
}
static GstFlowReturn frame(GstAppSink *sink, Player *p, gboolean preroll)
{
    GstSample *sample = preroll ? gst_app_sink_pull_preroll(sink) : gst_app_sink_pull_sample(sink);
    if (!sample) return GST_FLOW_EOS;
    GstVideoInfo info;
    GstVideoFrame mapped;
    if (!g_atomic_int_get(&p->closed) && gst_video_info_from_caps(&info, gst_sample_get_caps(sample)) &&
        gst_video_frame_map(&mapped, &info, gst_sample_get_buffer(sample), GST_MAP_READ)) {
        int w = GST_VIDEO_INFO_WIDTH(&info), h = GST_VIDEO_INFO_HEIGHT(&info);
        gsize stride = (gsize) w * 4;
        guint8 *pixels = g_try_malloc_n(stride, h);
        if (pixels) {
            for (int y = 0; y < h; ++y)
                memcpy(pixels + y * stride, (guint8 *) GST_VIDEO_FRAME_PLANE_DATA(&mapped, 0) +
                       y * GST_VIDEO_FRAME_PLANE_STRIDE(&mapped, 0), stride);
            GBytes *bytes = g_bytes_new_take(pixels, stride * h);
            GdkTexture *texture = gdk_memory_texture_new(w, h, GDK_MEMORY_R8G8B8A8, bytes, stride);
            g_bytes_unref(bytes);
            g_mutex_lock(&p->frames);
            g_clear_object(&p->pending);
            p->pending = texture;
            if (!p->scheduled) {
                p->scheduled = TRUE;
                g_idle_add_full(G_PRIORITY_DEFAULT, present, player_ref(p), player_unref);
            }
            if (p->width != w || p->height != h) { p->width = w; p->height = h; event(p, 5, w, h); }
            g_mutex_unlock(&p->frames);
        }
        gst_video_frame_unmap(&mapped);
    }
    gst_sample_unref(sample);
    return GST_FLOW_OK;
}
static GstFlowReturn sample(GstAppSink *sink, gpointer data) { return frame(sink, data, FALSE); }
static GstFlowReturn preroll(GstAppSink *sink, gpointer data) { return frame(sink, data, TRUE); }
static gboolean bus_message(GstBus *bus, GstMessage *msg, gpointer data)
{
    Player *p = data;
    if (g_atomic_int_get(&p->closed)) return G_SOURCE_CONTINUE;
    switch (GST_MESSAGE_TYPE(msg)) {
    case GST_MESSAGE_ERROR: {
        GError *error = NULL;
        gchar *debug = NULL;
        gst_message_parse_error(msg, &error, &debug);
        // Do not log source URLs: they may contain signed tokens.
        fprintf(stderr, "MediaPlayer: GStreamer error domain=%s code=%d\n", g_quark_to_string(error->domain), error->code);
        g_clear_error(&error); g_free(debug);
        g_atomic_int_set(&p->preparing, 0);
        event(p, 100, -1004, 0);
        break;
    }
    case GST_MESSAGE_EOS: event(p, 2, 0, 0); break;
    case GST_MESSAGE_ASYNC_DONE:
        if (g_atomic_int_compare_and_exchange(&p->preparing, 1, 0)) event(p, 1, 0, 0);
        else if (g_atomic_int_compare_and_exchange(&p->seeking, 1, 0)) event(p, 4, 0, 0);
        break;
    case GST_MESSAGE_BUFFERING: {
        gint percent; gst_message_parse_buffering(msg, &percent); event(p, 3, percent, 0); break;
    }
    default: break;
    }
    return G_SOURCE_CONTINUE;
}
static void source_setup(GstElement *pipeline, GstElement *source, Player *p)
{
    if (p->headers && g_object_class_find_property(G_OBJECT_GET_CLASS(source), "extra-headers"))
        g_object_set(source, "extra-headers", p->headers, NULL);
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "timeout"))
        g_object_set(source, "timeout", 30u, NULL);
}
JNIEXPORT jlong JNICALL Java_android_media_MediaPlayer_nativeCreate(JNIEnv *env, jobject owner,
        jstring path, jobjectArray keys, jobjectArray values, jint token)
{
    GError *error = NULL;
    if (!gst_init_check(NULL, NULL, &error)) { g_clear_error(&error); return 0; }
    const char *str = (*env)->GetStringUTFChars(env, path, NULL);
    char *uri = gst_uri_is_valid(str) ? g_strdup(str) : gst_filename_to_uri(str, &error);
    (*env)->ReleaseStringUTFChars(env, path, str);
    if (!uri) { g_clear_error(&error); return 0; }
    Player *p = g_new0(Player, 1);
    p->refs = 1; p->token = token;
    g_mutex_init(&p->frames); g_weak_ref_init(&p->widget, NULL);
    p->owner = (*env)->NewWeakGlobalRef(env, owner);
    jclass cls = (*env)->GetObjectClass(env, owner);
    p->post_event = (*env)->GetMethodID(env, cls, "postEvent", "(IIII)V");
    (*env)->DeleteLocalRef(env, cls);
    p->pipeline = gst_element_factory_make("playbin", NULL);
    p->sink = gst_element_factory_make("appsink", NULL);
    if (!p->pipeline || !p->sink) {
        if (p->pipeline) gst_object_unref(p->pipeline);
        if (p->sink) gst_object_unref(p->sink);
        g_free(uri); player_unref(p); return 0;
    }
    gst_object_ref_sink(p->pipeline);
    gst_object_ref_sink(p->sink);
    GstCaps *caps = gst_caps_new_simple("video/x-raw", "format", G_TYPE_STRING, "RGBA", NULL);
    g_object_set(p->sink, "caps", caps, "sync", TRUE, "max-buffers", 2u, "drop", TRUE, NULL);
    gst_caps_unref(caps);
    GstAppSinkCallbacks callbacks = { .new_preroll = preroll, .new_sample = sample };
    gst_app_sink_set_callbacks(GST_APP_SINK(p->sink), &callbacks, p, NULL);
    p->headers = gst_structure_new_empty("headers");
    for (int i = 0; i < (*env)->GetArrayLength(env, keys); ++i) {
        jstring key = (*env)->GetObjectArrayElement(env, keys, i), value = (*env)->GetObjectArrayElement(env, values, i);
        const char *k = (*env)->GetStringUTFChars(env, key, NULL), *v = (*env)->GetStringUTFChars(env, value, NULL);
        gst_structure_set(p->headers, k, G_TYPE_STRING, v, NULL);
        (*env)->ReleaseStringUTFChars(env, key, k); (*env)->ReleaseStringUTFChars(env, value, v);
        (*env)->DeleteLocalRef(env, key); (*env)->DeleteLocalRef(env, value);
    }
    g_signal_connect(p->pipeline, "source-setup", G_CALLBACK(source_setup), p);
    g_object_set(p->pipeline, "uri", uri, "video-sink", p->sink, NULL);
    g_free(uri);
    GstBus *bus = gst_element_get_bus(p->pipeline);
    p->bus_watch = gst_bus_add_watch_full(bus, G_PRIORITY_DEFAULT, bus_message, player_ref(p), player_unref);
    gst_object_unref(bus);
    return (jlong) (intptr_t) p;
}

typedef struct { Player *player; GtkWidget *widget; } Binding;
static gboolean bind_surface(gpointer data)
{
    Binding *b = data;
    if (!g_atomic_int_get(&b->player->closed)) {
        SurfaceViewWidget *view = b->widget ? surface_view_widget_from_widget(b->widget) : NULL;
        g_weak_ref_set(&b->player->widget, view);
    }
    g_clear_object(&b->widget);
    player_unref(b->player); g_free(b);
    return G_SOURCE_REMOVE;
}
JNIEXPORT void JNICALL Java_android_media_MediaPlayer_nativeSurface(JNIEnv *env, jclass cls, jlong ptr, jobject surface)
{
    Player *p = (void *) (intptr_t) ptr;
    jlong widget = 0;
    if (surface) {
        jclass sc = (*env)->GetObjectClass(env, surface);
        jfieldID field = (*env)->GetFieldID(env, sc, "widget", "J");
        widget = (*env)->GetLongField(env, surface, field);
        (*env)->DeleteLocalRef(env, sc);
        if (!widget) {
            jclass exception = (*env)->FindClass(env, "java/lang/UnsupportedOperationException");
            (*env)->ThrowNew(env, exception, "MediaPlayer currently requires a SurfaceView surface");
            (*env)->DeleteLocalRef(env, exception); return;
        }
    }
    Binding *b = g_new0(Binding, 1);
    b->player = player_ref(p); b->widget = widget ? g_object_ref((void *) (intptr_t) widget) : NULL;
    g_idle_add(bind_surface, b);
}
JNIEXPORT jboolean JNICALL Java_android_media_MediaPlayer_nativePrepare(JNIEnv *env, jclass cls, jlong ptr, jboolean wait)
{
    Player *p = (void *) (intptr_t) ptr;
    g_atomic_int_set(&p->preparing, 1);
    GstStateChangeReturn result = gst_element_set_state(p->pipeline, GST_STATE_PAUSED);
    if (result == GST_STATE_CHANGE_FAILURE) return FALSE;
    gboolean asynchronous = result == GST_STATE_CHANGE_ASYNC;
    if (wait) result = gst_element_get_state(p->pipeline, NULL, NULL, 30 * GST_SECOND);
    if (result == GST_STATE_CHANGE_SUCCESS || result == GST_STATE_CHANGE_NO_PREROLL) {
        // Async preparation has its own ASYNC_DONE message, including when the
        // caller waited synchronously. Do not let that message complete a later seek.
        if (!asynchronous && g_atomic_int_compare_and_exchange(&p->preparing, 1, 0)) event(p, 1, 0, 0);
        return TRUE;
    }
    return !wait && result == GST_STATE_CHANGE_ASYNC;
}
JNIEXPORT void JNICALL Java_android_media_MediaPlayer_nativeState(JNIEnv *env, jclass cls, jlong ptr, jint state)
{
    Player *p = (void *) (intptr_t) ptr;
    if (gst_element_set_state(p->pipeline, state == 1 ? GST_STATE_PLAYING : state == 2 ? GST_STATE_PAUSED : GST_STATE_READY) == GST_STATE_CHANGE_FAILURE)
        event(p, 100, -1004, 0);
}
JNIEXPORT jboolean JNICALL Java_android_media_MediaPlayer_nativeSeek(JNIEnv *env, jclass cls, jlong ptr, jlong ms, jint mode)
{
    Player *p = (void *) (intptr_t) ptr;
    if (ms > G_MAXINT64 / GST_MSECOND) return FALSE;
    GstSeekFlags flags = GST_SEEK_FLAG_FLUSH;
    if (mode == 3) flags |= GST_SEEK_FLAG_ACCURATE;
    else flags |= GST_SEEK_FLAG_KEY_UNIT | (mode == 0 ? GST_SEEK_FLAG_SNAP_BEFORE : mode == 1 ? GST_SEEK_FLAG_SNAP_AFTER : GST_SEEK_FLAG_SNAP_NEAREST);
    g_atomic_int_set(&p->seeking, 1);
    gboolean ok = gst_element_seek_simple(p->pipeline, GST_FORMAT_TIME, flags, ms * GST_MSECOND);
    if (!ok) g_atomic_int_set(&p->seeking, 0);
    return ok;
}
JNIEXPORT jint JNICALL Java_android_media_MediaPlayer_nativeTime(JNIEnv *env, jclass cls, jlong ptr, jboolean duration)
{
    Player *p = (void *) (intptr_t) ptr;
    gint64 ns = 0;
    gboolean ok = duration ? gst_element_query_duration(p->pipeline, GST_FORMAT_TIME, &ns) : gst_element_query_position(p->pipeline, GST_FORMAT_TIME, &ns);
    return ok && ns >= 0 ? MIN(ns / GST_MSECOND, G_MAXINT) : duration ? -1 : 0;
}
JNIEXPORT void JNICALL Java_android_media_MediaPlayer_nativeVolume(JNIEnv *env, jclass cls, jlong ptr, jfloat volume)
{
    Player *p = (void *) (intptr_t) ptr;
    g_object_set(p->pipeline, "volume", (double) volume, NULL);
}
JNIEXPORT void JNICALL Java_android_media_MediaPlayer_nativeRelease(JNIEnv *env, jclass cls, jlong ptr)
{
    Player *p = (void *) (intptr_t) ptr;
    g_atomic_int_set(&p->closed, 1);
    gst_element_set_state(p->pipeline, GST_STATE_NULL); // joins streaming threads
    if (p->bus_watch) g_source_remove(p->bus_watch);
    gst_object_unref(p->pipeline);
    gst_object_unref(p->sink);
    player_unref(p);
}
