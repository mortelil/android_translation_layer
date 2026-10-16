/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef BITMAP_SOURCE
#define BITMAP_SOURCE "../../src/api-impl-jni/graphics/android_graphics_Bitmap.c"
#endif
#include BITMAP_SOURCE
GThread *main_thread_id;
int main(void) {
    gtk_init(); main_thread_id = g_thread_self();
    GdkSurface *surface = gdk_surface_new_toplevel(gdk_display_get_default());
    GskRenderer *renderer = gsk_renderer_new_for_surface(surface);
    GtkSnapshot *composite = gtk_snapshot_new();
    GtkSnapshot *reference = gtk_snapshot_new();
    for (int i = 0; i < 64; i++) {
        GtkSnapshot *s = gtk_snapshot_new();
        GdkRGBA color = {i / 63.0, .25, .75, .25 + (i%4)*.25};
        gtk_snapshot_append_color(s, &color, &GRAPHENE_RECT_INIT(0, 0, 16, 16));
        PangoContext *context = pango_font_map_create_context(pango_cairo_font_map_get_default());
        PangoLayout *layout = pango_layout_new(context);
        pango_layout_set_text(layout, "+", -1);
        gtk_snapshot_append_layout(s, layout, &(GdkRGBA){0, 0, 0, 1});
        g_object_unref(layout); g_object_unref(context);
        GskRenderNode *original = gtk_snapshot_to_node(s);
        gtk_snapshot_save(reference);
        gtk_snapshot_translate(reference, &GRAPHENE_POINT_INIT((i%8)*16, (i/8)*16));
        gtk_snapshot_append_node(reference, original);
        gtk_snapshot_restore(reference);
        g_object_unref(s);
        s = gtk_snapshot_new();
        gtk_snapshot_append_node(s, original);
        gsk_render_node_unref(original);
        GdkTexture *texture = (GdkTexture *)Java_android_graphics_Bitmap_native_1create_1texture(
            NULL, NULL, (jlong)s, 16, 16, 64, GDK_MEMORY_R8G8B8A8);
        /* Do not download/synchronize between offscreen renders: that concealed
         * this defect in the original texture-lifetime test. */
        gtk_snapshot_append_texture(composite, texture, &GRAPHENE_RECT_INIT((i % 8)*16, (i/8)*16, 16, 16));
        g_object_unref(texture);
    }
    GskRenderNode *node = gtk_snapshot_free_to_node(composite);
    GdkTexture *result = gsk_renderer_render_texture(renderer, node, &GRAPHENE_RECT_INIT(0, 0, 128, 128));
    guchar pixels[128*128*4];
    GdkTextureDownloader *download = gdk_texture_downloader_new(result);
    gdk_texture_downloader_set_format(download, GDK_MEMORY_R8G8B8A8);
    gdk_texture_downloader_download_into(download, pixels, 128*4);
    GskRenderNode *reference_node = gtk_snapshot_free_to_node(reference);
    GdkTexture *expected = gsk_renderer_render_texture(renderer, reference_node, &GRAPHENE_RECT_INIT(0, 0, 128, 128));
    guchar expected_pixels[sizeof pixels];
    GdkTextureDownloader *reference_download = gdk_texture_downloader_new(expected);
    gdk_texture_downloader_set_format(reference_download, GDK_MEMORY_R8G8B8A8);
    gdk_texture_downloader_download_into(reference_download, expected_pixels, 128*4);
    int failures = 0;
    for (int i = 0; i < sizeof pixels; i++) {
        if (abs(pixels[i] - expected_pixels[i]) > 2) failures++;
    }
    gdk_texture_downloader_free(reference_download); g_object_unref(expected); gsk_render_node_unref(reference_node);
    gdk_texture_downloader_free(download); g_object_unref(result); gsk_render_node_unref(node);
    gsk_renderer_unrealize(renderer); g_object_unref(renderer); gdk_surface_destroy(surface); g_object_unref(surface);
    g_print("Bitmap renderer transfer: %d differing channels\n", failures);
    return failures ? 1 : 0;
}
