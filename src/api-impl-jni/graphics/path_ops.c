// SPDX-License-Identifier: GPL-3.0-only
#include "path_ops.h"
#include "../../../third_party/skiasharp/include/c/sk_path.h"
#include <math.h>

/* Preserve curves and coordinates. In particular, never choose a flattening or
 * snapping tolerance from the combined extent of unrelated contours. Skia's
 * PathOps operates on the same float curves and fill rules as Android. */
static gboolean to_skia(GskPathOperation op, const graphene_point_t *p,
                       gsize count, float weight, gpointer data)
{
	sk_path_t *path = data;
	for (gsize i = 0; i < count; i++)
		if (!isfinite(p[i].x) || !isfinite(p[i].y))
			return FALSE;
	switch (op) {
	case GSK_PATH_MOVE: sk_path_move_to(path, p[0].x, p[0].y); break;
	case GSK_PATH_LINE: sk_path_line_to(path, p[1].x, p[1].y); break;
	case GSK_PATH_QUAD: sk_path_quad_to(path, p[1].x, p[1].y, p[2].x, p[2].y); break;
	case GSK_PATH_CUBIC: sk_path_cubic_to(path, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y); break;
	case GSK_PATH_CONIC:
		if (!isfinite(weight) || weight <= 0) return FALSE;
		sk_path_conic_to(path, p[1].x, p[1].y, p[2].x, p[2].y, weight); break;
	case GSK_PATH_CLOSE: sk_path_close(path); break;
	default: return FALSE;
	}
	return TRUE;
}

GskPath *atl_path_op(GskPath *a, GskPath *b, int op, int fill_a, int fill_b, int *out_fill_type)
{
	*out_fill_type = -1;
	if (op < 0 || op > 4 || fill_a < 0 || fill_a > 3 || fill_b < 0 || fill_b > 3)
		return NULL;
	sk_path_t *sa = sk_path_new(), *sb = sk_path_new(), *result = sk_path_new();
	GskPath *path = NULL;
	if (!sa || !sb || !result) goto done;
	sk_path_set_filltype(sa, fill_a);
	sk_path_set_filltype(sb, fill_b);
	GskPathForeachFlags flags = GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC;
	if ((a && !gsk_path_foreach(a, flags, to_skia, sa)) ||
	    (b && !gsk_path_foreach(b, flags, to_skia, sb)) ||
	    !sk_pathop_op(sa, sb, op, result)) goto done;

	GskPathBuilder *builder = gsk_path_builder_new();
	sk_path_iterator_t *iter = sk_path_create_iter(result, 0);
	if (!iter) { gsk_path_builder_unref(builder); goto done; }
	sk_point_t p[4];
	sk_path_verb_t verb;
	while ((verb = sk_path_iter_next(iter, p)) != DONE_SK_PATH_VERB) {
		switch (verb) {
		case MOVE_SK_PATH_VERB: gsk_path_builder_move_to(builder, p[0].x, p[0].y); break;
		case LINE_SK_PATH_VERB: gsk_path_builder_line_to(builder, p[1].x, p[1].y); break;
		case QUAD_SK_PATH_VERB: gsk_path_builder_quad_to(builder, p[1].x, p[1].y, p[2].x, p[2].y); break;
		case CONIC_SK_PATH_VERB: gsk_path_builder_conic_to(builder, p[1].x, p[1].y, p[2].x, p[2].y, sk_path_iter_conic_weight(iter)); break;
		case CUBIC_SK_PATH_VERB: gsk_path_builder_cubic_to(builder, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y); break;
		case CLOSE_SK_PATH_VERB: gsk_path_builder_close(builder); break;
		default: g_assert_not_reached();
		}
	}
	sk_path_iter_destroy(iter);
	path = gsk_path_builder_free_to_path(builder);
	*out_fill_type = sk_path_get_filltype(result);
done:
	if (sa) sk_path_delete(sa);
	if (sb) sk_path_delete(sb);
	if (result) sk_path_delete(result);
	return path;
}
