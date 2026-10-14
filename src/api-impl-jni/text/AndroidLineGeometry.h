/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <gtk/gtk.h>

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

void atl_layout_set_spacing(PangoLayout *layout, float add, float mult);
const AtlLineGeometry *atl_layout_geometry(PangoLayout *layout);
void atl_layout_snapshot(GtkSnapshot *snapshot, PangoLayout *layout, const GdkRGBA *color);
