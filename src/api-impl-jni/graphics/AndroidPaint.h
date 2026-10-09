#pragma once
#include "pango/pango-layout.h"
#include <pango/pangocairo.h>
#include <gdk/gdk.h>
#include <gsk/gsk.h>
#include <pango/pango.h>

struct AndroidPaint {
	GdkRGBA color;
	GskStroke *gsk_stroke;
	PangoFontDescription *font;
	PangoFontMap *font_map;
	PangoAlignment alignment;
	graphene_matrix_t color_matrix;
	graphene_vec4_t color_offset;
	bool is_fill : 1;
	bool is_stroke : 1;
	bool use_color_filter : 1;
};

static inline PangoContext *atl_paint_context(struct AndroidPaint *paint, PangoContext *base)
{
	if (!paint->font_map) return g_object_ref(base);
	PangoContext *context = pango_font_map_create_context(paint->font_map);
	pango_context_set_language(context, pango_context_get_language(base));
	pango_context_set_base_dir(context, pango_context_get_base_dir(base));
	pango_context_set_matrix(context, pango_context_get_matrix(base));
	pango_cairo_context_set_resolution(context, pango_cairo_context_get_resolution(base));
	pango_cairo_context_set_font_options(context, pango_cairo_context_get_font_options(base));
	pango_context_set_round_glyph_positions(context, pango_context_get_round_glyph_positions(base));
	return context;
}
static inline PangoLayout *atl_paint_layout(struct AndroidPaint *paint, PangoContext *base)
{
	PangoContext *context = atl_paint_context(paint, base);
	PangoLayout *layout = pango_layout_new(context);
	g_object_unref(context);
	return layout;
}
