// SPDX-License-Identifier: GPL-3.0-only
/* Regression test for the layer outline clip of android.view.RenderNode.
 *
 * RenderNode.setClipToOutline(true) + setOutline() must clip the layer to the outline's *shape*
 * (AOSP RenderNodeDrawable::clipOutline(): rounded rect for MODE_ROUND_RECT, path for
 * MODE_PATH), in the layer's own coordinate space - AOSP concatenates the node matrix before
 * clipping. ATL used to clip to Outline.getRect() with a plain rectangular clip applied outside
 * the transform, which rendered Rhythm's 24dp icon buttons as hard-cornered squares and left the
 * clip stale whenever only the radius changed.
 *
 * Build/run with tests/render-node-outline-clip/run.sh (no display needed: the nodes are drawn
 * into a cairo image surface). Every shape assertion is numerical: filled area and per-row width
 * profile against the analytic stadium model, plus empty-corner checks.
 */
#include <gtk/gtk.h>
#include <math.h>
#include "../../src/api-impl-jni/generated_headers/android_view_RenderNode.h"

/* android.graphics.Outline modes */
#define MODE_ROUND_RECT 1
#define MODE_PATH 2

#define TEST_W 68.0f
#define TEST_H 80.0f
#define CANVAS_W 140
#define CANVAS_H 140
#define STADIUM_AREA 4448 /* 68 * 80 - (4 - pi) * 34^2 */

struct image {
	int w, h;
	unsigned char *alpha;
};

static GskRenderNode *solid_rect(void) {
	GtkSnapshot *snapshot = gtk_snapshot_new();
	gtk_snapshot_append_color(snapshot, &(GdkRGBA){1.f, 0.f, 0.f, 1.f}, &GRAPHENE_RECT_INIT(0, 0, TEST_W, TEST_H));
	return gtk_snapshot_free_to_node(snapshot);
}

static struct image render(GskRenderNode *node) {
	struct image img = {CANVAS_W, CANVAS_H, g_malloc0(CANVAS_W * CANVAS_H)};
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, CANVAS_W, CANVAS_H);
	cairo_t *cr = cairo_create(surface);
	gsk_render_node_draw(node, cr);
	cairo_destroy(cr);
	cairo_surface_flush(surface);
	unsigned char *data = cairo_image_surface_get_data(surface);
	int stride = cairo_image_surface_get_stride(surface);
	for (int y = 0; y < CANVAS_H; y++)
		for (int x = 0; x < CANVAS_W; x++)
			img.alpha[y * CANVAS_W + x] = data[y * stride + x * 4 + 3];
	cairo_surface_destroy(surface);
	return img;
}

static int is_filled(struct image *img, int x, int y) {
	return x >= 0 && y >= 0 && x < img->w && y < img->h && img->alpha[y * img->w + x] > 127;
}

static int filled_count(struct image *img) {
	int n = 0;
	for (int i = 0; i < img->w * img->h; i++)
		if (img->alpha[i] > 127)
			n++;
	return n;
}

static int row_width(struct image *img, int y) {
	int n = 0;
	for (int x = 0; x < img->w; x++)
		if (is_filled(img, x, y))
			n++;
	return n;
}

static void bounds(struct image *img, int *x0, int *y0, int *x1, int *y1) {
	*x0 = *y0 = G_MAXINT;
	*x1 = *y1 = -1;
	for (int y = 0; y < img->h; y++)
		for (int x = 0; x < img->w; x++)
			if (is_filled(img, x, y)) {
				*x0 = MIN(*x0, x); *y0 = MIN(*y0, y);
				*x1 = MAX(*x1, x); *y1 = MAX(*y1, y);
			}
}

/* Width of a rounded rect with corner radius r at depth d below its top edge (d = 0 is the row
 * the shape starts in), i.e. what SkRRect::MakeRectXY()/AOSP's clipRRect() produce. */
static double stadium_width(double w, double h, double r, double d) {
	d = MIN(d, h - d);
	if (d >= r)
		return w;
	return w - 2 * r + 2 * sqrt(r * r - (r - d) * (r - d));
}

static GskRenderNode *clip(GskRenderNode *child, jint mode, jfloat radius, jint fill_type, GskPath *path) {
	jlong node = Java_android_view_RenderNode_nativeClipOutline(NULL, NULL, (jlong)(intptr_t)child, mode,
			0, 0, TEST_W, TEST_H, radius, fill_type, (jlong)(intptr_t)path);
	return (GskRenderNode *)(intptr_t)node;
}

static void test_rect_clip(void) {
	GskRenderNode *child = solid_rect();
	GskRenderNode *node = clip(child, MODE_ROUND_RECT, 0, 0, NULL);
	g_assert_nonnull(node);
	/* radius 0 keeps the cheap rectangular clip */
	g_assert_cmpint(gsk_render_node_get_node_type(node), ==, GSK_CLIP_NODE);
	struct image img = render(node);
	g_assert_cmpint(filled_count(&img), ==, (int)(TEST_W * TEST_H));
	for (int y = 0; y < (int)TEST_H; y++)
		g_assert_cmpint(row_width(&img, y), ==, (int)TEST_W);
	g_free(img.alpha);
	gsk_render_node_unref(node);
	gsk_render_node_unref(child);
}

static void test_rounded_clip(void) {
	float radius = 34; /* min(68, 80) / 2: a full stadium */
	GskRenderNode *child = solid_rect();
	GskRenderNode *node = clip(child, MODE_ROUND_RECT, radius, 0, NULL);
	g_assert_nonnull(node);
	g_assert_cmpint(gsk_render_node_get_node_type(node), ==, GSK_ROUNDED_CLIP_NODE);
	struct image img = render(node);
	/* stadium area = w * h - (4 - pi) * r^2 */
	int area = filled_count(&img);
	g_assert_cmpint(abs(area - STADIUM_AREA), <, 70);
	g_assert_false(is_filled(&img, 0, 0));
	g_assert_true(is_filled(&img, 34, 40));
	/* The per-row width profile must be a circular-arc stadium of radius `radius`; allow the
	 * rasterizer's row grid to sit up to 3 rows off, then require a tight fit. */
	double best = G_MAXDOUBLE, shift_used = 0;
	for (int shift = -3; shift <= 3; shift++) {
		double worst = 0;
		for (int d = 3; d < (int)TEST_H - 3; d++)
			worst = MAX(worst, fabs(row_width(&img, d) - stadium_width(TEST_W, TEST_H, radius, d + shift)));
		if (worst < best) {
			best = worst;
			shift_used = shift;
		}
	}
	g_print("render-node-outline-clip: stadium profile max deviation %.2f px at row shift %g\n", best, shift_used);
	g_assert_cmpfloat(best, <, 3.0);
	g_free(img.alpha);
	gsk_render_node_unref(node);

	/* a radius larger than half the bounds is scaled down like SkRRect::MakeRectXY() */
	child = solid_rect();
	node = clip(child, MODE_ROUND_RECT, 400, 0, NULL);
	struct image big = render(node);
	g_assert_cmpint(filled_count(&big), ==, area);
	g_free(big.alpha);
	gsk_render_node_unref(node);
	gsk_render_node_unref(child);
}

static GskPath *circle_path(void) {
	GskPathBuilder *builder = gsk_path_builder_new();
	gsk_path_builder_add_circle(builder, &GRAPHENE_POINT_INIT(TEST_W / 2, TEST_H / 2), 30);
	return gsk_path_builder_free_to_path(builder);
}

static void test_path_clip(void) {
	for (int fill_type = 0; fill_type <= 2; fill_type += 2) {
		GskPath *path = circle_path();
		GskRenderNode *child = solid_rect();
		GskRenderNode *node = clip(child, MODE_PATH, 0, fill_type, path);
		g_assert_nonnull(node);
		struct image img = render(node);
		int inside = is_filled(&img, (int)TEST_W / 2, (int)TEST_H / 2);
		int corner = is_filled(&img, 0, 0);
		if (fill_type >= 2) /* inverse fill type: everything outside the circle */
			g_assert_true(!inside && corner);
		else
			g_assert_true(inside && !corner);
		/* circle area, or its complement inside the 68x80 rect */
		int expected = (int)(M_PI * 30 * 30);
		if (fill_type >= 2)
			expected = (int)(TEST_W * TEST_H) - expected;
		g_assert_cmpint(abs(filled_count(&img) - expected), <, 120);
		g_free(img.alpha);
		gsk_render_node_unref(node);
		gsk_render_node_unref(child);
		gsk_path_unref(path);
	}
}

/* Nothing to clip with: MODE_PATH without a path, and an outline that is not a shape at all. */
static void test_no_clip(void) {
	GskRenderNode *child = solid_rect();
	g_assert_null(clip(child, MODE_PATH, 0, 0, NULL));
	g_assert_null(clip(child, 0 /* MODE_EMPTY */, 0, 0, NULL));
	gsk_render_node_unref(child);
}

/* The clip belongs *inside* the transform: scaling the layer must scale the outline with it.
 * Clipping outside the transform (the old behaviour) would leave the scaled content unclipped
 * and therefore square. */
static void test_clip_inside_transform(void) {
	float radius = 34;
	GskRenderNode *child = solid_rect();
	GskRenderNode *clipped = clip(child, MODE_ROUND_RECT, radius, 0, NULL);
	GskTransform *transform = gsk_transform_scale(NULL, 0.5f, 0.5f);
	transform = gsk_transform_translate(transform, &GRAPHENE_POINT_INIT(20, 10));
	GskRenderNode *node = gsk_transform_node_new(clipped, transform);
	gsk_transform_unref(transform);
	gsk_render_node_unref(clipped);
	struct image img = render(node);
	int x0, y0, x1, y1;
	bounds(&img, &x0, &y0, &x1, &y1);
	g_assert_cmpint(x1 - x0 + 1, ==, 34);
	g_assert_cmpint(y1 - y0 + 1, ==, 40);
	/* a quarter of the stadium area, with rounded (empty) corners */
	g_assert_cmpint(abs(filled_count(&img) - STADIUM_AREA / 4), <, 40);
	g_assert_false(is_filled(&img, x0, y0));
	g_assert_false(is_filled(&img, x1, y1));
	g_assert_true(is_filled(&img, x0 + 17, y0 + 20));
	g_free(img.alpha);
	gsk_render_node_unref(node);
	gsk_render_node_unref(child);

	/* rotated by 90 degrees the outline rotates with the content */
	child = solid_rect();
	clipped = clip(child, MODE_ROUND_RECT, radius, 0, NULL);
	/* Translate in device space after rotating, keeping all ink on the canvas. */
	transform = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(80, 0));
	transform = gsk_transform_rotate(transform, 90);
	node = gsk_transform_node_new(clipped, transform);
	gsk_transform_unref(transform);
	gsk_render_node_unref(clipped);
	struct image rot = render(node);
	bounds(&rot, &x0, &y0, &x1, &y1);
	g_assert_cmpint(x1 - x0 + 1, ==, 80);
	g_assert_cmpint(y1 - y0 + 1, ==, 68);
	g_assert_cmpint(abs(filled_count(&rot) - STADIUM_AREA), <, 60);
	g_free(rot.alpha);
	gsk_render_node_unref(node);
	gsk_render_node_unref(child);
}

int main(void) {
	test_rect_clip();
	test_rounded_clip();
	test_path_clip();
	test_no_clip();
	test_clip_inside_transform();
	g_print("render-node-outline-clip: all checks passed\n");
	return 0;
}
