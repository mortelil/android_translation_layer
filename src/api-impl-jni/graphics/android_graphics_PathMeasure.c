/* SPDX-License-Identifier: GPL-3.0-only */
/* android.graphics.PathMeasure on top of GTK's GskPathMeasure.
 *
 * Native counterpart of src/api-impl/android/graphics/PathMeasure.java. The Java
 * side keeps the measured Path alive and this file keeps its own GskPath
 * reference, so the geometry a measure points at cannot be freed underneath it
 * (the previous implementation stored the Path's raw GskPath pointer only, which
 * went stale as soon as the Java Path was collected or reset()).
 *
 * Android's PathMeasure measures ONE contour at a time and nextContour() advances
 * to the following one. GskPathMeasure instead measures a whole GskPath, so on
 * setPath() every contour of the path is rebuilt into its own GskPath (a contour
 * starts at GSK_PATH_MOVE and ends at the next one or at GSK_PATH_CLOSE) and each
 * gets its own GskPathMeasure. Empty contours - a lone move, or a contour whose
 * segments all collapse to a point - are dropped, like Skia's
 * SkContourMeasureIter::buildSegments() does when it produces no segment.
 *
 * forceClosed (and an explicit close()) appends the contour's closing chord, i.e.
 * the same line Skia's buildSegments() appends for haveSeenClose, so an open
 * contour is measured as if it ended where it started.
 *
 * Semantics cross-checked against AOSP android-17.0.0_r1:
 *   frameworks/base/graphics/java/android/graphics/PathMeasure.java
 *   skia/src/core/SkContourMeasure.cpp (buildSegments, getPosTan)
 *   skia/src/core/SkPathMeasure.cpp (setPath, nextContour, getLength)
 * GSK behaviour assumed above was measured with tests/pathmeasure-probe/
 * native-semantics.c (GTK 4.24.1): GskPathMeasure never returns NULL, a rebuilt
 * contour with the closing chord measures like Skia's closed contour, and
 * gsk_path_measure_get_point() pins an out-of-range distance. */

#include <gsk/gsk.h>
#include <math.h>

#include "../defines.h"

#include "../generated_headers/android_graphics_PathMeasure.h"

/** One contour of the measured path, already rebuilt as a GskPath that geometrically
 *  ends at its start when the contour is closed/force-closed. */
struct atl_path_contour {
	GskPath *path;
	GskPathMeasure *measure;
};

struct atl_path_measure {
	/** Owned reference on the measured GskPath; NULL when no path is set. */
	GskPath *path;
	gboolean force_closed;
	/** Contours with a non-zero length, in path order. */
	GArray *contours; /* struct atl_path_contour */
	/** Index of the current contour; == contours->len means "no current contour". */
	guint index;
};

/* --- rebuilding one GskPath per contour --------------------------------- */

struct contour_collector {
	GPtrArray *builders; /* GskPathBuilder*; each is consumed by free_to_path() */
	GskPathBuilder *current;
	graphene_point_t first; /* start of the current contour */
	graphene_point_t last;  /* last point emitted for the current contour */
	gboolean closed;        /* the current contour saw GSK_PATH_CLOSE */
	gboolean force_closed;
};

static void contour_collector_flush(struct contour_collector *c)
{
	if (!c->current)
		return;

	/* Skia appends the closing segment for a closed (or force-closed) contour; a
	 * zero-length chord adds nothing, so skip it instead of emitting a degenerate
	 * line (this also keeps the end-of-contour tangent on the last real segment). */
	if ((c->closed || c->force_closed) && !graphene_point_equal(&c->last, &c->first))
		gsk_path_builder_line_to(c->current, c->first.x, c->first.y);

	g_ptr_array_add(c->builders, c->current);
	c->current = NULL;
}

static gboolean contour_collector_op(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer user_data)
{
	struct contour_collector *c = user_data;

	switch (op) {
	case GSK_PATH_MOVE:
		contour_collector_flush(c);
		c->current = gsk_path_builder_new();
		gsk_path_builder_move_to(c->current, pts[0].x, pts[0].y);
		c->first = pts[0];
		c->last = pts[0];
		c->closed = FALSE;
		break;
	case GSK_PATH_CLOSE:
		c->closed = TRUE;
		break;
	case GSK_PATH_LINE:
		gsk_path_builder_line_to(c->current, pts[1].x, pts[1].y);
		c->last = pts[1];
		break;
	case GSK_PATH_QUAD:
		gsk_path_builder_quad_to(c->current, pts[1].x, pts[1].y, pts[2].x, pts[2].y);
		c->last = pts[2];
		break;
	case GSK_PATH_CUBIC:
		gsk_path_builder_cubic_to(c->current, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y);
		c->last = pts[3];
		break;
	case GSK_PATH_CONIC:
		gsk_path_builder_conic_to(c->current, pts[1].x, pts[1].y, pts[2].x, pts[2].y, weight);
		c->last = pts[2];
		break;
	}
	return TRUE;
}

static void path_measure_free_contours(struct atl_path_measure *m)
{
	for (guint i = 0; i < m->contours->len; i++) {
		struct atl_path_contour *contour = &g_array_index(m->contours, struct atl_path_contour, i);
		g_clear_pointer(&contour->measure, gsk_path_measure_unref);
		g_clear_pointer(&contour->path, gsk_path_unref);
	}
	g_array_set_size(m->contours, 0);
}

static void path_measure_build_contours(struct atl_path_measure *m)
{
	path_measure_free_contours(m);
	m->index = 0;

	if (!m->path)
		return;

	GPtrArray *builders = g_ptr_array_new();
	struct contour_collector c = {
		.builders = builders,
		.force_closed = m->force_closed,
	};
	gsk_path_foreach(m->path,
	                 GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC,
	                 contour_collector_op, &c);
	contour_collector_flush(&c);

	/* Every builder is consumed by free_to_path() exactly once, so the array holds
	 * no owned pointers while it is freed here. */
	for (guint i = 0; i < builders->len; i++) {
		GskPath *contour_path = gsk_path_builder_free_to_path(g_ptr_array_index(builders, i));
		GskPathMeasure *measure = gsk_path_measure_new(contour_path);
		if (!measure || gsk_path_measure_get_length(measure) <= 0.0f) {
			/* Degenerate contour: Skia's buildSegments() returns no measure for it,
			 * so it is not a contour nextContour() can reach. */
			gsk_path_unref(contour_path);
			g_clear_pointer(&measure, gsk_path_measure_unref);
			continue;
		}
		struct atl_path_contour contour = {
			.path = contour_path,
			.measure = measure,
		};
		g_array_append_val(m->contours, contour);
	}
	g_ptr_array_unref(builders);
}

/** The current contour, or NULL when the measure is empty (no path, or past the
 *  last contour - Android's fContour == nullptr). */
static struct atl_path_contour *path_measure_current(struct atl_path_measure *m)
{
	if (!m || m->index >= m->contours->len)
		return NULL;
	return &g_array_index(m->contours, struct atl_path_contour, m->index);
}

/* --- JNI ----------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_android_graphics_PathMeasure_native_1create(JNIEnv *env, jclass class)
{
	struct atl_path_measure *m = g_new0(struct atl_path_measure, 1);
	m->contours = g_array_new(FALSE, TRUE, sizeof(struct atl_path_contour));
	return _INTPTR(m);
}

JNIEXPORT void JNICALL Java_android_graphics_PathMeasure_native_1setPath(JNIEnv *env, jclass class, jlong handle, jlong gsk_path_ptr, jboolean force_closed)
{
	struct atl_path_measure *m = _PTR(handle);
	if (!m)
		return;

	/* Take an own reference: the measured geometry must outlive the Java Path that
	 * handed us this pointer (a snapshot, see the class comment). */
	g_clear_pointer(&m->path, gsk_path_unref);
	if (gsk_path_ptr)
		m->path = gsk_path_ref(_PTR(gsk_path_ptr));
	m->force_closed = force_closed == JNI_TRUE;
	path_measure_build_contours(m);
}

JNIEXPORT jfloat JNICALL Java_android_graphics_PathMeasure_native_1getLength(JNIEnv *env, jclass class, jlong handle)
{
	struct atl_path_measure *m = _PTR(handle);
	struct atl_path_contour *contour = path_measure_current(m);
	return contour ? gsk_path_measure_get_length(contour->measure) : 0.0f;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1nextContour(JNIEnv *env, jclass class, jlong handle)
{
	struct atl_path_measure *m = _PTR(handle);
	if (!m)
		return JNI_FALSE;
	if (m->index < m->contours->len)
		m->index++;
	return m->index < m->contours->len ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1getPosTan(JNIEnv *env, jclass class, jlong handle, jfloat distance, jfloatArray pos, jfloatArray tan)
{
	struct atl_path_measure *m = _PTR(handle);
	struct atl_path_contour *contour = path_measure_current(m);
	if (!contour)
		return JNI_FALSE;

	/* PathMeasure.getPosTan() rejects these itself; keep the native side from
	 * raising a spurious JNI exception if it is ever reached with them. */
	if (pos && (*env)->GetArrayLength(env, pos) < 2)
		return JNI_FALSE;
	if (tan && (*env)->GetArrayLength(env, tan) < 2)
		return JNI_FALSE;

	/* Skia's SkContourMeasure::getPosTan(): NaN fails, everything else is pinned to
	 * 0 <= distance <= length and still succeeds. */
	if (isnan(distance))
		return JNI_FALSE;
	float length = gsk_path_measure_get_length(contour->measure);
	if (distance < 0.0f)
		distance = 0.0f;
	else if (distance > length)
		distance = length;

	GskPathPoint point;
	if (!gsk_path_measure_get_point(contour->measure, distance, &point))
		return JNI_FALSE;

	if (pos) {
		graphene_point_t position;
		gsk_path_point_get_position(&point, contour->path, &position);
		jfloat values[2] = {position.x, position.y};
		(*env)->SetFloatArrayRegion(env, pos, 0, 2, values);
		if ((*env)->ExceptionCheck(env))
			return JNI_FALSE;
	}

	if (tan) {
		/* GSK_PATH_TO_END is the path-direction tangent of the outgoing side, i.e. the
		 * direction of travel Skia reports. For the rebuilt contours it is also the
		 * right direction at distance == length (the closing chord for a closed
		 * contour, the last segment otherwise) - a contour closed with
		 * gsk_path_builder_close() would instead wrap around to the first segment
		 * there. Normalise here so a zero-length (single-point) tangent reads as
		 * (0, 0), like Skia's setNormalize(). */
		graphene_vec2_t tangent;
		gsk_path_point_get_tangent(&point, contour->path, GSK_PATH_TO_END, &tangent);
		float tx = graphene_vec2_get_x(&tangent);
		float ty = graphene_vec2_get_y(&tangent);
		float scale = sqrtf(tx * tx + ty * ty);
		jfloat values[2] = {0.0f, 0.0f};
		if (scale > 0.0f) {
			values[0] = tx / scale;
			values[1] = ty / scale;
		}
		(*env)->SetFloatArrayRegion(env, tan, 0, 2, values);
		if ((*env)->ExceptionCheck(env))
			return JNI_FALSE;
	}

	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_android_graphics_PathMeasure_native_1destroy(JNIEnv *env, jclass class, jlong handle)
{
	struct atl_path_measure *m = _PTR(handle);
	if (!m)
		return;
	path_measure_free_contours(m);
	g_array_unref(m->contours);
	g_clear_pointer(&m->path, gsk_path_unref);
	g_free(m);
}