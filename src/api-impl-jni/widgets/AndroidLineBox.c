/* SPDX-License-Identifier: GPL-3.0-only */
#include "AndroidLineBox.h"
#include "../text/AndroidLineGeometry.h"

/* Keep GtkLabel's text, attributes, font context and wrapping machinery.
 * For custom Android spacing the container owns measurement and snapshot;
 * it does not ask GtkLabel to render an incompatible line-height model.
 * Default spacing delegates unchanged to GtkLabel. Native selection/link
 * interaction is not enabled by ATL's TextView and is outside this adapter.
 */
struct _AtlLineBox {
	GtkWidget parent_instance;
	GtkWidget *label;
	float add, mult;
};
G_DEFINE_TYPE(AtlLineBox, atl_line_box, GTK_TYPE_WIDGET)

static gboolean custom(AtlLineBox *box) { return box->add != 0 || box->mult != 1; }

static PangoLayout *layout_for_width(AtlLineBox *box, int width)
{
	PangoLayout *layout = pango_layout_copy(gtk_label_get_layout(GTK_LABEL(box->label)));
	pango_layout_set_width(layout, width < 0 ? -1 : width * PANGO_SCALE);
	atl_layout_set_spacing(layout, box->add, box->mult);
	return layout;
}

static GtkSizeRequestMode request_mode(GtkWidget *widget)
{
	return GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH;
}

static void measure(GtkWidget *widget, GtkOrientation orientation, int for_size,
                    int *minimum, int *natural, int *min_baseline, int *nat_baseline)
{
	AtlLineBox *box = ATL_LINE_BOX(widget);
	if (!custom(box) || orientation == GTK_ORIENTATION_HORIZONTAL) {
		gtk_widget_measure(box->label, orientation, for_size, minimum, natural, min_baseline, nat_baseline);
		return;
	}
	PangoLayout *layout = layout_for_width(box, for_size);
	const AtlLineGeometry *g = atl_layout_geometry(layout);
	*minimum = *natural = MAX(0, g->height);
	*min_baseline = *nat_baseline = g_array_index(g->lines, AtlTextLine, 0).baseline;
	g_object_unref(layout);
}

static void allocate(GtkWidget *widget, int width, int height, int baseline)
{
	AtlLineBox *box = ATL_LINE_BOX(widget);
	gtk_widget_allocate(box->label, width, height, baseline, NULL);
}

static void snapshot(GtkWidget *widget, GtkSnapshot *snapshot)
{
	AtlLineBox *box = ATL_LINE_BOX(widget);
	if (!custom(box)) {
		gtk_widget_snapshot_child(widget, box->label, snapshot);
		return;
	}
	PangoLayout *layout = layout_for_width(box, gtk_widget_get_width(widget));
	GdkRGBA color;
	gtk_widget_get_color(box->label, &color);
	/* The label owns its own Pango attributes and has no Android Paint to synthesise from. */
	const AtlTextStyle style = { .color = color, .paint = NULL };
	atl_layout_snapshot(snapshot, layout, &style);
	g_object_unref(layout);
}

static void dispose(GObject *object)
{
	AtlLineBox *box = ATL_LINE_BOX(object);
	if (box->label) { gtk_widget_unparent(box->label); box->label = NULL; }
	G_OBJECT_CLASS(atl_line_box_parent_class)->dispose(object);
}

static void atl_line_box_class_init(AtlLineBoxClass *klass)
{
	G_OBJECT_CLASS(klass)->dispose = dispose;
	GtkWidgetClass *widget = GTK_WIDGET_CLASS(klass);
	widget->get_request_mode = request_mode;
	widget->measure = measure;
	widget->size_allocate = allocate;
	widget->snapshot = snapshot;
	gtk_widget_class_set_css_name(widget, "atl-line-box");
}

static void atl_line_box_init(AtlLineBox *box) { box->mult = 1; }
GtkWidget *atl_line_box_new(GtkWidget *label)
{
	AtlLineBox *box = g_object_new(ATL_TYPE_LINE_BOX, NULL);
	box->label = label;
	gtk_widget_set_parent(label, GTK_WIDGET(box));
	return GTK_WIDGET(box);
}

GtkWidget *atl_line_box_get_label(AtlLineBox *box) { return box->label; }

/* Android folds the paragraph alignment against the paragraph direction
 * (Layout#getLineLeft/getLineRight, AOSP android-17.0.0_r1
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d, core/java/android/text/Layout.java:1967-2055:
 * ALIGN_NORMAL -> (dir == RTL ? RIGHT : LEFT), ALIGN_OPPOSITE -> the inverse). Every paragraph
 * this build lays out is left-to-right (Layout#getParagraphDirection), so ALIGN_NORMAL is the
 * left edge and ALIGN_OPPOSITE the right one. GtkLabel has no `text-align` (GTK rejects it as an
 * unknown property); it aligns the lines it wraps with `justify` and places a text block narrower
 * than its allocation with `xalign`, so both are derived here from the resolved alignment. */
void atl_line_box_set_alignment(AtlLineBox *box, int alignment)
{
	GtkJustification justify = GTK_JUSTIFY_LEFT;
	float xalign = 0.f;
	switch (alignment) {
	case ATL_ALIGN_OPPOSITE:
	case ATL_ALIGN_RIGHT:
		justify = GTK_JUSTIFY_RIGHT;
		xalign = 1.f;
		break;
	case ATL_ALIGN_CENTER:
		justify = GTK_JUSTIFY_CENTER;
		xalign = 0.5f;
		break;
	default: /* ATL_ALIGN_NORMAL, ATL_ALIGN_LEFT */
		break;
	}
	if (gtk_label_get_justify(GTK_LABEL(box->label)) == justify &&
	    gtk_label_get_xalign(GTK_LABEL(box->label)) == xalign)
		return;
	gtk_label_set_justify(GTK_LABEL(box->label), justify);
	gtk_label_set_xalign(GTK_LABEL(box->label), xalign);
	gtk_widget_queue_resize(GTK_WIDGET(box));
	gtk_widget_queue_draw(GTK_WIDGET(box));
}
void atl_line_box_set_spacing(AtlLineBox *box, float add, float mult)
{
	if (box->add == add && box->mult == mult) return;
	box->add = add;
	box->mult = mult;
	gtk_widget_queue_resize(GTK_WIDGET(box));
	gtk_widget_queue_draw(GTK_WIDGET(box));
}
