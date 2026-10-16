/* SPDX-License-Identifier: GPL-3.0-only */
#include "../../src/api-impl-jni/graphics/android_graphics_drawable_DrawableContainer.c"
#include <assert.h>
static void gone(gpointer data, GObject *object) { *(gboolean *)data = TRUE; }
static void invalidated(GdkPaintable *object, gpointer data) { (*(int *)data)++; }
int main(void) {
    jlong ptr = Java_android_graphics_drawable_DrawableContainer_native_1constructor(NULL, NULL);
    GdkPaintable *parent = GDK_PAINTABLE(_PTR(ptr));
    GdkPaintable *child = gdk_paintable_new_empty(20, 30);
    gboolean freed = FALSE;
    int contents = 0, sizes = 0;
    g_object_weak_ref(G_OBJECT(child), gone, &freed);
    Java_android_graphics_drawable_DrawableContainer_native_1selectChild(NULL, NULL, ptr, _INTPTR(child));
    g_object_unref(child);
    assert(!freed); /* The container, not the caller, owns the selected child. */
    assert(gdk_paintable_get_intrinsic_width(parent) == 20);
    g_signal_connect(parent, "invalidate-contents", G_CALLBACK(invalidated), &contents);
    g_signal_connect(parent, "invalidate-size", G_CALLBACK(invalidated), &sizes);
    g_signal_emit_by_name(child, "invalidate-contents");
    g_signal_emit_by_name(child, "invalidate-size");
    assert(contents == 1 && sizes == 1);
    Java_android_graphics_drawable_DrawableContainer_native_1selectChild(NULL, NULL, ptr, 0);
    assert(freed);
    assert(gdk_paintable_get_intrinsic_width(parent) == 0);
    child = gdk_paintable_new_empty(4, 5);
    freed = FALSE;
    g_object_weak_ref(G_OBJECT(child), gone, &freed);
    Java_android_graphics_drawable_DrawableContainer_native_1selectChild(NULL, NULL, ptr, _INTPTR(child));
    GdkPaintable *replacement = gdk_paintable_new_empty(6, 7);
    Java_android_graphics_drawable_DrawableContainer_native_1selectChild(NULL, NULL, ptr, _INTPTR(replacement));
    int before = contents;
    g_signal_emit_by_name(child, "invalidate-contents");
    assert(contents == before); /* Retired children cannot invalidate the new selection. */
    g_object_unref(child);
    assert(freed);
    freed = FALSE;
    g_object_weak_ref(G_OBJECT(replacement), gone, &freed);
    g_object_unref(replacement);
    g_object_unref(parent);
    assert(freed);
    g_print("PASS: selected paintable ownership, invalidation forwarding and disposal\n");
}
