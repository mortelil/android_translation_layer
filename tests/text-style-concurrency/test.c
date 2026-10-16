// SPDX-License-Identifier: GPL-3.0-only
#include <gtk/gtk.h>
#include <pango/pangocairo.h>
#include "../../src/api-impl-jni/graphics/android_graphics_Paint.h"
#include "../../src/api-impl-jni/graphics/AndroidTypeface.h"

static gpointer exercise(gpointer shared_font)
{
    PangoFont *font = g_object_ref(shared_font);
    PangoFontDescription *description = pango_font_description_from_string("DejaVu Sans 20");
    pango_font_description_set_absolute_size(description, 20 * PANGO_SCALE);
    struct AndroidPaint paint = { .font = description, .fake_bold = true, .text_skew_x = -.125f };
    for (int i = 0; i < 200; i++) {
        g_assert_true(atl_font_family_installed(FcConfigGetCurrent(), "DejaVu Sans"));
        AtlTextSynthesis s = atl_paint_run_synthesis(&paint, font, 20);
        g_assert_cmpfloat_with_epsilon(s.embolden, 20.0 / 34.0, .0001);
        g_assert_cmpfloat_with_epsilon(s.skew_x, -.125, .0001);
    }
    g_object_unref(font); pango_font_description_free(description);
    return NULL;
}
int main(void)
{
    FcInit();
    PangoFontMap *map = pango_cairo_font_map_new();
    PangoContext *context = pango_font_map_create_context(map);
    PangoFontDescription *desc = pango_font_description_from_string("DejaVu Sans 20");
    PangoFont *font = pango_context_load_font(context, desc);
    g_assert_nonnull(font);
    GThread *threads[8];
    for (int i = 0; i < 8; i++) threads[i] = g_thread_new("font-test", exercise, font);
    for (int i = 0; i < 8; i++) g_thread_join(threads[i]);
    g_object_unref(font); pango_font_description_free(desc);
    g_object_unref(context); g_object_unref(map);
    puts("TEXT_STYLE_CONCURRENCY PASS threads=8 calls=1600");
}
