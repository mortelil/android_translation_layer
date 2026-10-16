/* SPDX-License-Identifier: GPL-3.0-only */
/* Android's generic family names must reach Pango as the concrete face Android's font config names
 * for them. AOSP's /system/etc/fonts.xml (and ATL's copy of it, res/fonts.xml, installed by
 * meson.build as share/atl/system/etc/fonts.xml) declares
 *     <family name="sans-serif">
 *         <font weight="400" style="normal">Roboto-Regular.ttf</font>
 *         ...
 * so Android's "sans-serif" is Roboto. Fontconfig has generic names of its own and resolves
 * "sans-serif" to its default sans (DejaVu Sans in atl_dev), a different face whose advance differs
 * for every string; handing the Android name straight to Pango therefore changes measured line
 * widths and every derived position. This test asserts the face of the family ATL resolves for
 * "sans-serif" (name, advance and font file), that unrelated family names are left alone, and that a
 * face which is not installed keeps the resolution it had before (fontconfig's own fallback).
 *
 * ATL_FONT_FAMILY_BEFORE compiles the same measurements with the pre-change behaviour (the family
 * name handed to Pango unchanged), which is how the before values below were produced:
 *     cc ... -DATL_FONT_FAMILY_BEFORE ...      -> FAIL, resolved=sans-serif, advance=DejaVu's
 *     cc ...                                   -> TEXT_FONT_FAMILY PASS
 */
#include <fontconfig/fontconfig.h>
#include <pango/pangocairo.h>
#include <pango/pangofc-fontmap.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../src/api-impl-jni/graphics/AndroidTypeface.h"

static int checks;
#define CHECK(expr) do { checks++; if (!(expr)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #expr); exit(1); } } while (0)

/* The Android family name ATL hands to Pango for a typeface family. */
#ifdef ATL_FONT_FAMILY_BEFORE
#define ATL_RESOLVED_FAMILY(config, family) (family)
#else
#define ATL_RESOLVED_FAMILY(config, family) atl_resolve_android_family((config), (family))
#endif

/* The advance Pango measures for FAMILY in MAP at the size TextView#native_setTextSize sets (Android
 * pixels, not points: 16 * PANGO_SCALE for 16dp - see tests/text-alignment). */
static double advance(PangoFontMap *map, const char *family, const char *text)
{
	PangoContext *context = pango_font_map_create_context(map);
	PangoLayout *layout = pango_layout_new(context);
	PangoFontDescription *description = pango_font_description_new();
	pango_font_description_set_family(description, family);
	pango_font_description_set_absolute_size(description, 16 * PANGO_SCALE);
	pango_layout_set_font_description(layout, description);
	pango_layout_set_text(layout, text, -1);
	PangoRectangle ink, logical;
	pango_layout_get_extents(layout, &ink, &logical);
	double width = (double)logical.width / PANGO_SCALE;
	pango_font_description_free(description);
	g_object_unref(layout);
	g_object_unref(context);
	return width;
}

/* The font file FAMILY resolves to in CONFIG, as atl_paint_font_file resolves the paint's face (so
 * the test names a face, not only the family string fontconfig echoes back). */
static char *face_file(FcConfig *config, const char *family)
{
	FcPattern *pattern = FcPatternCreate();
	char *path = NULL;
	if (!pattern)
		return NULL;
	FcPatternAddString(pattern, FC_FAMILY, (const FcChar8 *)family);
	FcPatternAddInteger(pattern, FC_WEIGHT, FC_WEIGHT_REGULAR);
	FcConfigSubstitute(config, pattern, FcMatchPattern);
	FcDefaultSubstitute(pattern);
	FcResult result;
	FcPattern *match = FcFontMatch(config, pattern, &result);
	if (match) {
		FcChar8 *file = NULL;
		if (FcPatternGetString(match, FC_FILE, 0, &file) == FcResultMatch)
			path = strdup((const char *)file);
		FcPatternDestroy(match);
	}
	FcPatternDestroy(pattern);
	return path;
}

int main(void)
{
	static const char *sample = "Handgloves 0123456789";
	FcConfig *config = FcConfigGetCurrent();
	PangoFontMap *map = pango_cairo_font_map_get_default();
	const char *resolved = ATL_RESOLVED_FAMILY(config, "sans-serif");
	double resolved_advance = advance(map, resolved, sample);
	double roboto_advance = advance(map, "Roboto", sample);
	double dejavu_advance = advance(map, "DejaVu Sans", sample);
	double generic_advance = advance(map, "sans-serif", sample);
	char *resolved_file = face_file(config, resolved);
	char *roboto_file = face_file(config, "Roboto");
	char *dejavu_file = face_file(config, "DejaVu Sans");
	printf("Android \"sans-serif\" resolves to: %s\n", resolved);
	printf("advance 16dp \"%s\": resolved=%.2f Roboto=%.2f DejaVuSans=%.2f fontconfig \"sans-serif\"=%.2f\n",
	       sample, resolved_advance, roboto_advance, dejavu_advance, generic_advance);
	printf("face files: resolved=%s Roboto=%s DejaVuSans=%s\n",
	       resolved_file ? resolved_file : "(none)", roboto_file ? roboto_file : "(none)",
	       dejavu_file ? dejavu_file : "(none)");

	CHECK(resolved_file != NULL && roboto_file != NULL && dejavu_file != NULL);
	/* The resolved name is the Android face, and it measures and opens as that face. */
	CHECK(strcmp(resolved, "Roboto") == 0);
	CHECK(resolved_advance == roboto_advance);
	CHECK(resolved_advance != dejavu_advance);
	CHECK(strcmp(resolved_file, roboto_file) == 0);
	CHECK(strcmp(resolved_file, dejavu_file) != 0);
	/* Only the families Android's font config names are mapped. */
	static const char *unchanged[] = {
		"serif", "monospace", "sans-serif-condensed", "Helvetica Neue", "Roboto Condensed",
		"ATL private file font 0",
	};
	for (size_t i = 0; i < sizeof(unchanged) / sizeof(unchanged[0]); i++)
		CHECK(strcmp(ATL_RESOLVED_FAMILY(config, unchanged[i]), unchanged[i]) == 0);

	/* A face that is not installed keeps the name, and therefore fontconfig's own fallback: a config
	 * that holds only DejaVu Sans resolves Android's "sans-serif" to DejaVu Sans, the face the
	 * pre-change resolution produced. */
	FcConfig *private = FcConfigCreate();
	CHECK(private != NULL);
	CHECK(FcConfigAppFontAddFile(private, (const FcChar8 *)dejavu_file));
	PangoFontMap *private_map = pango_cairo_font_map_new_for_font_type(CAIRO_FONT_TYPE_FT);
	CHECK(private_map != NULL);
	pango_fc_font_map_set_config(PANGO_FC_FONT_MAP(private_map), private);
	const char *private_resolved = ATL_RESOLVED_FAMILY(private, "sans-serif");
	double private_advance = advance(private_map, private_resolved, sample);
	printf("without Roboto (DejaVu-only config): %s advance=%.2f\n", private_resolved, private_advance);
	CHECK(strcmp(private_resolved, "sans-serif") == 0);
	CHECK(private_advance == dejavu_advance);
	CHECK(private_advance != roboto_advance);
#ifndef ATL_FONT_FAMILY_BEFORE
	CHECK(atl_font_family_installed(config, "Roboto"));
	CHECK(atl_font_family_installed(private, "DejaVu Sans"));
	CHECK(!atl_font_family_installed(private, "Roboto"));
	CHECK(!atl_font_family_installed(private, "No Such Android Face"));
#endif

	free(resolved_file);
	free(roboto_file);
	free(dejavu_file);
	g_object_unref(private_map);
	FcConfigDestroy(private);
	printf("TEXT_FONT_FAMILY PASS checks=%d\n", checks);
	return 0;
}
