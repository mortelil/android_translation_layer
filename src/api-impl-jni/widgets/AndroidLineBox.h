/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <gtk/gtk.h>
#define ATL_TYPE_LINE_BOX (atl_line_box_get_type())
G_DECLARE_FINAL_TYPE(AtlLineBox, atl_line_box, ATL, LINE_BOX, GtkWidget)
GtkWidget *atl_line_box_new(GtkWidget *label);
void atl_line_box_set_spacing(AtlLineBox *box, float add, float mult);
GtkWidget *atl_line_box_get_label(AtlLineBox *box);
