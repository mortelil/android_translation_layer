/* SPDX-License-Identifier: GPL-3.0-only */
#include <gtk/gtk.h>
#include "../defines.h"
#include "../generated_headers/android_atl_RecordingCanvas.h"

JNIEXPORT jlong JNICALL Java_android_atl_RecordingCanvas_nativeCreateSnapshot(JNIEnv *env, jclass cls) {
    return _INTPTR(gtk_snapshot_new());
}
JNIEXPORT jlong JNICALL Java_android_atl_RecordingCanvas_nativeFinishSnapshot(JNIEnv *env, jclass cls, jlong ptr) {
    GskRenderNode *node = gtk_snapshot_free_to_node(_PTR(ptr));
    return _INTPTR(node);
}
JNIEXPORT void JNICALL Java_android_atl_RecordingCanvas_nativeDropSnapshot(JNIEnv *env, jclass cls, jlong ptr) {
    g_object_unref(_PTR(ptr));
}
JNIEXPORT void JNICALL Java_android_atl_RecordingCanvas_nativeUnrefNode(JNIEnv *env, jclass cls, jlong ptr) {
    gsk_render_node_unref(_PTR(ptr));
}
JNIEXPORT jlong JNICALL Java_android_atl_RecordingCanvas_nativeRefPath(JNIEnv *env, jclass cls, jlong ptr) {
    return _INTPTR(gsk_path_ref(_PTR(ptr)));
}
JNIEXPORT void JNICALL Java_android_atl_RecordingCanvas_nativeUnrefPath(JNIEnv *env, jclass cls, jlong ptr) {
    gsk_path_unref(_PTR(ptr));
}
JNIEXPORT jlong JNICALL Java_android_atl_RecordingCanvas_nativeCopyMatrix(JNIEnv *env, jclass cls, jlong ptr) {
    return _INTPTR(g_memdup2(_PTR(ptr), sizeof(graphene_matrix_t)));
}
JNIEXPORT void JNICALL Java_android_atl_RecordingCanvas_nativeFreeMatrix(JNIEnv *env, jclass cls, jlong ptr) {
    g_free(_PTR(ptr));
}
