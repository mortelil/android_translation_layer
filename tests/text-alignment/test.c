/* SPDX-License-Identifier: GPL-3.0-only */
/* Paragraph alignment must reach the ink of the GtkLabel that paints a TextView, and every line
 * must sit where AOSP's Layout#getLineLeft puts it:
 *   ALIGN_CENTER  -> floor(paragraphLeft + (mWidth - getLineMax(line)) / 2)
 *   ALIGN_RIGHT   -> mWidth - getLineMax(line)
 * (android-17.0.0_r1 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/text/Layout.java
 * :1967-2055; ATL's paragraphs are left-to-right, getParagraphLeft() == 0 and getParagraphRight()
 * == mWidth, so ALIGN_NORMAL folds to the left edge and ALIGN_OPPOSITE to the right one.)
 * The lines, their widths and the ink offset of each line origin come from a reference Pango layout
 * built with the same font, attributes, text and width, so the check compares rendered ink only. */
#include <gtk/gtk.h>
#include <math.h>
#include <stdio.h>
#include "../../src/api-impl-jni/graphics/AndroidPaint.h"
#include "../../src/api-impl-jni/graphics/android_graphics_Paint.h"
#include "../../src/api-impl-jni/text/AndroidLineGeometry.h"
#include "../../src/api-impl-jni/widgets/AndroidLineBox.h"

static int checks;
#define CHECK(expr) do { checks++; if (!(expr)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #expr); exit(1); } } while (0)

/* The synthetic bold/oblique of one run is decided from the font file the run resolved to
 * (graphics/android_graphics_Paint.c); the geometry compiled here only needs the symbol. */
AtlTextSynthesis atl_paint_run_synthesis(struct AndroidPaint *paint, PangoFont *font, double size_dp)
{
	AtlTextSynthesis synthesis = { 0, 0 };
	return synthesis;
}

typedef struct { int min_x, max_x; } InkBox;

/* Ink columns of every rendered line band, in surface pixels. */
static int node_ink_lines(GskRenderNode *node, InkBox *boxes, int max)
{
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, 512, 1024);
	cairo_t *cr = cairo_create(surface);
	if (node) { gsk_render_node_draw(node, cr); gsk_render_node_unref(node); }
	cairo_destroy(cr);
	cairo_surface_flush(surface);
	unsigned char *bytes = cairo_image_surface_get_data(surface);
	int stride = cairo_image_surface_get_stride(surface), count = 0, prior = 0;
	for (int y = 0; y < 1024 && count <= max; y++) {
		int min_x = 512, max_x = -1;
		for (int x = 0; x < 512; x++)
			if (bytes[y * stride + x * 4 + 3]) {
				if (x < min_x) min_x = x;
				if (x > max_x) max_x = x;
			}
		int ink = max_x >= 0;
		if (ink) {
			if (!prior) {
				if (count == max) break;
				boxes[count].min_x = min_x;
				boxes[count].max_x = max_x;
				count++;
			} else {
				if (min_x < boxes[count - 1].min_x) boxes[count - 1].min_x = min_x;
				if (max_x > boxes[count - 1].max_x) boxes[count - 1].max_x = max_x;
			}
		}
		prior = ink;
	}
	cairo_surface_destroy(surface);
	return count;
}

static int widget_ink_lines(GtkWidget *widget, InkBox *boxes, int max)
{
	while (g_main_context_iteration(NULL, FALSE));
	GdkPaintable *paintable = gtk_widget_paintable_new(widget);
	GtkSnapshot *snapshot = gtk_snapshot_new();
	gdk_paintable_snapshot(paintable, snapshot, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
	g_object_unref(paintable);
	return node_ink_lines(gtk_snapshot_free_to_node(snapshot), boxes, max);
}

static void alignment_case(GtkWidget *box, GtkLabel *label, const char *text, int alignment,
                           const char *name)
{
	gtk_label_set_text(label, text);
	atl_line_box_set_alignment(ATL_LINE_BOX(box), alignment);
	for (int i = 0; i < 20; i++) { while (g_main_context_iteration(NULL, FALSE)); g_usleep(10000); }

	int width = gtk_widget_get_width(GTK_WIDGET(label));
	InkBox boxes[16];
	int count = widget_ink_lines(box, boxes, 16);

	/* Reference: same context, font, attributes, text and width, laid out without alignment. */
	PangoContext *context = gtk_widget_get_pango_context(GTK_WIDGET(label));
	PangoLayout *ref = pango_layout_new(context);
	PangoFontDescription *font = pango_font_description_copy(pango_context_get_font_description(context));
	pango_layout_set_font_description(ref, font);
	pango_font_description_free(font);
	pango_layout_set_attributes(ref, gtk_label_get_attributes(label));
	pango_layout_set_text(ref, text, -1);
	pango_layout_set_width(ref, width * PANGO_SCALE);
	pango_layout_set_wrap(ref, PANGO_WRAP_WORD_CHAR);
	int lines = pango_layout_get_line_count(ref);

	fprintf(stderr, "[alignment %s] label=%dx%d lines=%d rendered=%d\n",
	        name, width, gtk_widget_get_height(GTK_WIDGET(label)), lines, count);
	CHECK(width > 0);
	/* Each case must exercise per-line placement, not a single line that cannot differ. */
	CHECK(lines >= 2);
	CHECK(count == lines);
	for (int i = 0; i < lines; i++) {
		PangoRectangle ink, logical;
		pango_layout_line_get_pixel_extents(pango_layout_get_line_readonly(ref, i), &ink, &logical);
		double left;
		switch (alignment) {
		case ATL_ALIGN_CENTER: left = floor((width - logical.width) / 2.0); break;
		case ATL_ALIGN_RIGHT: left = width - logical.width; break;
		default: left = 0; break;
		}
		double expected = left + ink.x;
		/* Measured first: the values are the evidence when a build does not align. */
		fprintf(stderr, "[alignment %s] line %d lineMax=%d ink.x=%d left=%.1f expected=%.1f rendered=%.1f..%.1f\n",
		        name, i, logical.width, ink.x, left, expected,
		        (double)boxes[i].min_x, (double)boxes[i].max_x);
		CHECK(fabs(boxes[i].min_x - expected) <= 1.0);
		CHECK(abs((boxes[i].max_x - boxes[i].min_x + 1) - ink.width) <= 2);
	}
	g_object_unref(ref);
}

int main(void)
{
	gtk_init();
	GtkWidget *label = gtk_label_new("");
	gtk_label_set_wrap(GTK_LABEL(label), TRUE);
	gtk_label_set_xalign(GTK_LABEL(label), 0.f);
	gtk_label_set_yalign(GTK_LABEL(label), 0.f);
	gtk_widget_set_hexpand(label, TRUE);
	GtkWidget *box = g_object_ref_sink(atl_line_box_new(label));
	gtk_widget_set_hexpand(box, TRUE);

	PangoAttrList *attrs = pango_attr_list_new();
	pango_attr_list_insert(attrs, pango_attr_font_features_new("tnum"));
	/* The size TextView#native_setTextSize gives the label (Android pixels, not points). */
	pango_attr_list_insert(attrs, pango_attr_size_new_absolute(16 * PANGO_SCALE));
	gtk_label_set_attributes(GTK_LABEL(label), attrs);
	pango_attr_list_unref(attrs);

	GtkWidget *window = gtk_window_new();
	gtk_window_set_default_size(GTK_WINDOW(window), 347, 240);
	gtk_window_set_child(GTK_WINDOW(window), box);
	gtk_window_present(GTK_WINDOW(window));
	for (int i = 0; i < 20; i++) { while (g_main_context_iteration(NULL, FALSE)); g_usleep(10000); }

	/* A paragraph that wraps into several lines of different widths. */
	alignment_case(box, GTK_LABEL(label),
	               "AntennaPod can subscribe to any podcast, fetch new episodes and play them back.",
	               ATL_ALIGN_CENTER, "center");
	alignment_case(box, GTK_LABEL(label),
	               "AntennaPod can subscribe to any podcast, fetch new episodes and play them back.",
	               ATL_ALIGN_RIGHT, "right");
	alignment_case(box, GTK_LABEL(label),
	               "AntennaPod can subscribe to any podcast, fetch new episodes and play them back.",
	               ATL_ALIGN_NORMAL, "normal");
	/* Two paragraphs: the break after the first line is explicit. */
	alignment_case(box, GTK_LABEL(label),
	               "Short title here\nand a longer body line below it, wrapping once more",
	               ATL_ALIGN_CENTER, "center-2");

	gtk_window_destroy(GTK_WINDOW(window));
	g_object_unref(box);
	printf("TEXT_ALIGNMENT PASS checks=%d\n", checks);
	return 0;
}
