/* SPDX-License-Identifier: GPL-3.0-only */
/* Focused native semantics check for the ATL PathMeasure reimplementation.
 *
 * It answers the questions the new android_graphics_PathMeasure.c depends on:
 *  - does gsk_path_measure_new() ever return NULL, and what does it measure for a
 *    degenerate (single move) or empty path?
 *  - what does GskPathMeasure measure for a contour that was closed with
 *    gsk_path_builder_close() (does GSK add the closing chord itself)?
 *  - does gsk_path_measure_get_point() clamp an out-of-range distance or fail?
 *  - what is gsk_path_point_get_tangent(...GSK_PATH_TO_END) at the very end of an
 *    open contour (Skia returns the last segment direction there)?
 *  - does the contour-rebuild collector used by the fix reproduce per-contour
 *    lengths for a multi-contour path, and does it skip degenerate contours?
 *
 * Build/run (inside the atl_dev container):
 *   cc $(pkg-config --cflags gtk4) native-semantics.c -o /tmp/ns $(pkg-config --libs gtk4) -lm
 *   /tmp/ns
 */
#include <gsk/gsk.h>
#include <math.h>
#include <stdio.h>

static GskPath *path_of_ops(const char *def)
{
	/* Tiny path builder: 'm x y', 'l x y', 'c' (close). */
	GskPathBuilder *b = gsk_path_builder_new();
	const char *p = def;
	while (*p) {
		while (*p == ' ') p++;
		if (!*p) break;
		char op = *p++;
		if (op == 'c') { gsk_path_builder_close(b); continue; }
		float x = strtof(p, (char **)&p);
		float y = strtof(p, (char **)&p);
		if (op == 'm') gsk_path_builder_move_to(b, x, y);
		else gsk_path_builder_line_to(b, x, y);
	}
	return gsk_path_builder_free_to_path(b);
}

static void measure_report(const char *label, GskPath *path)
{
	GskPathMeasure *m = gsk_path_measure_new(path);
	printf("%s: measure=%p", label, (void *) m);
	if (!m) { printf(" (gsk_path_measure_new returned NULL)\n"); return; }
	float len = gsk_path_measure_get_length(m);
	printf(" length=%.6f", len);
	/* position + tangent at the end (and just inside the end) */
	for (int k = 0; k < 2; k++) {
		float d = k == 0 ? len : len - 0.001f;
		GskPathPoint pt;
		gboolean ok = gsk_path_measure_get_point(m, d, &pt);
		if (!ok) { printf(" | d=%.3f get_point=FALSE", d); continue; }
		graphene_point_t pos;
		gsk_path_point_get_position(&pt, path, &pos);
		graphene_vec2_t tv;
		gsk_path_point_get_tangent(&pt, path, GSK_PATH_TO_END, &tv);
		printf(" | d=%.3f pos=(%.4f,%.4f) tan=(%.4f,%.4f)", d, pos.x, pos.y,
		       graphene_vec2_get_x(&tv), graphene_vec2_get_y(&tv));
	}
	/* out-of-range pinning probe */
	GskPathPoint pt;
	gboolean ok_neg = gsk_path_measure_get_point(m, -5.0f, &pt);
	float neg_x = 0, neg_y = 0;
	if (ok_neg) { graphene_point_t pos; gsk_path_point_get_position(&pt, path, &pos); neg_x = pos.x; neg_y = pos.y; }
	gboolean ok_big = gsk_path_measure_get_point(m, len + 1000.0f, &pt);
	float big_x = 0, big_y = 0;
	if (ok_big) { graphene_point_t pos; gsk_path_point_get_position(&pt, path, &pos); big_x = pos.x; big_y = pos.y; }
	printf(" | d<0 ok=%d pos=(%.4f,%.4f) | d>len ok=%d pos=(%.4f,%.4f)",
	       ok_neg, neg_x, neg_y, ok_big, big_x, big_y);
	printf("\n");
	gsk_path_measure_unref(m);
}

/* The collector copied from the fix's native file. */
struct collect {
	GPtrArray *builders;
	GskPathBuilder *cur;
	graphene_point_t first, last;
	gboolean closed;
	gboolean force_closed;
};

static void collect_flush(struct collect *c)
{
	if (!c->cur) return;
	if ((c->closed || c->force_closed) && !graphene_point_equal(&c->last, &c->first))
		gsk_path_builder_line_to(c->cur, c->first.x, c->first.y);
	g_ptr_array_add(c->builders, c->cur);
	c->cur = NULL;
}

static gboolean collect_cb(GskPathOperation op, const graphene_point_t *pts, gsize n, float w, gpointer ud)
{
	struct collect *c = ud;
	switch (op) {
	case GSK_PATH_MOVE:
		collect_flush(c);
		c->cur = gsk_path_builder_new();
		gsk_path_builder_move_to(c->cur, pts[0].x, pts[0].y);
		c->first = pts[0];
		c->last = pts[0];
		c->closed = FALSE;
		break;
	case GSK_PATH_CLOSE:
		c->closed = TRUE;
		break;
	case GSK_PATH_LINE:
		gsk_path_builder_line_to(c->cur, pts[1].x, pts[1].y);
		c->last = pts[1];
		break;
	case GSK_PATH_QUAD:
		gsk_path_builder_quad_to(c->cur, pts[1].x, pts[1].y, pts[2].x, pts[2].y);
		c->last = pts[2];
		break;
	case GSK_PATH_CUBIC:
		gsk_path_builder_cubic_to(c->cur, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y);
		c->last = pts[3];
		break;
	case GSK_PATH_CONIC:
		gsk_path_builder_conic_to(c->cur, pts[1].x, pts[1].y, pts[2].x, pts[2].y, w);
		c->last = pts[2];
		break;
	}
	return TRUE;
}

static void contour_report(const char *label, GskPath *path, gboolean force_closed)
{
	GPtrArray *builders = g_ptr_array_new();
	struct collect c = { .builders = builders, .force_closed = force_closed };
	gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC,
	                 collect_cb, &c);
	collect_flush(&c);
	printf("%s (force_closed=%d): %u contour(s):", label, force_closed, builders->len);
	for (guint i = 0; i < builders->len; i++) {
		GskPath *cp = gsk_path_builder_free_to_path(g_ptr_array_index(builders, i));
		GskPathMeasure *m = gsk_path_measure_new(cp);
		float len = m ? gsk_path_measure_get_length(m) : -1;
		printf(" [%u]=%.6f%s", i, len, (!m || len == 0) ? "(skipped)" : "");
		/* end tangents: TO_END (outgoing) vs FROM_START (incoming, path direction) */
		for (int k = 0; k < 2; k++) {
			GskPathPoint pt;
			graphene_point_t pos;
			graphene_vec2_t te, fs;
			if (!m || !gsk_path_measure_get_point(m, len, &pt)) continue;
			gsk_path_point_get_position(&pt, cp, &pos);
			gsk_path_point_get_tangent(&pt, cp, k == 0 ? GSK_PATH_TO_END : GSK_PATH_FROM_START, k == 0 ? &te : &fs);
			if (k == 0)
				printf(" end-pos=(%.4f,%.4f) to_end=(%.4f,%.4f)", pos.x, pos.y,
				       graphene_vec2_get_x(&te), graphene_vec2_get_y(&te));
			else
				printf(" from_start=(%.4f,%.4f)", graphene_vec2_get_x(&fs), graphene_vec2_get_y(&fs));
		}
		if (m) gsk_path_measure_unref(m);
		gsk_path_unref(cp);
	}
	printf("\n");
	g_ptr_array_unref(builders);
}

int main(void)
{
	GskPath *two = path_of_ops("m 0 0 l 3 0 l 3 4  m 10 0 l 10 6");
	GskPath *two_degen = path_of_ops("m 0 0 l 3 0 l 3 4  m 50 50  m 10 0 l 10 6");
	GskPath *tri = path_of_ops("m 0 0 l 3 0 l 3 4");
	GskPath *tri_closed = path_of_ops("m 0 0 l 3 0 l 3 4 c");
	GskPath *point_only = path_of_ops("m 7 7");
	GskPath *empty = gsk_path_builder_free_to_path(gsk_path_builder_new());

	printf("== raw gsk_path_measure (pre-fix behaviour: whole path) ==\n");
	measure_report("two-contour", two);
	measure_report("triangle open", tri);
	measure_report("triangle close()", tri_closed);
	measure_report("move-only", point_only);
	measure_report("empty", empty);

	printf("== rebuilt per-contour (new behaviour) ==\n");
	contour_report("two-contour", two, FALSE);
	contour_report("two-contour+degenerate", two_degen, FALSE);
	contour_report("triangle open", tri, FALSE);
	contour_report("triangle open", tri, TRUE);
	contour_report("triangle close()", tri_closed, FALSE);
	contour_report("move-only", point_only, FALSE);
	contour_report("empty", empty, FALSE);

	gsk_path_unref(two);
	gsk_path_unref(two_degen);
	gsk_path_unref(tri);
	gsk_path_unref(tri_closed);
	gsk_path_unref(point_only);
	gsk_path_unref(empty);
	return 0;
}
