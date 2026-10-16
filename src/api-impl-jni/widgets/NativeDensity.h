/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <gtk/gtk.h>
#include "../defines.h"

/* GTK theme pixels are logical desktop pixels, but native controls live inside
 * ATL's Android-pixel subtree. Define the fallback control geometry in dp and
 * convert once; leave theme colors and application text styling intact. */
static gboolean atl_density_css_number(const GMatchInfo *match, GString *out, gpointer data)
{
    char *value = g_match_info_fetch(match, 1);
    char number[G_ASCII_DTOSTR_BUF_SIZE];
    g_ascii_dtostr(number, sizeof(number), g_ascii_strtod(value, NULL) * *(double *)data);
    g_string_append_printf(out, "%spx", number);
    g_free(value);
    return FALSE;
}

static void atl_install_native_density_style(JNIEnv *env, GdkDisplay *display)
{
    if (g_object_get_data(G_OBJECT(display), "atl-native-density-style")) return;
    jclass metrics = (*env)->FindClass(env, "android/util/DisplayMetrics");
    double density = (*env)->GetStaticIntField(env, metrics,
        _STATIC_FIELD_ID(metrics, "DENSITY_DEVICE", "I")) / 160.0;
    (*env)->DeleteLocalRef(env, metrics);
    const char *source =
        ".atl-native-check { padding: 0; border-spacing: 4dp; }"
        ".atl-native-check check, .atl-native-check radio { min-width: 16dp; min-height: 16dp; "
        "padding: 0; border-width: 1dp; -gtk-icon-size: 16dp; }"
        ".atl-native-switch { min-width: 36dp; min-height: 20dp; padding: 0; border-width: 1dp; border-radius: 11dp; }"
        ".atl-native-switch slider { min-width: 18dp; min-height: 18dp; padding: 0; margin: 0; "
        "border-width: 1dp; border-radius: 10dp; }"
        ".atl-native-switch image { -gtk-icon-size: 12dp; }"
        ".atl-native-seek { padding: 12dp; }"
        ".atl-native-seek trough, .atl-native-seek highlight { min-width: 4dp; min-height: 4dp; "
        "padding: 0; border-width: 0; border-radius: 2dp; }"
        ".atl-native-seek slider { min-width: 18dp; min-height: 18dp; padding: 0; margin: -8dp; "
        "border-width: 1dp; border-radius: 10dp; }";
    GRegex *pattern = g_regex_new("(-?[0-9]+)dp", 0, 0, NULL);
    char *css = g_regex_replace_eval(pattern, source, -1, 0, 0, atl_density_css_number, &density, NULL);
    g_regex_unref(pattern);
    GtkCssProvider *provider = gtk_css_provider_new();
    gtk_css_provider_load_from_string(provider, css);
    g_free(css);
    gtk_style_context_add_provider_for_display(display, GTK_STYLE_PROVIDER(provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
    g_object_set_data_full(G_OBJECT(display), "atl-native-density-style", provider, g_object_unref);
}
