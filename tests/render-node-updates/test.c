// SPDX-License-Identifier: GPL-3.0-only
#include "../../src/api-impl-jni/graphics/android_view_RenderNode.c"
#include <assert.h>
#include <string.h>

static GskRenderNode *wrap(GskRenderNode *child, int kind) {
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, 20, 20);
	GskRoundedRect rounded;
	gsk_rounded_rect_init_from_rect(&rounded, &bounds, 5);
	graphene_matrix_t matrix; graphene_matrix_init_identity(&matrix);
	graphene_vec4_t offset; graphene_vec4_init(&offset, .1, 0, 0, 0);
	if (kind == 0) return gsk_opacity_node_new(child, .5);
	if (kind == 1) return gsk_rounded_clip_node_new(child, &rounded);
	if (kind == 2) return gsk_mask_node_new(child, child, GSK_MASK_MODE_ALPHA);
	if (kind == 3) return gsk_color_matrix_node_new(child, &matrix, &offset);
	GskPathBuilder *builder = gsk_path_builder_new();
	gsk_path_builder_add_rect(builder, &GRAPHENE_RECT_INIT(3, 3, 14, 14));
	GskPath *path = gsk_path_builder_free_to_path(builder);
	GskStroke *stroke = gsk_stroke_new(3);
	GskRenderNode *result = kind == 4 ? gsk_fill_node_new(child, path, GSK_FILL_RULE_EVEN_ODD)
	                                 : gsk_stroke_node_new(child, path, stroke);
	gsk_stroke_free(stroke); gsk_path_unref(path);
	return result;
}
static void pixels(GskRenderNode *node, unsigned char *output) {
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, 20, 20);
	cairo_t *cr = cairo_create(surface); gsk_render_node_draw(node, cr); cairo_destroy(cr);
	cairo_surface_flush(surface);
	for (int y = 0; y < 20; y++) memcpy(output + y * 80, cairo_image_surface_get_data(surface) + y * cairo_image_surface_get_stride(surface), 80);
	cairo_surface_destroy(surface);
}
int main(void) {
	GskRenderNode *old = gsk_color_node_new(&(GdkRGBA){1, 0, 0, 1}, &GRAPHENE_RECT_INIT(0, 0, 20, 20));
	GskRenderNode *replacement = gsk_color_node_new(&(GdkRGBA){0, 0, 1, .75}, &GRAPHENE_RECT_INIT(0, 0, 20, 20));
	GskRenderNode *unrelated = gsk_color_node_new(&(GdkRGBA){0, 1, 0, 1}, &GRAPHENE_RECT_INIT(0, 0, 20, 20));
	for (int kind = 0; kind < 6; kind++) {
		GskRenderNode *parent = wrap(old, kind), *expected = wrap(replacement, kind);
		GskRenderNode *untouched = patch_node(parent, unrelated, replacement);
		assert(untouched == parent); gsk_render_node_unref(untouched);
		GskRenderNode *actual = patch_node(parent, old, replacement);
		assert(actual != parent);
		unsigned char a[1600], b[1600]; pixels(actual, a); pixels(expected, b);
		assert(memcmp(a, b, sizeof a) == 0);
		gsk_render_node_unref(parent); gsk_render_node_unref(actual); gsk_render_node_unref(expected);
	}
	gsk_render_node_unref(old); gsk_render_node_unref(replacement); gsk_render_node_unref(unrelated);
	g_print("PASS: updated children under six effects, mask branches, pixels and unchanged identity\n");
}
