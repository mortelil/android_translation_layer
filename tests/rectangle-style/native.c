/* SPDX-License-Identifier: GPL-3.0-only */
#include "../../src/api-impl-jni/graphics/android_atl_GskCanvas.c"
#include <assert.h>
#include <stdint.h>

static uint32_t pixel(cairo_surface_t *surface, int x, int y)
{
	cairo_surface_flush(surface);
	return *(uint32_t *)(cairo_image_surface_get_data(surface) + y * cairo_image_surface_get_stride(surface) + x * 4);
}

static void check(gboolean fill, gboolean stroke, gboolean rounded)
{
	struct AndroidPaint paint = { .color = {1, 0, 0, .5}, .is_fill = fill, .is_stroke = stroke };
	paint.gsk_stroke = gsk_stroke_new(4);
	GtkSnapshot *snapshot = gtk_snapshot_new();
	if (rounded)
		Java_android_atl_GskCanvas_native_1drawRoundRect(NULL, NULL, _INTPTR(snapshot), 10, 10, 30, 30, 5, 5, _INTPTR(&paint));
	else
		Java_android_atl_GskCanvas_native_1drawRect(NULL, NULL, _INTPTR(snapshot), 10, 10, 30, 30, _INTPTR(&paint), NULL, FALSE);
	gtk_snapshot_append_color(snapshot, &(GdkRGBA){0, 1, 0, 1}, &GRAPHENE_RECT_INIT(1, 1, 3, 3));
	GskRenderNode *node = gtk_snapshot_free_to_node(snapshot);
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, 40, 40);
	cairo_t *cr = cairo_create(surface);
	gsk_render_node_draw(node, cr);
	/* The centre is empty for STROKE. The stroke straddles the boundary.
	 * Alpha must not be applied twice where fill and stroke overlap. */
	assert((pixel(surface, 20, 20) >> 24) == (fill ? 128 : 0));
	assert((pixel(surface, 9, 20) >> 24) == (stroke ? 128 : 0));
	assert((pixel(surface, 10, 20) >> 24) == 128);
	assert(pixel(surface, 5, 20) == 0);
	assert(pixel(surface, 2, 2) == 0xff00ff00); /* No leaked shape mask. */
	cairo_destroy(cr);
	cairo_surface_destroy(surface);
	gsk_render_node_unref(node);
	gsk_stroke_free(paint.gsk_stroke);
}

int main(void)
{
	for (int rounded = 0; rounded < 2; rounded++) {
		check(TRUE, FALSE, rounded);
		check(FALSE, TRUE, rounded);
		check(TRUE, TRUE, rounded);
	}
	g_print("PASS: rectangle fill/stroke, centred width and single alpha application\n");
}
