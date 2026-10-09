#pragma once
#include <gtk/gtk.h>
#include <jni.h>

/* Android content uses density-scaled pixels; GTK windows use logical pixels. */
void atl_window_set_content(JNIEnv *env, GtkWindow *window, GtkWidget *content);
GtkWidget *atl_window_get_content(GtkWindow *window);
