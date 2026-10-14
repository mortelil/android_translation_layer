/* SPDX-License-Identifier: GPL-3.0-only */
#include "AndroidLineGeometry.h"
#include <pango/pangocairo.h>
#include <math.h>

/* Pango owns shaping and wrapping. Android owns the extra space after each
 * line, excluding the last. Contract reference: AOSP StaticLayout.out(),
 * android-17.0.0_r1, 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d.
 * Do not change glyph rise or logical font extents to simulate this spacing:
 * doing so changes shaping metrics and places ink differently from the APIs.
 */
static void geometry_free(gpointer value)
{
	AtlLineGeometry *g = value;
	g_array_unref(g->lines);
	g_free(g);
}

static AtlLineGeometry *geometry_get(PangoLayout *layout)
{
	AtlLineGeometry *g = g_object_get_data(G_OBJECT(layout), "atl-line-geometry");
	if (!g) {
		g = g_new0(AtlLineGeometry, 1);
		g->mult = 1;
		g->lines = g_array_new(FALSE, FALSE, sizeof(AtlTextLine));
		g_object_set_data_full(G_OBJECT(layout), "atl-line-geometry", g, geometry_free);
	}
	return g;
}

void atl_layout_set_spacing(PangoLayout *layout, float add, float mult)
{
	AtlLineGeometry *g = geometry_get(layout);
	if (g->add == add && g->mult == mult) return;
	g->add = add;
	g->mult = mult;
	g->serial = 0;
}

const AtlLineGeometry *atl_layout_geometry(PangoLayout *layout)
{
	AtlLineGeometry *g = geometry_get(layout);
	guint serial = pango_layout_get_serial(layout);
	if (g->serial == serial && g->lines->len) return g;
	g_array_set_size(g->lines, 0);
	int count = pango_layout_get_line_count(layout), index = 0;
	gint64 shift = 0;
	PangoLayoutIter *iter = pango_layout_get_iter(layout);
	do {
		PangoRectangle logical, positioned;
		pango_layout_line_get_extents(pango_layout_iter_get_line_readonly(iter), NULL, &logical);
		pango_layout_iter_get_line_extents(iter, NULL, &positioned);
		int natural_baseline = PANGO_PIXELS(pango_layout_iter_get_baseline(iter));
		int ascent = PANGO_PIXELS(PANGO_ASCENT(logical));
		int descent = PANGO_PIXELS(PANGO_DESCENT(logical));
		double value = (ascent + descent) * ((double)g->mult - 1.0) + g->add;
		int extra = index + 1 == count ? 0 : (int)CLAMP(round(value), G_MININT, G_MAXINT);
		AtlTextLine line = {
			.top = CLAMP(natural_baseline - ascent + shift, G_MININT, G_MAXINT),
			.baseline = CLAMP(natural_baseline + shift, G_MININT, G_MAXINT),
			.bottom = CLAMP(natural_baseline + descent + shift + extra, G_MININT, G_MAXINT),
			.ascent = -ascent, .descent = CLAMP((gint64)descent + extra, G_MININT, G_MAXINT),
			.natural_baseline = natural_baseline,
			.x = (float)positioned.x / PANGO_SCALE
		};
		g_array_append_val(g->lines, line);
		shift += extra;
		index++;
	} while (pango_layout_iter_next_line(iter));
	pango_layout_iter_free(iter);
	int natural_height;
	pango_layout_get_pixel_size(layout, NULL, &natural_height);
	g->height = CLAMP(natural_height + shift, G_MININT, G_MAXINT);
	g->serial = serial;
	return g;
}

void atl_layout_snapshot(GtkSnapshot *snapshot, PangoLayout *layout, const GdkRGBA *color)
{
	const AtlLineGeometry *g = atl_layout_geometry(layout);
	if (g->add == 0 && g->mult == 1) {
		gtk_snapshot_append_layout(snapshot, layout, color);
		return;
	}
	/* Include actual ink, including overhangs and overlapping negative spacing. */
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, 1, MAX(1, g->height));
	for (guint i = 0; i < g->lines->len; i++) {
		const AtlTextLine *line = &g_array_index(g->lines, AtlTextLine, i);
		PangoRectangle ink;
		pango_layout_line_get_pixel_extents(pango_layout_get_line_readonly(layout, i), &ink, NULL);
		graphene_rect_t rect = GRAPHENE_RECT_INIT(line->x + ink.x, line->baseline + ink.y,
		                                        ink.width, ink.height);
		graphene_rect_union(&bounds, &rect, &bounds);
	}
	cairo_t *cr = gtk_snapshot_append_cairo(snapshot, &bounds);
	gdk_cairo_set_source_rgba(cr, color);
	for (guint i = 0; i < g->lines->len; i++) {
		const AtlTextLine *line = &g_array_index(g->lines, AtlTextLine, i);
		cairo_move_to(cr, line->x, line->baseline);
		pango_cairo_show_layout_line(cr, pango_layout_get_line_readonly(layout, i));
	}
	cairo_destroy(cr);
}
