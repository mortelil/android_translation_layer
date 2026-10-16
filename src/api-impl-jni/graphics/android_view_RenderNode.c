#include <gsk/gsk.h>
#include <gtk/gtk.h>

#include "../defines.h"

#include "../generated_headers/android_view_RenderNode.h"

/* android.graphics.Outline's modes (see src/api-impl/android/graphics/Outline.java) */
#define OUTLINE_MODE_EMPTY 0
#define OUTLINE_MODE_ROUND_RECT 1
#define OUTLINE_MODE_PATH 2

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeTransform(JNIEnv *env, jobject this, jlong node_ptr, jfloat scale_x, jfloat scale_y, jfloat translate_x, jfloat translate_y, jfloat rotate, jfloat pivot_x, jfloat pivot_y)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskTransform *transform = NULL;
	GskRenderNode *transformed_node;
	transform = gsk_transform_translate(transform, &GRAPHENE_POINT_INIT(-pivot_x, -pivot_y));
	transform = gsk_transform_scale(transform, scale_x, scale_y);
	transform = gsk_transform_rotate(transform, rotate);
	transform = gsk_transform_translate(transform, &GRAPHENE_POINT_INIT(translate_x + pivot_x, translate_y + pivot_y));
	if (transform) {
		transformed_node = gsk_transform_node_new(node, transform);
		gsk_transform_unref(transform);
	} else {
		transformed_node = gsk_render_node_ref(node);
	}
	return _INTPTR(transformed_node);
}

/* Mirrors AOSP's outline clip shape selection (frameworks/base/libs/hwui/pipeline/skia/
 * RenderNodeDrawable.cpp clipOutline(), android-17.0.0_r1, commit
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d): a round-rect outline clips to a rounded rectangle
 * whose radius is scaled down the way SkRRect::MakeRectXY() does when it overflows the bounds
 * (radius 0 stays a plain rectangle), and any other outline clips to its path honouring the
 * path's fill type. The outline is in the node's own coordinate space: AOSP concatenates the
 * node matrix first and clips afterwards, so the caller has to keep the layer transform outside
 * the returned node. Unlike the clip it replaces, this takes a reference on node_ptr instead of
 * consuming it; the caller keeps owning it. Returns 0 when there is nothing to clip with. */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeClipOutline(JNIEnv *env, jobject this, jlong node_ptr, jint mode, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat radius, jint fill_type, jlong path_ptr)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskRenderNode *clipped_node = NULL;
	float width = right - left;
	float height = bottom - top;

	if (mode == OUTLINE_MODE_PATH) {  /* android.graphics.Outline.MODE_PATH */
		GskPath *path = _PTR(path_ptr);
		if (!path)
			return 0;
		GskFillRule fill_rule = (fill_type & 1) ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING;
		if (fill_type >= 2) {
			/* An inverse fill type clips to everything *outside* the path, which a fill node
			 * cannot express; use the inverted alpha mask Canvas.clipOutPath() uses, over the
			 * bounds of the node being clipped. */
			graphene_rect_t bounds;
			gsk_render_node_get_bounds(node, &bounds);
			GdkRGBA white = {1.f, 1.f, 1.f, 1.f};
			GskRenderNode *fill = gsk_color_node_new(&white, &bounds);
			GskRenderNode *mask = gsk_fill_node_new(fill, path, fill_rule);
			gsk_render_node_unref(fill);
			clipped_node = gsk_mask_node_new(node, mask, GSK_MASK_MODE_INVERTED_ALPHA);
			gsk_render_node_unref(mask);
		} else {
			clipped_node = gsk_fill_node_new(node, path, fill_rule);
		}
	} else if (mode == OUTLINE_MODE_ROUND_RECT && width > 0 && height > 0) {  /* Outline.MODE_ROUND_RECT */
		if (radius > 0) {
			float rx = radius * MIN(1.0f, MIN(width / (2 * radius), height / (2 * radius)));
			GskRoundedRect rounded_rect = {
				.bounds = GRAPHENE_RECT_INIT(left, top, width, height),
				.corner = {{rx, rx}, {rx, rx}, {rx, rx}, {rx, rx}},
			};
			clipped_node = gsk_rounded_clip_node_new(node, &rounded_rect);
		} else {
			clipped_node = gsk_clip_node_new(node, &GRAPHENE_RECT_INIT(left, top, width, height));
		}
	}
	if (getenv("ATL_TRACE_OUTLINE_CLIP"))
		g_printerr("ATL_TRACE_OUTLINE_CLIP mode=%d rect=(%g,%g,%g,%g) radius=%g fill_type=%d path=%p -> node=%p type=%d\n",
				mode, left, top, right, bottom, radius, fill_type, _PTR(path_ptr), clipped_node,
				clipped_node ? gsk_render_node_get_node_type(clipped_node) : -1);
	return _INTPTR(clipped_node);
}

/* Wraps the node so that its whole subtree is composited with the layer's alpha,
 * matching how Android applies RenderNode.setAlpha() when the layer is drawn. */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeOpacity(JNIEnv *env, jobject this, jlong node_ptr, jfloat alpha)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskRenderNode *opacity_node = gsk_opacity_node_new(node, CLAMP(alpha, 0.0f, 1.0f));
	gsk_render_node_unref(node);
	return _INTPTR(opacity_node);
}

JNIEXPORT void JNICALL Java_android_view_RenderNode_nativeUnref(JNIEnv *env, jobject this, jlong node_ptr)
{
	if (node_ptr != 0)
		gsk_render_node_unref(_PTR(node_ptr));
}

JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeCreateEmpyNode(JNIEnv *env, jobject this)
{
	return _INTPTR(gsk_container_node_new(NULL, 0));
}
