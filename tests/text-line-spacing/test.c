/* SPDX-License-Identifier: GPL-3.0-only */
#include <gtk/gtk.h>
#include <math.h>
#include <stdio.h>
#include "../../src/api-impl-jni/text/AndroidLineGeometry.h"
#include "../../src/api-impl-jni/widgets/AndroidLineBox.h"

static int checks;
static int last_ink_bottom;
#define CHECK(expr) do { checks++; if (!(expr)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #expr); exit(1); } } while (0)

/* Observe ink rows, not the line-box measurements the implementation returns. */
static int node_ink_bands(GskRenderNode *node, int *tops)
{
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, 512, 1024);
	cairo_t *cr = cairo_create(surface);
	if (node) { gsk_render_node_draw(node, cr); gsk_render_node_unref(node); }
	cairo_destroy(cr);
	cairo_surface_flush(surface);
	unsigned char *bytes = cairo_image_surface_get_data(surface);
	int stride = cairo_image_surface_get_stride(surface), count=0, prior=0;
	last_ink_bottom = 0;
	for (int y=0; y<1024; y++) {
		int ink=0;
		for (int x=0; x<512; x++) if (bytes[y*stride+x*4+3]) { ink=1; break; }
		if (ink && !prior) { CHECK(count<64); tops[count++]=y; }
		if (ink) last_ink_bottom=y+1;
		prior=ink;
	}
	cairo_surface_destroy(surface);
	return count;
}

static int ink_bands(PangoLayout *layout, int *tops)
{
	GtkSnapshot *snapshot = gtk_snapshot_new();
	GdkRGBA black = {0,0,0,1};
	atl_layout_snapshot(snapshot, layout, &black);
	return node_ink_bands(gtk_snapshot_free_to_node(snapshot), tops);
}

static int widget_ink_bands(GtkWidget *widget, int *tops)
{
	while (g_main_context_iteration(NULL, FALSE));
	GdkPaintable *paintable=gtk_widget_paintable_new(widget);
	GtkSnapshot *snapshot=gtk_snapshot_new();
	gdk_paintable_snapshot(paintable, snapshot, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
	g_object_unref(paintable);
	return node_ink_bands(gtk_snapshot_free_to_node(snapshot), tops);
}

static void spacing_case(PangoContext *context, const char *text, int width, gboolean mixed,
                         float add, float mult)
{
	PangoLayout *layout = pango_layout_new(context);
	PangoFontDescription *font = pango_font_description_from_string("DejaVu Sans 20");
	pango_font_description_set_absolute_size(font, 24*PANGO_SCALE);
	pango_layout_set_font_description(layout, font);
	pango_font_description_free(font);
	pango_layout_set_text(layout,text,-1);
	pango_layout_set_width(layout,width < 0 ? -1 : width*PANGO_SCALE);
	pango_layout_set_wrap(layout,PANGO_WRAP_WORD_CHAR);
	if (mixed) {
		PangoAttrList *attrs=pango_attr_list_new();
		PangoAttribute *size=pango_attr_size_new_absolute(40*PANGO_SCALE);
		size->start_index=0; size->end_index=1; pango_attr_list_insert(attrs,size);
		pango_layout_set_attributes(layout,attrs); pango_attr_list_unref(attrs);
	}
	const AtlLineGeometry *base = atl_layout_geometry(layout);
	int n=base->lines->len, natural_height=base->height;
	CHECK(n<64);
	AtlTextLine lines[64];
	for(int i=0;i<n;i++) lines[i]=g_array_index(base->lines,AtlTextLine,i);
	int before[64],after[64];
	int before_count=ink_bands(layout,before);
	int before_bottom=last_ink_bottom;
	atl_layout_set_spacing(layout,add,mult);
	const AtlLineGeometry *spaced=atl_layout_geometry(layout);
	int after_count=ink_bands(layout,after), shift=0;
	int after_bottom=last_ink_bottom;
	CHECK(before_count==after_count || add<0 || mult<1);
	for(int i=0;i<n;i++) {
		AtlTextLine line=g_array_index(spaced->lines,AtlTextLine,i);
		CHECK(line.baseline==lines[i].baseline+shift);
		CHECK(line.ascent==lines[i].ascent);
		if (before_count==after_count && i<before_count) CHECK(abs(after[i]-before[i]-shift)<=1);
		int extra=i+1==n ? 0 : (int)round((lines[i].descent-lines[i].ascent)*(mult-1)+add);
		CHECK(line.descent==lines[i].descent+extra);
		shift+=extra;
	}
	CHECK(spaced->height==natural_height+shift);
	if (before_count==n) CHECK(abs(after_bottom-before_bottom-shift)<=1);
	// Replacing text invalidates cached geometry, including an empty last line.
	pango_layout_set_text(layout,"A",-1);
	CHECK(atl_layout_geometry(layout)->lines->len==1);
	CHECK(g_array_index(atl_layout_geometry(layout)->lines,AtlTextLine,0).descent>=0);
	g_object_unref(layout);
}

int main(void)
{
	gtk_init();
	GtkWidget *label=gtk_label_new("Ag\nAg\nAg");
	gtk_label_set_wrap(GTK_LABEL(label),TRUE);
	GtkWidget *box=g_object_ref_sink(atl_line_box_new(label));
	PangoContext *context=gtk_widget_get_pango_context(label);
	spacing_case(context,"Ag\nAg\nAg",-1,FALSE,12,1);
	spacing_case(context,"Ag\nAg\nAg",-1,TRUE,3,1.5);
	spacing_case(context,"Ag Ag Ag Ag Ag Ag",50,FALSE,8,1);
	spacing_case(context,"Ag\nAg\nAg",-1,FALSE,-2,1);
	spacing_case(context,"Ag\nAg\nAg",-1,FALSE,0,0.5);
	spacing_case(context,"Ag\nAg\n",-1,FALSE,9,1);
	spacing_case(context,"Ag",-1,FALSE,40,2);
	int before,natural,after,baseline;
	gtk_widget_measure(box,GTK_ORIENTATION_VERTICAL,160,&before,&natural,NULL,NULL);
	atl_line_box_set_spacing(ATL_LINE_BOX(box),10,1);
	gtk_widget_measure(box,GTK_ORIENTATION_VERTICAL,160,&after,&natural,&baseline,NULL);
	CHECK(abs(after-before-20)<=1);
	gtk_label_set_text(GTK_LABEL(label),"Ag");
	gtk_widget_measure(box,GTK_ORIENTATION_VERTICAL,160,&after,&natural,NULL,NULL);
	atl_line_box_set_spacing(ATL_LINE_BOX(box),0,1);
	gtk_widget_measure(box,GTK_ORIENTATION_VERTICAL,160,&before,&natural,NULL,NULL);
	CHECK(abs(after-before)<=1);
	// Observe the actual widget snapshot, not only the shared renderer.
	gtk_label_set_text(GTK_LABEL(label), "Ag\nAg\nAg");
	gtk_label_set_yalign(GTK_LABEL(label), 0);
	GtkWidget *window=gtk_window_new();
	gtk_window_set_child(GTK_WINDOW(window),box);
	gtk_window_present(GTK_WINDOW(window));
	for(int i=0;i<20;i++) { while(g_main_context_iteration(NULL,FALSE)); g_usleep(10000); }
	int base_rows[64], spaced_rows[64];
	CHECK(widget_ink_bands(box,base_rows)==3);
	atl_line_box_set_spacing(ATL_LINE_BOX(box),10,1);
	for(int i=0;i<20;i++) { while(g_main_context_iteration(NULL,FALSE)); g_usleep(10000); }
	CHECK(widget_ink_bands(box,spaced_rows)==3);
	for(int i=0;i<3;i++) CHECK(abs(spaced_rows[i]-base_rows[i]-i*10)<=1);
	gtk_window_destroy(GTK_WINDOW(window));
	g_object_unref(box);
	printf("TEXT_LINE_GEOMETRY PASS checks=%d\n",checks);
	return 0;
}
