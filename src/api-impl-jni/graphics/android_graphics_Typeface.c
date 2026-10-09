// SPDX-License-Identifier: GPL-3.0-only
#include <fontconfig/fontconfig.h>
#include <fontconfig/fcfreetype.h>
#include <fcntl.h>
#include <unistd.h>
#include <pango/pangocairo.h>
#include <pango/pangofc-fontmap.h>
#include "../defines.h"
#include "AndroidTypeface.h"
#include "../generated_headers/android_graphics_Typeface.h"

JNIEXPORT jlong JNICALL Java_android_graphics_Typeface_nativeCreate(JNIEnv *env, jclass cls, jstring family, jint weight, jboolean italic)
{
	const char *name = (*env)->GetStringUTFChars(env, family, NULL);
	if (!name) return 0;
	struct AndroidTypeface *font = g_new0(struct AndroidTypeface, 1);
	font->description = pango_font_description_new();
	pango_font_description_set_family(font->description, name);
	pango_font_description_set_weight(font->description, weight);
	pango_font_description_set_style(font->description, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
	(*env)->ReleaseStringUTFChars(env, family, name);
	return _INTPTR(font);
}

static void close_font_file(gpointer data) { close(GPOINTER_TO_INT(data) - 1); }

JNIEXPORT jlong JNICALL Java_android_graphics_Typeface_nativeLoad(JNIEnv *env, jclass cls, jstring path, jint index)
{
	const char *filename = (*env)->GetStringUTFChars(env, path, NULL);
	if (!filename) return 0;
	int fd = open(filename, O_RDONLY | O_CLOEXEC);
	char retained_path[64];
	g_snprintf(retained_path, sizeof(retained_path), "/proc/self/fd/%d", fd);
	int count, weight = FC_WEIGHT_REGULAR, slant = FC_SLANT_ROMAN;
	FcPattern *query = fd >= 0 ? FcFreeTypeQuery((const FcChar8 *)retained_path, index, NULL, &count) : NULL;
	FcConfig *config = query ? FcInitLoadConfigAndFonts() : NULL;
	struct AndroidTypeface *font = NULL;
	if (!config || !FcConfigAppFontAddFile(config, (const FcChar8 *)retained_path)) goto done;
	// Give the chosen file/collection face a private family in this map only.
	// This prevents a system font with the same family name winning the match.
	FcFontSet *set = FcConfigGetFonts(config, FcSetApplication);
	bool found = false;
	for (int i = 0; set && i < set->nfont; ++i) {
		int face = 0;
		FcPatternGetInteger(set->fonts[i], FC_INDEX, 0, &face);
		if (face != index) continue;
		FcPatternDel(set->fonts[i], FC_FAMILY);
		if (!FcPatternAddString(set->fonts[i], FC_FAMILY, (const FcChar8 *)"ATL private file font")) goto done;
		found = true;
	}
	if (!found) goto done;
	FcPatternGetInteger(query, FC_WEIGHT, 0, &weight);
	FcPatternGetInteger(query, FC_SLANT, 0, &slant);
	font = g_new0(struct AndroidTypeface, 1);
	font->map = pango_cairo_font_map_new_for_font_type(CAIRO_FONT_TYPE_FT);
	if (!font->map) { g_free(font); font = NULL; goto done; }
	pango_fc_font_map_set_config(PANGO_FC_FONT_MAP(font->map), config);
	g_object_set_data_full(G_OBJECT(font->map), "atl-font-fd", GINT_TO_POINTER(fd + 1), close_font_file);
	fd = -1;
	font->description = pango_font_description_new();
	pango_font_description_set_family(font->description, "ATL private file font");
	int ot_weight = FcWeightToOpenType(weight);
	pango_font_description_set_weight(font->description, ot_weight > 0 ? ot_weight : 400);
	pango_font_description_set_style(font->description, slant == FC_SLANT_ROMAN ? PANGO_STYLE_NORMAL : PANGO_STYLE_ITALIC);
done:
	if (fd >= 0) close(fd);
	if (config) FcConfigDestroy(config);
	if (query) FcPatternDestroy(query);
	(*env)->ReleaseStringUTFChars(env, path, filename);
	return _INTPTR(font);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Typeface_nativeCopy(JNIEnv *env, jclass cls, jlong handle, jint weight, jint italic, jstring variations)
{
	struct AndroidTypeface *source = _PTR(handle);
	struct AndroidTypeface *font = g_new0(struct AndroidTypeface, 1);
	font->description = pango_font_description_copy(source->description);
	if (source->map) font->map = g_object_ref(source->map);
	if (weight >= 0) pango_font_description_set_weight(font->description, weight);
	if (italic >= 0) pango_font_description_set_style(font->description, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
	if (variations) {
		const char *settings = (*env)->GetStringUTFChars(env, variations, NULL);
		if (!settings) {
			pango_font_description_free(font->description);
			g_clear_object(&font->map);
			g_free(font);
			return 0;
		}
		pango_font_description_set_variations(font->description, settings);
		(*env)->ReleaseStringUTFChars(env, variations, settings);
	}
	return _INTPTR(font);
}
JNIEXPORT jint JNICALL Java_android_graphics_Typeface_nativeStyle(JNIEnv *env, jclass cls, jlong handle)
{
	struct AndroidTypeface *font = _PTR(handle);
	return pango_font_description_get_weight(font->description) | (pango_font_description_get_style(font->description) != PANGO_STYLE_NORMAL ? 0x10000 : 0);
}
JNIEXPORT void JNICALL Java_android_graphics_Typeface_nativeRelease(JNIEnv *env, jclass cls, jlong handle)
{
	struct AndroidTypeface *font = _PTR(handle);
	if (!font) return;
	pango_font_description_free(font->description);
	g_clear_object(&font->map);
	g_free(font);
}
