/* SPDX-License-Identifier: GPL-3.0-only */
#include "../../src/api-impl-jni/widgets/android_widget_PopupWindow.c"
#include <assert.h>

static void settle(void)
{
    for (int i = 0; i < 50; i++) {
        while (g_main_context_iteration(NULL, false)) {}
        g_usleep(10000);
    }
}

int main(void)
{
    gtk_init();
    GtkWidget *window = gtk_window_new();
    gtk_window_set_decorated(GTK_WINDOW(window), false);
    gtk_window_set_default_size(GTK_WINDOW(window), 400, 600);
    GtkWidget *frame = gtk_fixed_new();
    GtkWidget *frame_child = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_fixed_put(GTK_FIXED(frame), frame_child, 0, 0);
    GtkWidget *anchor = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    GtkWidget *button = gtk_label_new("");
    /* Custom-drawn ATL wrappers can have zero GTK allocation while their
     * Android View occupies 40x30. This is the AntennaPod menu regression. */
    gtk_widget_set_size_request(anchor, 0, 0);
    gtk_box_append(GTK_BOX(anchor), button);
    gtk_fixed_put(GTK_FIXED(frame), anchor, 260, 300);
    gtk_window_set_child(GTK_WINDOW(window), frame);
    gtk_window_present(GTK_WINDOW(window));
    settle();

    jlong ptr = Java_android_widget_PopupWindow_native_1constructor(NULL, NULL);
    GtkWidget *popover = GTK_WIDGET(_PTR(ptr));
    gtk_popover_set_child(GTK_POPOVER(popover), gtk_label_new("fixture"));
    gtk_widget_set_size_request(popover, 120, 80);
    Java_android_widget_PopupWindow_native_1show(NULL, NULL, ptr,
        _INTPTR(button), _INTPTR(frame_child), 40, 30, 0, 0, 5, false, true, false);
    settle();
    GdkPopup *surface = GDK_POPUP(gtk_native_get_surface(GTK_NATIVE(popover)));
    int x = gdk_popup_get_position_x(surface), y = gdk_popup_get_position_y(surface);
    g_print("RIGHT popup: %d,%d size %dx%d\n", x, y,
        gdk_surface_get_width(GDK_SURFACE(surface)), gdk_surface_get_height(GDK_SURFACE(surface)));
    assert(x == 180 && y == 330);

    gtk_popover_popdown(GTK_POPOVER(popover));
    Java_android_widget_PopupWindow_native_1show(NULL, NULL, ptr,
        _INTPTR(frame_child), _INTPTR(frame_child), 400, 600, 10, 20, 5 | 80, true, true, false);
    settle();
    surface = GDK_POPUP(gtk_native_get_surface(GTK_NATIVE(popover)));
    x = gdk_popup_get_position_x(surface); y = gdk_popup_get_position_y(surface);
    g_print("Absolute BOTTOM RIGHT: %d,%d\n", x, y);
    assert(x == 270 && y == 500);
    gtk_widget_unparent(popover);
    g_object_unref(popover);
    gtk_window_destroy(GTK_WINDOW(window));
    g_print("PASS: native popup surface placement and reparenting\n");
}
