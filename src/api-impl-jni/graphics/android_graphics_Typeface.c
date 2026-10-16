// SPDX-License-Identifier: GPL-3.0-only
#include <fontconfig/fontconfig.h>
#include <fontconfig/fcfreetype.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_TRUETYPE_TABLES_H
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
	/* The family the typeface asks for resolved against the config Pango will resolve it with, so a
	 * generic Android family reaches Pango as the face Android's font config gives it. */
	pango_font_description_set_family(font->description,
	                                  atl_resolve_android_family(FcConfigGetCurrent(), name));
	pango_font_description_set_weight(font->description, weight);
	pango_font_description_set_style(font->description, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
	(*env)->ReleaseStringUTFChars(env, family, name);
	return _INTPTR(font);
}

static void close_font_file(gpointer data) { close(GPOINTER_TO_INT(data) - 1); }

/* minikin's FontFamily reads a font's coverage from the raw font bytes' "cmap" table
 * (FontFamily.cpp, rev 915f1232: HbBlob(font->baseFont(), "cmap")); a missing table means the Font
 * covers nothing and itemization moves on to the next family. A raw non-sfnt bitmap face (PCF/BDF)
 * has no such table, but fontconfig's own charset - which FcFreeTypeQuery derives from the bitmap
 * encoding - would otherwise let the strike claim the text. Mirror the coverage rule on the
 * registered pattern so the per-run Pango/fontconfig resolution decides for every font, exactly as
 * for the scalable faces. */
static bool atl_pattern_apply_coverage(FcPattern *pattern, FT_Library library, const char *path, int index)
{
	FT_Face face = NULL;
	if (FT_New_Face(library, path, index, &face))
		return true; /* unopenable: keep fontconfig's charset rather than inventing one */
	FT_ULong length = 0;
	bool has_cmap = FT_IS_SFNT(face) &&
	                FT_Load_Sfnt_Table(face, FT_MAKE_TAG('c', 'm', 'a', 'p'), 0, NULL, &length) == 0 &&
	                length > 0;
	FT_Done_Face(face);
	if (has_cmap)
		return true;
	FcCharSet *empty = FcCharSetCreate();
	FcPatternDel(pattern, FC_CHARSET);
	FcPatternAddCharSet(pattern, FC_CHARSET, empty);
	FcCharSetDestroy(empty);
	return false;
}

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
	FT_Library library = NULL;
	struct AndroidTypeface *font = NULL;
	if (!config || !FcConfigAppFontAddFile(config, (const FcChar8 *)retained_path)) goto done;
	FT_Init_FreeType(&library);
	// Give the chosen file/collection face a private family in this map only.
	// This prevents a system font with the same family name winning the match.
	FcFontSet *set = FcConfigGetFonts(config, FcSetApplication);
	bool found = false, covered = true;
	for (int i = 0; set && i < set->nfont; ++i) {
		int face = 0;
		FcPatternGetInteger(set->fonts[i], FC_INDEX, 0, &face);
		if (face != index) continue;
		FcPatternDel(set->fonts[i], FC_FAMILY);
		if (!FcPatternAddString(set->fonts[i], FC_FAMILY, (const FcChar8 *)"ATL private file font")) goto done;
		if (library) covered = atl_pattern_apply_coverage(set->fonts[i], library, retained_path, index);
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
	/* A face whose coverage cannot be read (a raw bitmap font: no sfnt "cmap") contributes no
	 * glyph to the family, so the typeface resolves through the default family - the same chain
	 * a null family name takes (Typeface.create(null, ...)) and the same fallback a custom
	 * typeface without a declared fallback family gets from a minikin FontCollection. Only this
	 * font's own empty coverage selects it; the file, the family name and the text do not. */
	pango_font_description_set_family(font->description, covered ? "ATL private file font"
	                                                            : atl_resolve_android_family(config, "sans-serif"));
	int ot_weight = FcWeightToOpenType(weight);
	pango_font_description_set_weight(font->description, ot_weight > 0 ? ot_weight : 400);
	pango_font_description_set_style(font->description, slant == FC_SLANT_ROMAN ? PANGO_STYLE_NORMAL : PANGO_STYLE_ITALIC);
done:
	if (fd >= 0) close(fd);
	if (library) FT_Done_FreeType(library);
	if (config) FcConfigDestroy(config);
	if (query) FcPatternDestroy(query);
	(*env)->ReleaseStringUTFChars(env, path, filename);
	return _INTPTR(font);
}

/* Adds PATH (collection face INDEX) to CONFIG's application font set and renames only the patterns
 * this call appended, so several chain files can share the same collection index without clobbering
 * each other. Gives each chain file a private family so that no system font can win the match and
 * silently serve a run (the controlled chain is the whole collection). */
static bool atl_config_add_private_font(FcConfig *config, const char *path, int index, const char *family)
{
	FcFontSet *set = FcConfigGetFonts(config, FcSetApplication);
	int before = set ? set->nfont : 0;
	if (!FcConfigAppFontAddFile(config, (const FcChar8 *)path)) return false;
	set = FcConfigGetFonts(config, FcSetApplication);
	if (!set) return false;
	FT_Library library = NULL;
	FT_Init_FreeType(&library);
	bool renamed = false;
	for (int i = before; i < set->nfont; ++i) {
		int face = 0;
		FcPatternGetInteger(set->fonts[i], FC_INDEX, 0, &face);
		if (face != index) continue;
		FcPatternDel(set->fonts[i], FC_FAMILY);
		if (!FcPatternAddString(set->fonts[i], FC_FAMILY, (const FcChar8 *)family)) { if (library) FT_Done_FreeType(library); return false; }
		if (library) atl_pattern_apply_coverage(set->fonts[i], library, path, index);
		renamed = true;
	}
	if (library) FT_Done_FreeType(library);
	return renamed;
}

/* Builds the Typeface of Typeface.CustomFallbackBuilder: a private font collection holding only the
 * declared chain files, in order, under the families "ATL private file font <i>", with the first
 * file's description as the default instance. Pango itemization then resolves each text run to the
 * real FC_FILE/FC_INDEX of the chain file that serves it (Android minikin FontCollection semantics). */
JNIEXPORT jlong JNICALL Java_android_graphics_Typeface_nativeCreateFromFamilies(JNIEnv *env, jclass cls, jobjectArray paths, jintArray indices, jobjectArray variations, jstring fallback, jint weight, jboolean italic)
{
	jsize count = paths ? (*env)->GetArrayLength(env, paths) : 0;
	if (count <= 0) return 0;
	jint *face_indices = indices ? (*env)->GetIntArrayElements(env, indices, NULL) : NULL;
	FcConfig *config = FcConfigCreate();
	struct AndroidTypeface *font = NULL;
	if (!config) goto done;
	for (jsize i = 0; i < count; i++) {
		jstring jpath = (*env)->GetObjectArrayElement(env, paths, i);
		if (!jpath) continue;
		const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
		if (path) {
			char family[64];
			g_snprintf(family, sizeof(family), "ATL private file font %d", (int)i);
			/* A chain entry that fails to register is simply absent; the remaining fonts still
			 * form the chain rather than failing the whole build. */
			atl_config_add_private_font(config, path, face_indices ? face_indices[i] : 0, family);
			(*env)->ReleaseStringUTFChars(env, jpath, path);
		}
		(*env)->DeleteLocalRef(env, jpath);
	}
	font = g_new0(struct AndroidTypeface, 1);
	font->map = pango_cairo_font_map_new_for_font_type(CAIRO_FONT_TYPE_FT);
	if (!font->map) { g_free(font); font = NULL; goto done; }
	pango_fc_font_map_set_config(PANGO_FC_FONT_MAP(font->map), config);
	font->description = pango_font_description_new();
	pango_font_description_set_family(font->description, "ATL private file font 0");
	pango_font_description_set_weight(font->description, weight > 0 ? weight : 400);
	pango_font_description_set_style(font->description, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
	/* The requested style, like each Font's axes, describes the chain's fonts, not a system
	 * fallback (the fallback name is not resolved by ATL's controlled chain). */
	if (variations && count > 0) {
		jstring jvariations = (*env)->GetObjectArrayElement(env, variations, 0);
		if (jvariations) {
			const char *settings = (*env)->GetStringUTFChars(env, jvariations, NULL);
			if (settings && *settings) pango_font_description_set_variations(font->description, settings);
			if (settings) (*env)->ReleaseStringUTFChars(env, jvariations, settings);
			(*env)->DeleteLocalRef(env, jvariations);
		}
	}
done:
	if (face_indices) (*env)->ReleaseIntArrayElements(env, indices, face_indices, JNI_ABORT);
	if (config) FcConfigDestroy(config);
	return font ? _INTPTR(font) : 0;
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
	/* AOSP derives the resolved style weight from the 'wght' axis (clamped to 1..1000) when
	 * variations carry it; otherwise it reads the font tables (here: the Pango weight). */
	int weight = atl_variation_axis_value(pango_font_description_get_variations(font->description), "wght");
	if (weight >= 0) {
		if (weight < 1) weight = 1;
		else if (weight > 1000) weight = 1000;
	} else {
		weight = pango_font_description_get_weight(font->description);
	}
	return weight | (pango_font_description_get_style(font->description) != PANGO_STYLE_NORMAL ? 0x10000 : 0);
}
JNIEXPORT void JNICALL Java_android_graphics_Typeface_nativeRelease(JNIEnv *env, jclass cls, jlong handle)
{
	struct AndroidTypeface *font = _PTR(handle);
	if (!font) return;
	pango_font_description_free(font->description);
	g_clear_object(&font->map);
	g_free(font);
}
