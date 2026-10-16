/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <gtk/gtk.h>

/* Forward declaration: only the canvas text path has a Paint (graphics/AndroidPaint.h). */
struct AndroidPaint;

typedef struct {
	int top, baseline, bottom, ascent, descent;
	int natural_baseline;
	float x;
} AtlTextLine;

typedef struct {
	guint serial;
	float add, mult;
	GArray *lines;
	int height;
} AtlLineGeometry;

/* What a layout is drawn with. PAINT is the paint the runs belong to, whose synthetic bold and
 * oblique the draw applies to the runs the family cannot serve; a drawing that has no Paint
 * (a widget's own label) leaves it NULL and keeps the pure Pango path. */
typedef struct {
	GdkRGBA color;
	struct AndroidPaint *paint;
} AtlTextStyle;

void atl_layout_set_spacing(PangoLayout *layout, float add, float mult);
const AtlLineGeometry *atl_layout_geometry(PangoLayout *layout);
void atl_layout_snapshot(GtkSnapshot *snapshot, PangoLayout *layout, const AtlTextStyle *style);
