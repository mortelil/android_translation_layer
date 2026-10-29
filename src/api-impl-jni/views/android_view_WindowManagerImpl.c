#include <gtk/gtk.h>
#include "../android_view_Window.h"

#include "../defines.h"
#include "../generated_headers/android_view_WindowManagerImpl.h"
#include "gdk/gdk.h"
#include "glib-object.h"

#define FIRST_SUB_WINDOW 1000
#define LAST_SUB_WINDOW  1999

extern GtkWindow *window;

JNIEXPORT void JNICALL Java_android_view_WindowManagerImpl_native_1addView(JNIEnv *env, jclass clazz, jlong widget_ptr, jint type, jint x, jint y, jint width, jint height)
{
	GtkWidget *widget = _PTR(widget_ptr);
	if (type < FIRST_SUB_WINDOW || type > LAST_SUB_WINDOW) {
		// TODO: handle toplevel windows properly
		printf("WARNING: non subwindow types not implemented properly in WindowManagerImpl\n");
	}
	GtkPopover *popover = GTK_POPOVER(g_object_ref(gtk_popover_new()));
	gtk_popover_set_child(popover, gtk_widget_get_parent(widget));
	/* Android supplies the subwindow visuals. GTK's bubble chrome and arrow
	 * must not turn selection handles into decorated tooltips. */
	gtk_popover_set_has_arrow(popover, FALSE);
	gtk_widget_add_css_class(GTK_WIDGET(popover), "atl-subwindow");
	GdkDisplay *display = gtk_widget_get_display(widget);
	if (!g_object_get_data(G_OBJECT(display), "atl-subwindow-css")) {
		GtkCssProvider *css = gtk_css_provider_new();
		gtk_css_provider_load_from_string(css,
			"popover.atl-subwindow > contents { padding: 0; border: none; border-radius: 0; box-shadow: none; background: transparent; }");
		gtk_style_context_add_provider_for_display(display, GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
		g_object_set_data_full(G_OBJECT(display), "atl-subwindow-css", css, g_object_unref);
	}
	gtk_widget_set_halign(GTK_WIDGET(popover), GTK_ALIGN_START);
	gtk_popover_set_position(popover, GTK_POS_BOTTOM);
	printf("::: x=%d, y=%d, width=%d, height=%d\n", x, y, width, height);
	gtk_popover_set_autohide(popover, FALSE);
	gtk_popover_set_pointing_to(popover, &(GdkRectangle){.x = x, .y = y - 1, .width = 1, .height = 1});
	GtkStack *stack = GTK_STACK(atl_window_get_content(window));
	GtkWidget *decor_view = gtk_stack_get_visible_child(stack);
	gtk_widget_insert_before(GTK_WIDGET(popover), decor_view, NULL);
	gtk_popover_present(popover);
	gtk_popover_popup(popover);
	gtk_widget_queue_allocate(decor_view);
}

JNIEXPORT void JNICALL Java_android_view_WindowManagerImpl_native_1updateViewLayout(JNIEnv *env, jclass clazz, jlong widget_ptr, jint x, jint y, jint width, jint height)
{
	GtkPopover *popover = GTK_POPOVER(gtk_widget_get_parent(gtk_widget_get_parent(gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr))))));
	printf("updateViewLayout::: x=%d, y=%d, width=%d, height=%d\n", x, y, width, height);
	gtk_popover_set_pointing_to(popover, &(GdkRectangle){.x = x, .y = y - 1, .width = 1, .height = 1});
}

JNIEXPORT void JNICALL Java_android_view_WindowManagerImpl_native_1removeView(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkPopover *popover = GTK_POPOVER(gtk_widget_get_parent(gtk_widget_get_parent(gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr))))));
	gtk_popover_popdown(popover);
	gtk_widget_unparent(GTK_WIDGET(popover));
	gtk_popover_set_child(popover, NULL);
	g_object_unref(popover);
}
