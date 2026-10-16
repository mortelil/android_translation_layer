#include <graphene.h>
#include <gtk/gtk.h>
#include <math.h>
#include <pango/pango.h>

#include "../defines.h"
#include "AndroidPaint.h"
#include "../text/AndroidLineGeometry.h"

#include "../generated_headers/android_atl_GskCanvas.h"

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawBitmap__JJIIIIJ(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong texture_ptr, jint x, jint y, jint width, jint height, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint->use_color_filter) {
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	}
	gtk_snapshot_append_texture(snapshot, texture, &GRAPHENE_RECT_INIT(x, y, width, height));
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawBitmap__JJIIIIIIIIJ(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong texture_ptr, jint x, jint y, jint width, jint height, jint src_x, jint src_y, jint src_width, jint src_height, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint->use_color_filter) {
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	}
	gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(x, y, width, height));
	float xscale = width / (float)src_width;
	float yscale = height / (float)src_height;
	float scaled_width = gdk_texture_get_width(texture) * xscale;
	float scaled_height = gdk_texture_get_height(texture) * yscale;
	gtk_snapshot_append_texture(snapshot, texture, &GRAPHENE_RECT_INIT(x - src_x * xscale, y - src_y * yscale, scaled_width, scaled_height));
	gtk_snapshot_pop(snapshot);
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}

/* Linear-gradient shaders. android.graphics.LinearGradient keeps its parameters in Java (see
 * LinearGradient.java), because GSK is handed the draw parameters at draw time; read them back here
 * to build the corresponding GSK gradient. Only linear gradients are expressible: any other Shader
 * subclass keeps the previous behaviour (the paint's flat colour). */
static jclass linear_gradient_class = NULL;
static jfieldID linear_gradient_x0, linear_gradient_y0, linear_gradient_x1, linear_gradient_y1;
static jfieldID linear_gradient_colors, linear_gradient_positions;

static gboolean atl_linear_gradient_init(JNIEnv *env)
{
	if (linear_gradient_class != NULL)
		return TRUE;
	jclass cls = (*env)->FindClass(env, "android/graphics/LinearGradient");
	if (cls == NULL)
		return FALSE;
	linear_gradient_class = (*env)->NewGlobalRef(env, cls);
	linear_gradient_x0 = (*env)->GetFieldID(env, cls, "x0", "F");
	linear_gradient_y0 = (*env)->GetFieldID(env, cls, "y0", "F");
	linear_gradient_x1 = (*env)->GetFieldID(env, cls, "x1", "F");
	linear_gradient_y1 = (*env)->GetFieldID(env, cls, "y1", "F");
	linear_gradient_colors = (*env)->GetFieldID(env, cls, "colors", "[I");
	linear_gradient_positions = (*env)->GetFieldID(env, cls, "positions", "[F");
	(*env)->DeleteLocalRef(env, cls);
	return linear_gradient_class != NULL && linear_gradient_colors != NULL;
}

/* Returns FALSE if the paint carries no shader GSK can express. */
static gboolean atl_snapshot_append_shader(JNIEnv *env, GtkSnapshot *snapshot, jobject shader, gboolean repeating, const GdkRGBA *paint_color, const graphene_rect_t *bounds)
{
	if (shader == NULL || !atl_linear_gradient_init(env))
		return FALSE;
	if (!(*env)->IsInstanceOf(env, shader, linear_gradient_class))
		return FALSE;

	jfloat x0 = (*env)->GetFloatField(env, shader, linear_gradient_x0);
	jfloat y0 = (*env)->GetFloatField(env, shader, linear_gradient_y0);
	jfloat x1 = (*env)->GetFloatField(env, shader, linear_gradient_x1);
	jfloat y1 = (*env)->GetFloatField(env, shader, linear_gradient_y1);
	jintArray colors = (*env)->GetObjectField(env, shader, linear_gradient_colors);
	jfloatArray positions = (*env)->GetObjectField(env, shader, linear_gradient_positions);
	if (colors == NULL)
		return FALSE;
	jsize n_stops = (*env)->GetArrayLength(env, colors);
	if (n_stops < 2)
		return FALSE;

	jint *color_ints = (*env)->GetIntArrayElements(env, colors, NULL);
	jfloat *color_positions = positions != NULL ? (*env)->GetFloatArrayElements(env, positions, NULL) : NULL;
	if (!color_ints || (positions != NULL && !color_positions)) {
        if (color_ints) (*env)->ReleaseIntArrayElements(env, colors, color_ints, JNI_ABORT);
        return FALSE;
    }
    GskColorStop *stops = g_new0(GskColorStop, n_stops);
	float last_offset = 0.f;
	for (jsize i = 0; i < n_stops; i++) {
		jint argb = color_ints[i];
		float offset = color_positions != NULL ? color_positions[i] : (float)i / (float)(n_stops - 1);
		/* Skia requires monotonically increasing offsets and clamps the ones that step back. */
		offset = CLAMP(offset, 0.f, 1.f);
		if (offset < last_offset)
			offset = last_offset;
		last_offset = offset;
		stops[i].offset = offset;
		stops[i].color.red = ((argb >> 16) & 0xFF) / 255.f;
		stops[i].color.green = ((argb >> 8) & 0xFF) / 255.f;
		stops[i].color.blue = ((argb >> 0) & 0xFF) / 255.f;
		/* The paint's alpha modulates the whole draw, gradient colours included. */
		stops[i].color.alpha = (((argb >> 24) & 0xFF) / 255.f) * paint_color->alpha;
	}
	graphene_point_t start = GRAPHENE_POINT_INIT(x0, y0);
	graphene_point_t end = GRAPHENE_POINT_INIT(x1, y1);
	if (repeating)
		gtk_snapshot_append_repeating_linear_gradient(snapshot, bounds, &start, &end, stops, n_stops);
	else
		gtk_snapshot_append_linear_gradient(snapshot, bounds, &start, &end, stops, n_stops);
	g_free(stops);
	(*env)->ReleaseIntArrayElements(env, colors, color_ints, JNI_ABORT);
	if (color_positions != NULL)
		(*env)->ReleaseFloatArrayElements(env, positions, color_positions, JNI_ABORT);
	return TRUE;
}

/* Apply paint once through the union of the fill and centred stroke. */
static void atl_snapshot_push_shape_mask(GtkSnapshot *snapshot, GskPath *path,
                                        struct AndroidPaint *paint, graphene_rect_t *bounds)
{
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_ALPHA);
	const GdkRGBA opaque = {1, 1, 1, 1};
	gtk_snapshot_append_stroke(snapshot, path, paint->gsk_stroke, &opaque);
	if (paint->is_fill)
		gtk_snapshot_append_fill(snapshot, path, GSK_FILL_RULE_WINDING, &opaque);
	gtk_snapshot_pop(snapshot);
	graphene_rect_t stroke_bounds;
	gsk_path_get_stroke_bounds(path, paint->gsk_stroke, &stroke_bounds);
	graphene_rect_union(bounds, &stroke_bounds, bounds);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jlong paint_ptr, jobject shader, jboolean repeating)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top);
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	if (paint->is_stroke) {
		GskPathBuilder *builder = gsk_path_builder_new();
		gsk_path_builder_add_rect(builder, &bounds);
		GskPath *path = gsk_path_builder_free_to_path(builder);
		/* Paint the union once: overlapping translucent fill and stroke must
		 * not increase opacity along the inside of the border. The mask also
		 * preserves shader coordinates and the stroke's join/miter settings. */
		atl_snapshot_push_shape_mask(snapshot, path, paint, &bounds);
		gsk_path_unref(path);
	}
	if (!atl_snapshot_append_shader(env, snapshot, shader, repeating, &paint->color, &bounds))
		gtk_snapshot_append_color(snapshot, &paint->color, &bounds);
	if (paint->is_stroke)
		gtk_snapshot_pop(snapshot);
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jlong paint_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	if (paint->is_stroke) {
		gtk_snapshot_append_stroke(snapshot, path, paint->gsk_stroke, &paint->color);
	}
	if (paint->is_fill) {
		gtk_snapshot_append_fill(snapshot, path, GSK_FILL_RULE_WINDING, &paint->color);
	}
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1translate(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat dx, jfloat dy)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(dx, dy));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1rotate(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat angle)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_rotate(snapshot, angle);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1save(JNIEnv *env, jclass this_class, jlong snapshot_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_save(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1restore(JNIEnv *env, jclass this_class, jlong snapshot_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_restore(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawLine(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat x0, jfloat y0, jfloat x1, jfloat y1, jlong paint_ptr)
{
	if (isnan(x0) || isnan(y0) || isnan(x1) || isnan(y1)) {
		return;
	}
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gtk_snapshot_save(snapshot);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x0, y0));
	float rotation = atan2(y1 - y0, x1 - x0);
	gtk_snapshot_rotate(snapshot, rotation * 180 / M_PI);
	float length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
	float stroke_width = gsk_stroke_get_line_width(paint->gsk_stroke);
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	gtk_snapshot_append_color(snapshot, &paint->color, &GRAPHENE_RECT_INIT(0, -stroke_width / 2, length, stroke_width));
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawLines(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloatArray points_arr, jint offset, jint count, jlong paint_ptr)
{
	jfloat *points = (*env)->GetFloatArrayElements(env, points_arr, NULL);
	for (int i = offset; i < count; i++)
		Java_android_atl_GskCanvas_native_1drawLine(env, this_class, snapshot_ptr, points[i + 0], points[i + 1], points[i + 2], points[i + 3], paint_ptr);
	(*env)->ReleaseFloatArrayElements(env, points_arr, points, 0);
}

extern GtkWidget *window;

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawText(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jstring text, jfloat x, jfloat y, jlong paint_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);
	pango_layout_set_text(layout, str, -1);
	(*env)->ReleaseStringUTFChars(env, text, str);
	PangoRectangle rect;
	pango_layout_get_pixel_extents(layout, NULL, &rect);
	y -= (float)pango_layout_get_baseline(layout) / PANGO_SCALE;
	if (paint->alignment == PANGO_ALIGN_CENTER)
		x -= rect.width / 2.f;
	else if (paint->alignment == PANGO_ALIGN_RIGHT)
		x -= rect.width;
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x, y));
	/* The paint carries the synthetic bold/oblique that Android gives a run its font family
	 * cannot serve, so a canvas text draw goes through the same funnel as Layout.draw. */
	const AtlTextStyle style = { .color = paint->color, .paint = paint };
	atl_layout_snapshot(snapshot, layout, &style);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(-x, -y));
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
	g_object_unref(layout);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRoundRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat rx, jfloat ry, jlong paint_ptr)
{
	GdkSnapshot *snapshot = (GdkSnapshot *)_PTR(snapshot_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top);
	GskRoundedRect round_rect;
	graphene_size_t radius = GRAPHENE_SIZE_INIT(MAX(rx, 0), MAX(ry, 0));
	gsk_rounded_rect_init(&round_rect, &bounds, &radius, &radius, &radius, &radius);
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	if (paint->is_stroke) {
		GskPathBuilder *builder = gsk_path_builder_new();
		gsk_path_builder_add_rounded_rect(builder, &round_rect);
		GskPath *path = gsk_path_builder_free_to_path(builder);
		atl_snapshot_push_shape_mask(snapshot, path, paint, &bounds);
		gsk_path_unref(path);
	} else {
		gtk_snapshot_push_rounded_clip(snapshot, &round_rect);
	}
	gtk_snapshot_append_color(snapshot, &paint->color, &bounds);
	gtk_snapshot_pop(snapshot);
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1scale(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat x, jfloat y)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_scale(snapshot, x, y);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1concat(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong matrix_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	graphene_matrix_t *matrix = (graphene_matrix_t *)_PTR(matrix_ptr);
	double xx, yx, xy, yy, dx, dy;
	if (graphene_matrix_to_2d(matrix, &xx, &yx, &xy, &yy, &dx, &dy)) {
		/* Preserve the 2D category: Cairo cannot draw a generic 3D transform. */
#if GTK_CHECK_VERSION(4, 20, 0)
		GskTransform *transform = gsk_transform_matrix_2d(NULL, xx, yx, xy, yy, dx, dy);
#else
		double sx = hypot(xx, yx), sy, rotation, skew = 0;
		if (sx > 0) {
			sy = (xx * yy - yx * xy) / sx;
			rotation = atan2(yx, xx);
			skew = atan((xx * xy + yx * yy) / (sx * sx));
		} else {
			sy = hypot(xy, yy);
			rotation = atan2(-xy, yy);
		}
		GskTransform *transform = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(dx, dy));
		transform = gsk_transform_rotate(transform, rotation * 180 / G_PI);
		transform = gsk_transform_scale(transform, sx, sy);
		transform = gsk_transform_skew(transform, skew * 180 / G_PI, 0);
#endif
		gtk_snapshot_transform(snapshot, transform);
		gsk_transform_unref(transform);
	} else {
		gtk_snapshot_transform_matrix(snapshot, matrix);
	}
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipRect(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
}

/* Composites everything drawn until the matching pop with the given opacity, i.e. the group
 * semantics android.graphics.Canvas.saveLayer()/saveLayerAlpha() ask for. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushOpacity(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jfloat alpha)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_opacity(snapshot, CLAMP(alpha, 0.0f, 1.0f));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pop(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jint count)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	while (count--)
		gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawRenderNode(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong node_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskRenderNode *node = _PTR(node_ptr);
	gtk_snapshot_append_node(snapshot, node);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_ALPHA);
	gtk_snapshot_append_fill(snapshot, path, GSK_FILL_RULE_WINDING, &(GdkRGBA){1, 1, 1, 1});
	gtk_snapshot_pop(snapshot);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipOutPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_type)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	gtk_snapshot_push_mask(snapshot, fill_type >= 2 ? GSK_MASK_MODE_ALPHA : GSK_MASK_MODE_INVERTED_ALPHA);
	gtk_snapshot_append_fill(snapshot, path, (fill_type & 1) ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING, &(GdkRGBA){1, 1, 1, 1});
	gtk_snapshot_pop(snapshot);
}
