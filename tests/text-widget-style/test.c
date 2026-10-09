// SPDX-License-Identifier: GPL-3.0-only
#include <gtk/gtk.h>
#include <stdint.h>
#include "../../src/api-impl-jni/generated_headers/android_widget_TextView.h"

static void settle(void) {
	for (int i = 0; i < 30; i++) {
		while (g_main_context_iteration(NULL, FALSE)) {}
		g_usleep(10000);
	}
}
static int colored_pixels(GtkWidget *window, GtkWidget *text, int mode) {
	settle();
	int width = gtk_widget_get_width(text), height = gtk_widget_get_height(text);
	GdkPaintable *paintable = gtk_widget_paintable_new(text);
	GtkSnapshot *snapshot = gtk_snapshot_new();
	gdk_paintable_snapshot(paintable, GDK_SNAPSHOT(snapshot), width, height);
	GskRenderNode *node = gtk_snapshot_free_to_node(snapshot);
	g_assert_nonnull(node);
	graphene_rect_t rect = GRAPHENE_RECT_INIT(0, 0, width, height);
	GdkTexture *texture = gsk_renderer_render_texture(gtk_native_get_renderer(GTK_NATIVE(window)), node, &rect);
	char *path = g_strdup_printf("build/text-widget-style/mode-%d.png", mode);
	gdk_texture_save_to_png(texture, path);
	g_free(path);
	guchar *pixels = g_malloc(width * height * 4);
	gdk_texture_download(texture, pixels, width * 4);
	int count = 0;
	for (int i = 0; i < width * height; i++) {
		int b = pixels[i*4], g = pixels[i*4+1], r = pixels[i*4+2];
		if (mode == 0 ? r > 180 && g < 80 && b < 80 :
		    mode == 1 ? g > 140 && r < 80 && b < 80 : r > 100 && b > 100 && g < 60) count++;
	}
	g_free(pixels); g_object_unref(texture); gsk_render_node_unref(node); g_object_unref(paintable);
	return count;
}
int main(void) {
	gtk_init();
	GtkWidget *window = gtk_window_new(), *text = gtk_text_new();
	gtk_window_set_default_size(GTK_WINDOW(window), 400, 100);
	gtk_window_set_child(GTK_WINDOW(window), text);
	gtk_window_present(GTK_WINDOW(window));
	jlong ptr = (jlong)(intptr_t)text;
	gtk_text_set_input_hints(GTK_TEXT(text), GTK_INPUT_HINT_NO_SPELLCHECK);
	Java_android_widget_TextView_native_1setShowSoftInputOnFocus(NULL, NULL, ptr, FALSE);
	g_assert_true(gtk_text_get_input_hints(GTK_TEXT(text)) & GTK_INPUT_HINT_INHIBIT_OSK);
	Java_android_widget_TextView_native_1setShowSoftInputOnFocus(NULL, NULL, ptr, TRUE);
	g_assert_false(gtk_text_get_input_hints(GTK_TEXT(text)) & GTK_INPUT_HINT_INHIBIT_OSK);
	g_assert_true(gtk_text_get_input_hints(GTK_TEXT(text)) & GTK_INPUT_HINT_NO_SPELLCHECK);
	gtk_text_set_placeholder_text(GTK_TEXT(text), "Hint MMMM");
	Java_android_widget_TextView_native_1setHintTextColor(NULL, NULL, ptr, 0xff00ff00);
	g_assert_cmpint(colored_pixels(window, text, 1), >, 20);
	gtk_editable_set_text(GTK_EDITABLE(text), "Selected MMMM");
	gtk_editable_select_region(GTK_EDITABLE(text), 0, -1);
	Java_android_widget_TextView_native_1setHighlightColor(NULL, NULL, ptr, 0xffff0000);
	g_assert_cmpint(colored_pixels(window, text, 0), >, 200);
	gtk_editable_select_region(GTK_EDITABLE(text), 0, 0);
	Java_android_widget_TextView_native_1setShadowLayer(NULL, NULL, ptr, 1, 35, 12, 0xffff00ff);
	g_assert_cmpint(colored_pixels(window, text, 2), >, 10);
	Java_android_widget_TextView_native_1setShadowLayer(NULL, NULL, ptr, 0, 35, 12, 0xffff00ff);
	g_assert_cmpint(colored_pixels(window, text, 2), ==, 0);
	gtk_window_destroy(GTK_WINDOW(window));
	g_print("PASS: real GTK hint/selection/shadow pixels, shadow removal and preserved input-method hints\n");
	return 0;
}
