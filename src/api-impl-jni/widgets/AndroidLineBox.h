/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <gtk/gtk.h>
#define ATL_TYPE_LINE_BOX (atl_line_box_get_type())
G_DECLARE_FINAL_TYPE(AtlLineBox, atl_line_box, ATL, LINE_BOX, GtkWidget)
/* android.text.Layout.Alignment, in declaration order: the value a TextView resolves its
 * android:textAlignment to (TextView.getLayoutAlignment). */
enum {
	ATL_ALIGN_NORMAL,
	ATL_ALIGN_OPPOSITE,
	ATL_ALIGN_CENTER,
	ATL_ALIGN_LEFT,
	ATL_ALIGN_RIGHT,
};

GtkWidget *atl_line_box_new(GtkWidget *label);
void atl_line_box_set_spacing(AtlLineBox *box, float add, float mult);
void atl_line_box_set_alignment(AtlLineBox *box, int alignment);
GtkWidget *atl_line_box_get_label(AtlLineBox *box);
