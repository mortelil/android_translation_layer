/* SPDX-License-Identifier: GPL-3.0-only */
#include "AndroidLineGeometry.h"
#include "../graphics/AndroidPaint.h"
#include "../graphics/android_graphics_Paint.h"
#include <pango/pangocairo.h>
#include <math.h>
#include <stdio.h>

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

/* The synthetic bold/oblique the runs of LINE ask for. Only a paint carries such a request, and
 * only a font family that cannot serve it results in one (see android_graphics_Paint.h). */
static void line_synthesis(struct AndroidPaint *paint, PangoLayoutLine *line, double size_dp,
                           double *embolden, float *skew_x)
{
	*embolden = 0;
	*skew_x = 0;
	if (!paint)
		return;
	for (GSList *run = line->runs; run; run = run->next) {
		AtlTextSynthesis synthesis = atl_paint_run_synthesis(
			paint, ((PangoGlyphItem *)run->data)->item->analysis.font, size_dp);
		*embolden = MAX(*embolden, synthesis.embolden);
		if (fabsf(synthesis.skew_x) > fabsf(*skew_x))
			*skew_x = synthesis.skew_x;
	}
}

/* Prints what the app asked for and what was resolved for every run, for the Step 0 diagnosis of
 * the synthetic-style path: ATL_DEBUG_TEXT_STYLE=1 turns it on. */
static void log_text_style(PangoLayout *layout, struct AndroidPaint *paint, double size_dp)
{
	if (!g_getenv("ATL_DEBUG_TEXT_STYLE"))
		return;
	static int draws;
	const char *text = pango_layout_get_text(layout);
	fprintf(stderr, "[atl-text-style] draw=%d fakeBold=%d textSkewX=%g size=%g text=\"%.60s\"\n",
	        ++draws, paint ? (int)paint->fake_bold : 0, paint ? (double)paint->text_skew_x : 0.0,
	        size_dp, text ? text : "");
	for (int i = 0; i < pango_layout_get_line_count(layout); i++) {
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, i);
		for (GSList *run = line->runs; run; run = run->next) {
			PangoGlyphItem *item = run->data;
			PangoFontDescription *description = pango_font_describe(item->item->analysis.font);
			AtlTextSynthesis synthesis = atl_paint_run_synthesis(paint, item->item->analysis.font, size_dp);
			fprintf(stderr, "[atl-text-style]   run=%s advance=%.3f embolden=%.3f skewX=%.3f\n",
			        pango_font_description_to_string(description),
			        (double)pango_glyph_string_get_width(item->glyphs) / PANGO_SCALE,
			        synthesis.embolden, (double)synthesis.skew_x);
			pango_font_description_free(description);
		}
		PangoRectangle ink, logical;
		pango_layout_line_get_pixel_extents(line, &ink, &logical);
		fprintf(stderr, "[atl-text-style]   line=%d advance=%d ink=%d..%d\n",
		        i, logical.width, ink.x, ink.x + ink.width);
	}
}

void atl_layout_snapshot(GtkSnapshot *snapshot, PangoLayout *layout, const AtlTextStyle *style)
{
	const AtlLineGeometry *g = atl_layout_geometry(layout);
	bool spaced = g->add != 0 || g->mult != 1;
	struct AndroidPaint *paint = style->paint;
	/* The em a run is synthesised against, in the units the snapshot is drawn in: Pango's
	 * absolute size is the dp the paint set (native_set_text_size). */
	double size_dp = paint ? (double)pango_font_description_get_size(paint->font) / PANGO_SCALE : 0;
	log_text_style(layout, paint, size_dp);
	if (!paint && !spaced) {
		gtk_snapshot_append_layout(snapshot, layout, &style->color);
		return;
	}
	/* Include actual ink, including overhangs and overlapping negative spacing. */
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, 1, MAX(1, g->height));
	double grow = 0;
	bool synthesised = false;
	for (guint i = 0; i < g->lines->len; i++) {
		const AtlTextLine *line = &g_array_index(g->lines, AtlTextLine, i);
		PangoLayoutLine *pango_line = pango_layout_get_line_readonly(layout, i);
		PangoRectangle ink;
		pango_layout_line_get_pixel_extents(pango_line, &ink, NULL);
		graphene_rect_t rect = GRAPHENE_RECT_INIT(line->x + ink.x, line->baseline + ink.y,
		                                        ink.width, ink.height);
		graphene_rect_union(&bounds, &rect, &bounds);
		/* A dilated outline grows the ink by half the strength on every side; a shear moves it
		 * sideways by the shear times the line's extent above and below the baseline. */
		double embolden;
		float skew_x;
		line_synthesis(paint, pango_line, size_dp, &embolden, &skew_x);
		synthesised = synthesised || embolden > 0 || skew_x != 0;
		grow = MAX(grow, embolden * 0.5 + fabs(skew_x) * MAX(-line->ascent, line->descent));
	}
	/* Nothing to synthesise: draw through GSK's own layout node, the way every other Pango path
	 * in ATL does. A cairo node draws the same runs unhinted, which changes their weight. */
	if (!synthesised && !spaced) {
		gtk_snapshot_append_layout(snapshot, layout, &style->color);
		return;
	}
	bounds.origin.x -= grow;
	bounds.origin.y -= grow;
	bounds.size.width += 2 * grow;
	bounds.size.height += 2 * grow;
	cairo_t *cr = gtk_snapshot_append_cairo(snapshot, &bounds);
	gdk_cairo_set_source_rgba(cr, &style->color);
	for (guint i = 0; i < g->lines->len; i++) {
		const AtlTextLine *line = &g_array_index(g->lines, AtlTextLine, i);
		PangoLayoutLine *pango_line = pango_layout_get_line_readonly(layout, i);
		double embolden;
		float skew_x;
		line_synthesis(paint, pango_line, size_dp, &embolden, &skew_x);
		if (embolden == 0 && skew_x == 0) {
			cairo_move_to(cr, line->x, line->baseline);
			pango_cairo_show_layout_line(cr, pango_line);
			continue;
		}
		/* A synthesised line is drawn run by run: Pango's runs are in visual order, so the pen
		 * walks left to right over them, and each run's advance is unchanged by the synthesis
		 * (Android's outline dilation and shear never move the pen). */
		double pen = line->x;
		for (GSList *run = pango_line->runs; run; run = run->next) {
			PangoGlyphItem *item = run->data;
			AtlTextSynthesis synthesis = atl_paint_run_synthesis(paint, item->item->analysis.font, size_dp);
			cairo_save(cr);
			cairo_translate(cr, pen, line->baseline);
			if (synthesis.skew_x != 0) {
				/* x' = x + skewX * (y - baseline) about the baseline: the shear keeps the
				 * baseline where the layout placed it. */
				cairo_matrix_t matrix;
				cairo_matrix_init(&matrix, 1, 0, synthesis.skew_x, 1, 0, 0);
				cairo_transform(cr, &matrix);
			}
			pango_cairo_glyph_string_path(cr, item->item->analysis.font, item->glyphs);
			if (synthesis.embolden > 0) {
				/* FT_Outline_Embolden offsets every outline edge outward by strength/2, which
				 * is a morphological dilation of the ink; stroke+fill dilates the path by the
				 * same amount. Round joins round convex corners by strength/2 where FreeType
				 * keeps a straight edge sharp: the one approximation of this path. */
				cairo_fill_preserve(cr);
				cairo_set_line_join(cr, CAIRO_LINE_JOIN_ROUND);
				cairo_set_line_width(cr, synthesis.embolden);
				cairo_stroke(cr);
			} else {
				cairo_fill(cr);
			}
			cairo_restore(cr);
			pen += (double)pango_glyph_string_get_width(item->glyphs) / PANGO_SCALE;
		}
	}
	cairo_destroy(cr);
}
