#include <glib.h>
#include <gsk/gsk.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "AndroidPaint.h"
#include "android_graphics_Paint.h"
#include "AndroidTypeface.h"
#include "AndroidTextAttributes.h"
#include "pango/pango-font.h"
#include "pango/pangofc-font.h"
#include "pango/pangofc-fontmap.h"
#include <fontconfig/fontconfig.h>
#include <hb.h>
#include <hb-ot.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include FT_MODULE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_TRUETYPE_TABLES_H
#include <math.h>
#include <stdint.h>

#include "../generated_headers/android_graphics_Paint.h"

/* android.graphics.Paint.LINEAR_TEXT_FLAG */
#define ATL_LINEAR_TEXT_FLAG (1 << 6)
/* android.graphics.Paint.FAKE_BOLD_TEXT_FLAG */
#define ATL_FAKE_BOLD_TEXT_FLAG (1 << 5)

JNIEXPORT jlong JNICALL Java_android_graphics_Paint_native_1create(JNIEnv *env, jclass clazz)
{
	struct AndroidPaint *paint = g_new0(struct AndroidPaint, 1);
	paint->color.alpha = 1.f;
	paint->gsk_stroke = gsk_stroke_new(1);
	paint->font = pango_font_description_new();
	paint->is_fill = true;
	return _INTPTR(paint);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Paint_native_1clone(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	struct AndroidPaint *clone = g_memdup2(paint, sizeof(struct AndroidPaint));
	clone->gsk_stroke = gsk_stroke_copy(paint->gsk_stroke);
	clone->font = pango_font_description_copy(paint->font);
	if (paint->font_map) clone->font_map = g_object_ref(paint->font_map);
	return _INTPTR(clone);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1recycle(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_free(paint->gsk_stroke);
	pango_font_description_free(paint->font);
	g_clear_object(&paint->font_map);
	g_free(paint);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color(JNIEnv *env, jclass clazz, jlong paint_ptr, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->color.red = ((color >> 16) & 0xFF) / 255.f;
	paint->color.green = ((color >> 8) & 0xFF) / 255.f;
	paint->color.blue = ((color >> 0) & 0xFF) / 255.f;
	paint->color.alpha = ((color >> 24) & 0xFF) / 255.f;
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1color(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return ((int)(paint->color.red * 0xFF) << 16) + ((int)(paint->color.green * 0xFF) << 8) + ((int)(paint->color.blue * 0xFF) << 0) + ((int)(paint->color.alpha * 0xFF) << 24);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1alpha(JNIEnv *env, jclass clazz, jlong paint_ptr, jint alpha)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->color.alpha = (alpha & 0xFF) / 255.f;
}

JNIEXPORT int JNICALL Java_android_graphics_Paint_native_1get_1alpha(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return (int)(paint->color.alpha * 0xFF);
}

#define STYLE_FILL            0
#define STYLE_STROKE          1
#define STYLE_FILL_AND_STROKE 2

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1style(JNIEnv *env, jclass clazz, jlong paint_ptr, jint style)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->is_fill = style == STYLE_FILL || style == STYLE_FILL_AND_STROKE;
	paint->is_stroke = style == STYLE_STROKE || style == STYLE_FILL_AND_STROKE;
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1style(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint->is_fill && paint->is_stroke)
		return STYLE_FILL_AND_STROKE;
	else if (paint->is_fill)
		return STYLE_FILL;
	else
		return STYLE_STROKE;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1width(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat width)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	/* TODO: width of 0 means "single pixel width, no matter what",
	 * meanwile width of 1 should care about scaling;
	 * The problem is, 0 is not a valid value for gsk_stroke_set_line_width,
	 * so at least change it to 1 for now */
	if (width == 0)
		width = 1;
	gsk_stroke_set_line_width(paint->gsk_stroke, width);
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1stroke_1width(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_width(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1cap(JNIEnv *env, jclass clazz, jlong paint_ptr, jint cap)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_set_line_cap(paint->gsk_stroke, cap);
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1stroke_1cap(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_cap(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1join(JNIEnv *env, jclass clazz, jlong paint_ptr, jint join)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_set_line_join(paint->gsk_stroke, join);
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1stroke_1join(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_line_join(paint->gsk_stroke);
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1text_1size(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat size)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	pango_font_description_set_absolute_size(paint->font, roundf(size * PANGO_SCALE));
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1size(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return (jfloat)pango_font_description_get_size(paint->font) / PANGO_SCALE;
}

/* SkFont::setEmbolden(boolean), which hwui's Paint.setFakeBoldText reaches (hwui/jni/Paint.cpp:782)
 * and which a drawn run must carry: this is the flag half of atl_paint_run_synthesis. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1fake_1bold_1text(JNIEnv *env, jclass clazz, jlong paint_ptr, jboolean fake_bold)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->fake_bold = fake_bold;
}

/* SkFont::setSkewX(float), hwui Paint.setTextSkewX (hwui/jni/Paint.cpp:1018); the shear half of
 * atl_paint_run_synthesis, sign included. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1text_1skew_1x(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat skew_x)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->text_skew_x = skew_x;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1filter(JNIEnv *env, jclass clazz, jlong paint_ptr, jint mode, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	/* clang-format off */
	graphene_matrix_init_from_float(&paint->color_matrix, (float[]){
		0, 0, 0, 0,
		0, 0, 0, 0,
		0, 0, 0, 0,
		0, 0, 0, ((color >> 24) & 0xFF) / 255.f,
	});
	/* clang-format on */
	graphene_vec4_init(&paint->color_offset, ((color >> 16) & 0xFF) / 255.f, ((color >> 8) & 0xFF) / 255.f, ((color >> 0) & 0xFF) / 255.f, 0);
	paint->use_color_filter = mode != -1;
}

extern GtkWidget *window;

/* Resolve the font file Pango will use for this paint so the ink box can be measured with
 * FreeType directly. Typeface.createFromFile registers the bundled file in the private
 * FcConfig owned by the typeface's PangoFcFontMap (see android_graphics_Typeface.c), which is
 * why that config - and not the global one - is queried when a map is present. Returns a newly
 * allocated path, or NULL when no font file could be resolved. */
static char *atl_paint_font_file(struct AndroidPaint *paint, int *index)
{
	const char *family = pango_font_description_get_family(paint->font);
	if (!family)
		return NULL;
	FcConfig *config = NULL;
	if (paint->font_map && PANGO_IS_FC_FONT_MAP(paint->font_map))
		config = pango_fc_font_map_get_config(PANGO_FC_FONT_MAP(paint->font_map));
	FcPattern *pattern = FcPatternCreate();
	char *path = NULL;
	if (!pattern)
		return NULL;
	FcPatternAddString(pattern, FC_FAMILY, (const FcChar8 *)family);
	int weight = pango_font_description_get_weight(paint->font);
	if (weight >= 0)
		FcPatternAddInteger(pattern, FC_WEIGHT, FcWeightFromOpenType(weight));
	if (pango_font_description_get_style(paint->font) == PANGO_STYLE_ITALIC)
		FcPatternAddInteger(pattern, FC_SLANT, FC_SLANT_ITALIC);
	FcConfigSubstitute(config, pattern, FcMatchPattern);
	FcDefaultSubstitute(pattern);
	FcResult result;
	FcPattern *match = FcFontMatch(config, pattern, &result);
	if (match) {
		FcChar8 *file = NULL;
		if (FcPatternGetString(match, FC_FILE, 0, &file) == FcResultMatch)
			path = g_strdup((const char *)file);
		FcPatternGetInteger(match, FC_INDEX, 0, index);
		FcPatternDestroy(match);
	}
	FcPatternDestroy(pattern);
	return path;
}

/* Applies the design coordinates a font variation settings string describes to a variable face,
 * mirroring Skia's SkTypeface_FreeType::FaceRec::setupAxes / computeAxisValues
 * (skia 508606366, src/ports/SkFontHost_FreeType.cpp:322,2317): every axis of the face gets a
 * coordinate - its fvar default, overridden by a matching requested axis clamped to [min,max] -
 * and FT_Set_Var_Design_Coordinates is called with the whole array. Returns the number of
 * requested axes that matched a real axis (0 = no variable axis touched). */
static int atl_ft_face_apply_variations(FT_Library library, FT_Face face, const char *variations)
{
	if (!face || !variations || !*variations)
		return 0;
	if (!(face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS))
		return 0;
	FT_MM_Var *mm = NULL;
	if (FT_Get_MM_Var(face, &mm) || !mm)
		return 0;
	FT_Fixed *coords = g_new0(FT_Fixed, mm->num_axis);
	for (FT_UInt a = 0; a < mm->num_axis; a++)
		coords[a] = mm->axis[a].def;
	int matched = 0;
	const char *p = variations;
	while (*p) {
		while (*p == ' ' || *p == '\t' || *p == ',') p++;
		if (!*p) break;
		char tag[4];
		int n = 0;
		if (*p == '\'' || *p == '"') {
			char quote = *p++;
			while (*p && *p != quote && n < 4) tag[n++] = *p++;
			if (*p == quote) p++;
		} else {
			while (*p && *p != '=' && *p != ',' && *p != ' ' && *p != '\t' && n < 4) tag[n++] = *p++;
		}
		while (*p == ' ' || *p == '\t' || *p == '=') p++;
		char *end = NULL;
		double value = strtod(p, &end);
		if (end == p) break; /* malformed remainder */
		p = end;
		if (n != 4) continue;
		FT_ULong want = FT_MAKE_TAG((FT_ULong)(unsigned char)tag[0], (FT_ULong)(unsigned char)tag[1], (FT_ULong)(unsigned char)tag[2], (FT_ULong)(unsigned char)tag[3]);
		for (FT_UInt a = 0; a < mm->num_axis; a++) {
			if (mm->axis[a].tag != want) continue;
			double lo = mm->axis[a].minimum / 65536.0, hi = mm->axis[a].maximum / 65536.0;
			if (value < lo) value = lo;
			if (value > hi) value = hi;
			coords[a] = (FT_Fixed)(value * 65536.0); /* SkScalarToFixed */
			matched++;
			break;
		}
	}
	if (matched)
		FT_Set_Var_Design_Coordinates(face, mm->num_axis, coords);
	FT_Done_MM_Var(library, mm);
	g_free(coords);
	return matched;
}

/* Creates a private FreeType library with the classic TrueType interpreter (version 35) Android's
 * emulator runs, so the hinted advances and ink boxes match. Returns NULL on failure. */
static FT_Library atl_ft_library_new(void)
{
	FT_Library library = NULL;
	if (FT_Init_FreeType(&library)) return NULL;
	FT_UInt interpreter_version = 35; /* classic TrueType interpreter, as on Android */
	FT_Property_Set(library, "truetype", "interpreter-version", &interpreter_version);
	return library;
}

/* The font file (and collection face index) the Pango font of one layout run resolved to, i.e. the
 * file Android's minikin FontCollection would have picked for that run. Returns NULL when the run
 * font is not fontconfig backed; the caller frees the returned path. */
static char *atl_run_font_file(PangoFont *font, int *index_out)
{
	if (!font || !PANGO_IS_FC_FONT(font)) return NULL;
	FcPattern *pattern = pango_fc_font_get_pattern(PANGO_FC_FONT(font));
	FcChar8 *file = NULL;
	if (!pattern || FcPatternGetString(pattern, FC_FILE, 0, &file) != FcResultMatch || !file)
		return NULL;
	int index = 0;
	FcPatternGetInteger(pattern, FC_INDEX, 0, &index);
	if (index_out) *index_out = index;
	return g_strdup((const char *)file);
}

/* Per-call FT face cache for the multi-run measurement path. Every layout run is measured with the
 * face of the file that actually serves it (DejaVu for Latin, the CJK face for 世); the face is
 * reused while consecutive runs resolve to the same file, like minikin's per-run ScalerContext. */
struct atl_face_cache {
	FT_Library library;
	FT_Face face;
	char *path;
	int index;
	/* HarfBuzz view of the same file (same face index), used only to read the pair adjustment
	 * the shaping result carries; see atl_run_hb_font. */
	hb_blob_t *hb_blob;
	hb_face_t *hb_face;
	hb_font_t *hb_font;
};

/* Defined with the pair-term helpers below; declared here because the face cache clears it
 * whenever the cached file changes. */
static void atl_face_cache_clear_hb(struct atl_face_cache *cache);

/* Resolves the run's own font file and (re)opens its face in CACHE, sized for SIZE26. Falls back to
 * the paint's fontconfig match when the run font carries no file (the single-font path). Returns
 * NULL when no face could be opened for the run. */
static FT_Face atl_run_face(struct atl_face_cache *cache, struct AndroidPaint *paint, PangoFont *font, int size26)
{
	int index = -1;
	char *path = atl_run_font_file(font, &index);
	if (!path) {
		path = atl_paint_font_file(paint, &index);
		if (!path) return NULL;
	}
	if (cache->face && cache->path && cache->index == index && strcmp(cache->path, path) == 0) {
		g_free(path);
	} else {
		if (cache->face) { FT_Done_Face(cache->face); cache->face = NULL; }
		atl_face_cache_clear_hb(cache);
		g_free(cache->path);
		cache->path = path;
		cache->index = index;
		if (FT_New_Face(cache->library, path, index, &cache->face)) {
			cache->face = NULL;
		} else {
			/* Skia applies the variation coordinates to the face before sizing it. */
			atl_ft_face_apply_variations(cache->library, cache->face, pango_font_description_get_variations(paint->font));
		}
	}
	if (!cache->face) return NULL;
	FT_Set_Char_Size(cache->face, size26, size26, 72, 72);
	return cache->face;
}

/* Drops the HarfBuzz view of the cached file, if one was built. */
static void atl_face_cache_clear_hb(struct atl_face_cache *cache)
{
	if (cache->hb_font) { hb_font_destroy(cache->hb_font); cache->hb_font = NULL; }
	if (cache->hb_face) { hb_face_destroy(cache->hb_face); cache->hb_face = NULL; }
	if (cache->hb_blob) { hb_blob_destroy(cache->hb_blob); cache->hb_blob = NULL; }
}

/* HarfBuzz view of the run's file, at the scale minikin shapes with: LayoutCore.cpp:397 does
 * hb_font_set_scale(font, HBFloatToFixed(size * scaleX), HBFloatToFixed(size)) - i.e.
 * size * 256, the unit LayoutCore.cpp:519 then divides the shaped positions by - and
 * Font.cpp:255-260 applies the typeface's variation coordinates to it. Built lazily and reused
 * while consecutive runs resolve to the same file. Returns NULL when no font can be built. */
static hb_font_t *atl_run_hb_font(struct atl_face_cache *cache, float size)
{
	if (cache->hb_font) return cache->hb_font;
	if (!cache->face || !cache->path) return NULL;
	cache->hb_blob = hb_blob_create_from_file_or_fail(cache->path);
	if (!cache->hb_blob || cache->hb_blob == hb_blob_get_empty()) {
		atl_face_cache_clear_hb(cache);
		return NULL;
	}
	cache->hb_face = hb_face_create(cache->hb_blob, cache->index);
	cache->hb_font = hb_font_create(cache->hb_face);
	hb_ot_font_set_funcs(cache->hb_font);
	int hb_scale = (int)(size * 256.0f); /* HBFloatToFixed */
	hb_font_set_scale(cache->hb_font, hb_scale, hb_scale);
	/* The coordinates the FT face already carries, so both views describe the same instance. */
	FT_MM_Var *mm = NULL;
	if ((cache->face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS) &&
	    !FT_Get_MM_Var(cache->face, &mm) && mm) {
		FT_Fixed *coords = g_new0(FT_Fixed, mm->num_axis);
		if (!FT_Get_Var_Design_Coordinates(cache->face, mm->num_axis, coords)) {
			hb_variation_t *vars = g_new0(hb_variation_t, mm->num_axis);
			for (FT_UInt a = 0; a < mm->num_axis; a++) {
				vars[a].tag = (hb_tag_t)mm->axis[a].tag;
				vars[a].value = (float)(coords[a] / 65536.0);
			}
			hb_font_set_variations(cache->hb_font, vars, mm->num_axis);
			g_free(vars);
		}
		g_free(coords);
		FT_Done_MM_Var(cache->library, mm);
	}
	return cache->hb_font;
}

/* Pair adjustment (1/256 px) the shaping result carries for each glyph of a run: minikin reads the
 * shaped positions directly (LayoutCore.cpp:518-519), so the adjustment is the difference between
 * the shaped x_advance with and without the "kern" feature at minikin's scale. Returns a newly
 * allocated array of NUM_GLYPHS entries (0 where a glyph carries none), or NULL when the run
 * cannot be shaped (single glyph, shape/reorder mismatch) and the caller must fall back. */
static int *atl_run_pair_kerns(struct atl_face_cache *cache, float size, const char *text, int text_len, PangoGlyphString *glyphs, int **x_offsets, int **y_offsets)
{
	int num_glyphs = glyphs->num_glyphs;
	if (num_glyphs < 1 || !text || text_len <= 0) return NULL;
	hb_font_t *hb = atl_run_hb_font(cache, size);
	if (!hb) return NULL;
	hb_feature_t no_kern;
	bool have_no_kern = hb_feature_from_string("-kern", -1, &no_kern);
	hb_buffer_t *buf = hb_buffer_create();
	int *out = NULL;
	for (int pass = 0; pass < 2; pass++) {
		hb_buffer_clear_contents(buf);
		hb_buffer_add_utf8(buf, text, text_len, 0, text_len);
		hb_buffer_guess_segment_properties(buf);
		if (pass == 1 && have_no_kern)
			hb_shape(hb, buf, &no_kern, 1);
		else
			hb_shape(hb, buf, NULL, 0);
		unsigned int n = 0;
		hb_glyph_position_t *pos = hb_buffer_get_glyph_positions(buf, &n);
		hb_glyph_info_t *info = hb_buffer_get_glyph_infos(buf, NULL);
		bool matching = n == (unsigned int)num_glyphs;
		for (unsigned int i = 0; matching && i < n; i++)
			matching = info[i].codepoint == glyphs->glyphs[i].glyph;
		if (!matching) { /* Do not apply positions to a different glyph sequence. */
			g_free(out);
			out = NULL;
			break;
		}
		if (pass == 0) {
			if (x_offsets && y_offsets) {
				*x_offsets = g_new0(int, n);
				*y_offsets = g_new0(int, n);
				for (unsigned int i = 0; i < n; i++) {
					(*x_offsets)[i] = pos[i].x_offset;
					(*y_offsets)[i] = pos[i].y_offset;
				}
			}
			out = g_new0(int, n);
			for (unsigned int i = 0; i < n; i++) out[i] = pos[i].x_advance;
		} else {
			for (unsigned int i = 0; i < n; i++) out[i] -= pos[i].x_advance;
		}
	}
	hb_buffer_destroy(buf);
	return out;
}

/* Number of UTF-16 code units in the first byte_len bytes of a (modified) UTF-8 string, i.e. the
 * code-unit index a Pango glyph cluster at that byte offset belongs to. */
static int atl_utf16_units(const char *s, int byte_len)
{
	int units = 0;
	for (int i = 0; i < byte_len; ) {
		unsigned char c = (unsigned char)s[i];
		if (c < 0x80) { i += 1; units += 1; }
		else if ((c & 0xE0) == 0xC0) { i += 2; units += 1; }
		else if ((c & 0xF0) == 0xE0) { i += 3; units += 1; }
		else if ((c & 0xF8) == 0xF0) { i += 4; units += 2; } /* surrogate pair */
		else { i += 1; units += 1; }
	}
	return units;
}

/* FT_LOAD_* flags SkScalerContext_FreeType passes for a given SkFontHinting: Paint.setHinting
 * maps HINTING_OFF to kNone and every other value to kNormal (PaintGlue::setHinting), so kNone
 * is the only mode that turns hinting off (and switches Skia to linear metrics). Android's
 * framework build additionally always ORs in the flags below. Shared by the advance and the
 * ink-box paths so both measure one hinting mode. */
static FT_Int32 atl_ft_load_flags(int hinting)
{
	FT_Int32 flags = FT_LOAD_NO_AUTOHINT | FT_LOAD_NO_BITMAP | FT_LOAD_IGNORE_GLOBAL_ADVANCE_WIDTH | FT_LOAD_BITMAP_METRICS_ONLY;
	return flags | (hinting == 0 ? FT_LOAD_NO_HINTING : FT_LOAD_TARGET_NORMAL);
}

/* Skia refuses to hint text that is not axis aligned: SkTypeface_FreeType::onFilterRec
 * (skia 508606366, src/ports/SkFontHost_FreeType.cpp:839) does
 *     if (!isAxisAligned(*rec)) { h = SkFontHinting::kNone; }
 * and isAxisAligned (:713) is false as soon as rec.fPreSkewX != 0. SkScalerContext computes
 * rec.fPreSkewX = font.getSkewX() (SkScalerContext.cpp:1100), i.e. Paint.getTextSkewX(), and the
 * device matrix of these measurements is identity. A skew therefore turns hinting into kNone,
 * which selects the FT_LOAD_NO_HINTING flags above *and* the linear (unhinted) metric source
 * (SkFontHost_FreeType.cpp:962), on top of whatever hinting Paint was set to. */
static int atl_effective_hinting(int hinting, float skew_x)
{
	return skew_x != 0.0f ? 0 : hinting;
}

/* ICU's u_iscntrl(), which minikin's letter spacing uses to skip control code points: the
 * Unicode Cc category, i.e. U+0000..U+001F and U+007F..U+009F. */
static bool atl_is_control_code_point(uint32_t cp)
{
	return cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F);
}

/* minikin LetterSpacingUtils.h (minikin 915f1232): scripts with cursive connection never receive
 * letter spacing - HarfBuzz issue 64 - and only code points of the remaining scripts are called
 * "letter spacing capable", which is what Layout uses to pick the code unit it un-adjusts at the
 * left and right edge of a measured run. */
static bool atl_letter_spacing_capable_script(hb_script_t script)
{
	return !(script == HB_SCRIPT_ARABIC || script == HB_SCRIPT_NKO ||
	         script == HB_SCRIPT_PSALTER_PAHLAVI || script == HB_SCRIPT_MANDAIC ||
	         script == HB_SCRIPT_MONGOLIAN || script == HB_SCRIPT_PHAGS_PA ||
	         script == HB_SCRIPT_DEVANAGARI || script == HB_SCRIPT_BENGALI ||
	         script == HB_SCRIPT_GURMUKHI || script == HB_SCRIPT_MODI ||
	         script == HB_SCRIPT_SHARADA || script == HB_SCRIPT_SYLOTI_NAGRI ||
	         script == HB_SCRIPT_TIRHUTA || script == HB_SCRIPT_OGHAM);
}

static bool atl_letter_spacing_capable_code_point(uint32_t cp)
{
	return atl_letter_spacing_capable_script(hb_unicode_script(hb_unicode_funcs_get_default(), cp));
}

/* The first Unicode code point at byte offset BYTE_OFFSET of the (modified) UTF-8 string S. The
 * letter spacing rules only ever ask for the code point of a glyph's cluster start. */
static uint32_t atl_utf8_code_point(const char *s, int byte_offset)
{
	const unsigned char *p = (const unsigned char *)s + byte_offset;
	if (p[0] < 0x80)
		return p[0];
	if ((p[0] & 0xE0) == 0xC0)
		return ((uint32_t)(p[0] & 0x1F) << 6) | (p[1] & 0x3F);
	if ((p[0] & 0xF0) == 0xE0)
		return ((uint32_t)(p[0] & 0x0F) << 12) | ((uint32_t)(p[1] & 0x3F) << 6) | (p[2] & 0x3F);
	/* Modified UTF-8 encodes a surrogate pair as two three byte sequences (0xED ...): combine it
	 * into the code point HarfBuzz's script lookup expects. */
	if (p[0] == 0xED && p[3] == 0xED) {
		uint32_t hi = 0xD800 | (((uint32_t)(p[0] & 0x0F) << 12) | ((uint32_t)(p[1] & 0x3F) << 6) | (p[2] & 0x3F)) & 0x3FF;
		uint32_t lo = 0xDC00 | (((uint32_t)(p[3] & 0x0F) << 12) | ((uint32_t)(p[4] & 0x3F) << 6) | (p[5] & 0x3F)) & 0x3FF;
		return 0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00);
	}
	if ((p[0] & 0xF8) == 0xF0)
		return ((uint32_t)(p[0] & 0x07) << 18) | ((uint32_t)(p[1] & 0x3F) << 12) | ((uint32_t)(p[2] & 0x3F) << 6) | (p[3] & 0x3F);
	return p[0];
}

/* The HarfBuzz advance of glyph GLYPH_INDEX of GLYPHS in 1/256 px, including the pair kern
 * adjustment HarfBuzz adds to the *first* glyph of a pair. GLYPH_LOADED tells whether that glyph
 * is currently in FACE's slot. PAIR_KERN is the adjustment the shaping result carries for this
 * glyph (0 when there is none); see the comment on get_text_widths below for the derivation. */
static int64_t atl_hb_glyph_advance(FT_Face face, PangoGlyphString *glyphs, int glyph_index, bool glyph_loaded, bool linear, double kern_scale, int pair_kern)
{
	int64_t hb_advance = 0;
	if (glyph_loaded) {
		float advance;
		if (linear)
			advance = (float)(face->glyph->linearHoriAdvance * (1.0f / 65536.0f));
		else
			advance = (float)face->glyph->advance.x / 64.0f;
		hb_advance = (int64_t)scalbnf(advance, 8); /* HBFloatToFixed */
	}
	if (glyph_index + 1 < glyphs->num_glyphs) {
		/* Minikin's advance is the shaped position, which already carries the pair adjustment
		 * (LayoutCore.cpp:518-519); PAIR_KERN is that adjustment for this glyph in 1/256 px.
		 * The legacy kern table is only a fallback for what the shaping result did not report. */
		int64_t legacy = 0;
		if (face->units_per_EM > 0) {
			FT_Vector kern;
			if (!FT_Get_Kerning(face, glyphs->glyphs[glyph_index].glyph, glyphs->glyphs[glyph_index + 1].glyph, FT_KERNING_UNSCALED, &kern))
				legacy = llround((double)kern.x * kern_scale / (double)face->units_per_EM);
		}
		hb_advance += pair_kern != 0 ? pair_kern : legacy;
	}
	return hb_advance;
}

/* The weight the resolved font file really has: minikin's analyzeStyle reads the OS/2 table's
 * usWeightClass (the fallback mirrors FreeType's style flags when the table is missing). */
static int atl_ft_face_weight(FT_Face face)
{
	TT_OS2 *os2 = FT_Get_Sfnt_Table(face, ft_sfnt_os2);
	if (os2 && os2->usWeightClass)
		return os2->usWeightClass;
	return (face->style_flags & FT_STYLE_FLAG_BOLD) ? 700 : 400;
}

/* minikin FontFamily::computeFakery, as both the measurement and the drawing path see it:
 * Paint.isFakeBoldText() (SkFont::setEmbolden, hwui/jni/Paint.cpp:782) always emboldens, and so
 * does a requested typeface weight of at least 600 when the face that serves the run is at least
 * 200 lighter. ACTUAL_WEIGHT is -1 when it is not needed (and when it could not be resolved). */
static bool atl_run_needs_embolden(bool fake_bold, int requested_weight, int actual_weight)
{
	if (fake_bold) return true;
	return requested_weight >= 600 && actual_weight >= 0 && (requested_weight - actual_weight) >= 200;
}

/* The weight and slant of the font file that serves one layout run, read from the file's own
 * tables the way minikin's analyzeStyle reads them. The (file, face index, variations) triple
 * determines the face, so the open - which is as expensive as the whole measurement - is cached
 * for the process: a draw repeats every frame while the tables cannot change underneath it. */
struct atl_face_style {
	int weight;
	bool italic;
};
static GHashTable *atl_face_styles;

/* The weight and slant FACE serves after its variations were applied. minikin's analyzeStyle reads
 * the resolved coordinates: FreeType folds neither an applied wght nor an applied ital/slnt into
 * usWeightClass or style_flags, so a variable face serving bold or italic by axis would otherwise
 * be reported as regular and synthesised a second time. */
static void atl_ft_face_style(FT_Library library, FT_Face face, int *weight, bool *italic)
{
	*weight = atl_ft_face_weight(face);
	*italic = (face->style_flags & FT_STYLE_FLAG_ITALIC) != 0;
	if (!(face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS))
		return;
	FT_MM_Var *mm = NULL;
	if (!library || FT_Get_MM_Var(face, &mm) != 0 || !mm)
		return;
	FT_Fixed *coords = g_new0(FT_Fixed, mm->num_axis);
	if (FT_Get_Var_Design_Coordinates(face, mm->num_axis, coords) == 0) {
		for (FT_UInt i = 0; i < mm->num_axis; i++) {
			FT_ULong tag = mm->axis[i].tag;
			if (tag == FT_MAKE_TAG('w', 'g', 'h', 't') && coords[i] > 0)
				*weight = (int)((coords[i] + 0x8000) >> 16);
			if ((tag == FT_MAKE_TAG('i', 't', 'a', 'l') || tag == FT_MAKE_TAG('s', 'l', 'n', 't')) && coords[i] != 0)
				*italic = true;
		}
	}
	g_free(coords);
	FT_Done_MM_Var(library, mm);
}

static bool atl_run_face_style(struct AndroidPaint *paint, PangoFont *font, int *weight, bool *italic)
{
	int index = -1;
	char *path = atl_run_font_file(font, &index);
	if (!path)
		path = atl_paint_font_file(paint, &index);
	if (!path)
		return false;
	const char *variations = pango_font_description_get_variations(paint->font);
	char *key = g_strdup_printf("%s|%d|%s", path, index, variations ? variations : "");
	struct atl_face_style *style = atl_face_styles ? g_hash_table_lookup(atl_face_styles, key) : NULL;
	if (!style) {
		style = g_new0(struct atl_face_style, 1);
		style->weight = 400;
		FT_Library library = atl_ft_library_new();
		FT_Face face = NULL;
		if (library && !FT_New_Face(library, path, index, &face)) {
			atl_ft_face_apply_variations(library, face, variations);
			atl_ft_face_style(library, face, &style->weight, &style->italic);
			FT_Done_Face(face);
		}
		if (library) FT_Done_FreeType(library);
		if (!atl_face_styles) atl_face_styles = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, g_free);
		g_hash_table_replace(atl_face_styles, key, style);
		key = NULL;
	}
	if (weight) *weight = style->weight;
	if (italic) *italic = style->italic;
	g_free(key);
	g_free(path);
	return true;
}

AtlTextSynthesis atl_paint_run_synthesis(struct AndroidPaint *paint, PangoFont *font, double size_dp)
{
	AtlTextSynthesis synthesis = { 0, 0 };
	if (!paint || size_dp <= 0)
		return synthesis;
	int requested_weight = pango_font_description_get_weight(paint->font);
	bool requested_italic = pango_font_description_get_style(paint->font) != PANGO_STYLE_NORMAL;
	/* The common case: nothing to synthesise and no font file to look at. */
	if (paint->fake_bold || requested_weight >= 600 || requested_italic) {
		int actual_weight = -1;
		bool actual_italic = false;
		if (atl_run_face_style(paint, font, &actual_weight, &actual_italic)) {
			if (atl_run_needs_embolden(paint->fake_bold, requested_weight,
			                           requested_weight >= 600 ? actual_weight : -1))
				synthesis.embolden = size_dp / 34.0;
			/* An italic request the run's own face cannot serve is sheared on top of the
			 * paint's own skew (MinikinSkia.cpp:175). */
			if (requested_italic && !actual_italic)
				synthesis.skew_x -= 0.25f;
		}
	}
	synthesis.skew_x += paint->text_skew_x;
	return synthesis;
}

/* Android's getTextBounds returns the integer ink box of the laid out text: Skia unions, for
 * every laid out glyph, the glyph's outlined control box placed at the pen position, and
 * MinikinUtils/getTextBounds rounds that union out to integers once at the end. Concretely
 * (skia 508606366, minikin 915f1232, hwui 94b4c163):
 *   - SkScalerContext_FreeType::generateMetrics: FT_Load_Glyph with fLoadGlyphFlags, then
 *     emboldenIfNeeded, then FT_Outline_Get_CBox; SkGlyph::rect() = r.roundOut(&r) of that box in
 *     pixels (SkScalerContext::SaturateGlyphBounds), stored as int16 fields.
 *   - MinikinFontSkia::GetBounds returns that integer rect; LayoutPiece::calculateBounds joins the
 *     rects at the fractional HarfBuzz pen positions, and hwui's doTextBounds floors the joined
 *     MinikinRect's min and ceils its max.
 *   - the char size, the load flags and the pen advances are the ones get_text_widths derives
 *     (minikin truncates the size unless LINEAR_TEXT_FLAG is set, positions are 1/256 px, and
 *     hinting kNone additionally switches to the unhinted advances).
 *   - fake bold reaches Skia as SkScalerContext::kEmbolden_Flag, from Paint's
 *     FAKE_BOLD_TEXT_FLAG or from minikin FontFamily::computeFakery (requested typeface weight
 *     minus the weight of the font file that was resolved: at least 200, and requested >= 600);
 *     emboldenIfNeeded then calls FT_Outline_Embolden with FT_MulFix(upem, y_scale) divided by
 *     SK_OUTLINE_EMBOLDEN_DIVISOR, which is 34 in Android's framework build.
 *   - the SUBPIXEL_TEXT_FLAG does not shift glyph bounds (the glyph ids minikin hands to
 *     SkFont::getBounds carry no subpixel component).
 *   - a non-zero Paint.getTextSkewX() makes Skia turn hinting off (atl_effective_hinting) and
 *     SkScalerContext_FreeType::setupSize() shears the loaded outline by -skewX, which is applied
 *     to the outline before the embolden above, so the ink box below is the sheared one.
 *   - letter spacing only moves the pen LayoutPiece places the glyphs at: half a letter space
 *     before the first code unit and a full one (or half next to a control) at every distinct
 *     cluster boundary; getTextBounds passes no run flags, so the edge adjustment that
 *     getTextWidths performs does not apply.
 * The emulator's FreeType runs the classic TrueType interpreter (interpreter-version 35) while
 * the system FreeType defaults to 40, which changes both the hinted advances and the ink boxes,
 * so the measurement is done on a private interp-35 FreeType library/face rather than on the one
 * Pango uses (setting the property on the shared library would change the rendering of all other
 * text and is not reachable there anyway). Text alignment never participates in the result. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1get_1text_1bounds(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text_ptr, jobject bounds, jint flags, jint hinting, jint typeface_weight, jfloat letter_spacing, jfloat skew_x)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text_ptr, NULL);
	pango_layout_set_text(layout, str, -1);

	int left = 0, top = 0, right = 0, bottom = 0;
	float text_size = (float)pango_font_description_get_size(paint->font) / PANGO_SCALE;
	/* MinikinUtils::prepareMinikinPaint: size = isLinearMetrics() ? getSize() : (int)getSize()
	 * (the flag, not the hinting-derived linearity), and SkScalerContext's FT_Set_Char_Size
	 * truncates SkScalarToFDot6(size) = (int)(size * 64). */
	bool flag_linear = (flags & ATL_LINEAR_TEXT_FLAG) != 0;
	/* A skew turns Skia's hinting off (atl_effective_hinting), which also switches the advance
	 * source to the linear one and forces FT_LOAD_NO_HINTING; minikin's *size* however only
	 * follows the Paint flag, so it is derived from flag_linear alone. */
	int eff_hinting = atl_effective_hinting(hinting, skew_x);
	bool linear = flag_linear || eff_hinting == 0;
	float size = flag_linear ? text_size : (float)(int)text_size;
	int size26 = (int)(size * 64.f);
	FT_Library library = size26 > 0 ? atl_ft_library_new() : NULL;
	struct atl_face_cache face_cache = { .library = library };
	/* The Pango fallback below is only for paints whose font file cannot be resolved. A layout
	 * with no runs (empty text) has nothing to resolve per run, so it must still be "measured"
	 * (as an empty box) exactly as the single-face path did before the per-run rework. */
	int meas_index = 0;
	char *meas_file = atl_paint_font_file(paint, &meas_index);
	bool measured = meas_file != NULL;
	g_free(meas_file);

	if (library) {
		FT_Int32 load_flags = atl_ft_load_flags(eff_hinting);
		/* Paint.isFakeBoldText(), or FontFamily::computeFakery(wanted, actual). */
		bool fake_bold = (flags & ATL_FAKE_BOLD_TEXT_FLAG) != 0;
		/* minikin's letter spacing in pixels; ATL exposes no text scaleX (SkFont's default 1). */
		double letter_space = (double)letter_spacing * (double)size;
		double letter_space_half = letter_space * 0.5;
		double pen = 0; /* pen position in px */
		double min_left = 0, min_top = 0, max_right = 0, max_bottom = 0;
		bool have_ink = false;
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			/* Every run is measured with the face that actually serves it, so a fallback run (the
			 * CJK face for 世, say) contributes its own advances and ink box. */
			FT_Face face = atl_run_face(&face_cache, paint, ((PangoGlyphItem *)run->data)->item->analysis.font, size26);
			if (!face) continue;
			measured = true;
			/* Minikin shapes with HarfBuzz and positions are 1/256 px, so the pair kerning is
			 * scaled by 256 * x_ppem (the font's integer pixel size), not by the fractional size. */
			double kern_scale = 256.0 * face->size->metrics.x_ppem;
			bool embolden = false;
			if (fake_bold || typeface_weight >= 600) {
				/* The weight the resolved font instance really has: the requested 'wght' axis when the
				 * description carries variations (minikin's analyzeStyle uses it for the typeface
				 * style, so computeFakery sees it as the actual weight), else the OS/2 table. */
				int actual_weight = -1;
				if (!fake_bold && typeface_weight >= 600) {
					actual_weight = atl_variation_axis_value(pango_font_description_get_variations(paint->font), "wght");
					if (actual_weight < 0) actual_weight = atl_ft_face_weight(face);
				}
				embolden = atl_run_needs_embolden(fake_bold, typeface_weight, actual_weight);
			}
			FT_Pos embolden_strength = embolden ? FT_MulFix(face->units_per_EM, face->size->metrics.y_scale) / 34 : 0;
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			/* PangoGlyphString cluster offsets are relative to the run, so the run's item offset
			 * gives the absolute byte position in the layout text. */
			int run_offset = ((PangoGlyphItem *)run->data)->item->offset;
			/* The pair adjustment the shaping result carries for this run (1/256 px per glyph),
			 * or NULL when there is none to apply. */
			int *x_offsets = NULL, *y_offsets = NULL;
			int *pair_kerns = atl_run_pair_kerns(&face_cache, size, str + run_offset,
			                                     ((PangoGlyphItem *)run->data)->item->length,
			                                     glyphs, &x_offsets, &y_offsets);
			/* Letter spacing is applied per script run and only to letter spacing capable scripts
			 * (a Pango run is one script, so its first code point selects it). */
			double run_ls = 0, run_ls_half = 0;
			if (letter_space != 0.0 && glyphs->num_glyphs > 0 &&
			    atl_letter_spacing_capable_code_point(atl_utf8_code_point(str, run_offset + glyphs->log_clusters[0]))) {
				run_ls = letter_space;
				run_ls_half = letter_space_half;
			}
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				uint32_t cp = atl_utf8_code_point(str, run_offset + glyphs->log_clusters[i]);
				bool ctrl = atl_is_control_code_point(cp);
				/* LayoutCore's shaping loop moves the pen by half a letter space before the first
				 * code unit and by a full one (half when exactly one of the two code points is a
				 * control) at every distinct cluster boundary, and the pen is what places the ink
				 * boxes; getTextBounds passes no run flags, so the edge adjustment of
				 * adjustAdvanceLetterSpacingEdge does not apply here. */
				if (run_ls != 0.0 && i == 0 && !ctrl) {
					pen += run_ls_half;
				} else if (run_ls != 0.0 && i > 0 && glyphs->log_clusters[i] != glyphs->log_clusters[i - 1]) {
					bool prev_ctrl = atl_is_control_code_point(atl_utf8_code_point(str, run_offset + glyphs->log_clusters[i - 1]));
					if (!ctrl && !prev_ctrl)
						pen += run_ls;
					else if (!ctrl || !prev_ctrl)
						pen += run_ls_half;
				}
				FT_UInt glyph = glyphs->glyphs[i].glyph;
				bool loaded = !FT_Load_Glyph(face, glyph, load_flags);
				if (loaded) {
					if (face->glyph->format == FT_GLYPH_FORMAT_OUTLINE) {
						/* SkScalerContext_FreeType::setupSize() hands the residual matrix to
						 * FT_Set_Transform; its xy term is -fMatrix22Scalar.getSkewX()
						 * (SkFontHost_FreeType.cpp:1104-1107), so the loaded outline is already
						 * sheared. Doing it here keeps the embolden that generateMetrics applies
						 * after the load on the sheared outline. */
						if (skew_x != 0.0f) {
							FT_Matrix m;
							m.xx = 0x10000;
							m.xy = (FT_Fixed)(int)(-skew_x * 65536.0f);
							m.yx = 0;
							m.yy = 0x10000;
							FT_Outline_Transform(&face->glyph->outline, &m);
						}
						if (embolden)
							FT_Outline_Embolden(&face->glyph->outline, embolden_strength);
					}
					FT_BBox cbox;
					FT_Outline_Get_CBox(&face->glyph->outline, &cbox);
					/* roundOut() of the box in pixels, y down. */
					int glyph_left = (int)floor(cbox.xMin / 64.0);
					int glyph_top = (int)floor(-cbox.yMax / 64.0);
					int glyph_right = (int)ceil(cbox.xMax / 64.0);
					int glyph_bottom = (int)ceil(-cbox.yMin / 64.0);
					if (glyph_right > glyph_left && glyph_bottom > glyph_top) {
						double glyph_pen = pen + (x_offsets ? x_offsets[i] / 256.0 : 0.0);
						double glyph_y = y_offsets ? -y_offsets[i] / 256.0 : 0.0;
						if (!have_ink || glyph_pen + glyph_left < min_left)
							min_left = glyph_pen + glyph_left;
						if (!have_ink || glyph_pen + glyph_right > max_right)
							max_right = glyph_pen + glyph_right;
						if (!have_ink || glyph_y + glyph_top < min_top)
							min_top = glyph_y + glyph_top;
						if (!have_ink || glyph_y + glyph_bottom > max_bottom)
							max_bottom = glyph_y + glyph_bottom;
						have_ink = true;
					}
				}
				pen += (double)atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale, pair_kerns ? pair_kerns[i] : 0) * (1.0 / 256.0);
			}
			g_free(pair_kerns);
			g_free(x_offsets);
			g_free(y_offsets);
		}
		if (have_ink) {
			left = (int)floor(min_left);
			top = (int)floor(min_top);
			right = (int)ceil(max_right);
			bottom = (int)ceil(max_bottom);
		}
		if (face_cache.face) FT_Done_Face(face_cache.face);
		atl_face_cache_clear_hb(&face_cache);
		g_free(face_cache.path);
		FT_Done_FreeType(library);
	}
	if (!measured) {
		/* No resolvable font file: keep the previous Pango-based approximation. */
		PangoRectangle rect;
		pango_layout_get_pixel_extents(layout, NULL, &rect);
		rect.y -= pango_layout_get_baseline(layout) / PANGO_SCALE;
		left = rect.x;
		top = rect.y;
		right = rect.x + rect.width;
		bottom = rect.y + rect.height;
	}
	_SET_INT_FIELD(bounds, "left", left);
	_SET_INT_FIELD(bounds, "top", top);
	_SET_INT_FIELD(bounds, "right", right);
	_SET_INT_FIELD(bounds, "bottom", bottom);
	(*env)->ReleaseStringUTFChars(env, text_ptr, str);
	g_object_unref(layout);
}

/* Android's Paint.getTextWidths/measureText go through nGetTextAdvances ->
 * MinikinUtils::measureText -> minikin::Layout::measureText, whose per-code-unit advances come
 * from HarfBuzz shaping with Skia's glyph advances (SkScalerContext_FreeType, skia
 * 508606366e71a3daa933a4e08bc49b84e387ec18):
 *   - SkScalerContext_FreeType::generateMetrics: fDoLinearMetrics == false ->
 *     SkFDot6ToFloat(FT advance.x) = advance.x/64 (hinted integer); true ->
 *     SkFixedToScalar(linearHoriAdvance) = linearHoriAdvance/65536 (unhinted subpixel).
 *   - fDoLinearMetrics = SkFont linear-metrics flag (Paint LINEAR_TEXT_FLAG) OR hinting
 *     kNone (Paint.setHinting(HINTING_OFF)); Android's PaintGlue::setHinting maps any non-zero
 *     value to kNormal, and getHinting() returns 0 only for kNone.
 *   - MinikinUtils::prepareMinikinPaint: size = isLinearMetrics() ? getSize() : (int)getSize()
 *     (the *flag*, not the hinting-derived linearity).
 *   - SkScalerContext_FreeType::setupSize: FT_Set_Char_Size(SkScalarToFDot6(size), 72, 72) and
 *     SkScalarToFDot6(x) == (SkFDot6)(x*64) truncates.
 *   - minikin::LayoutCore returns each advance as the HarfBuzz position in 1/256 px:
 *     (int32_t)(advance * 256) (HBFloatToFixed = scalbnf(v,+8), a truncating float->int), and
 *     hb_font_set_scale is HBFloatToFixed(size). A legacy/GPOS kern pair adds
 *     round(kern_units * 256 * x_ppem / upem) to the *first* glyph of the pair (the emulator's
 *     Minikin revision scales kerning by the integer pixel size; calibrated in FONT-004 against
 *     Android's "AV" advances at 16.3/33.4/64.0 and shared with get_text_bounds).
 * Fills widths[0 .. text.length()) and returns the summed advance, which is exactly what
 * measureText()'s ceil() is applied to.
 * Letter spacing (minikin 915f1232, hwui 94b4c163) comes on top: LayoutCore's shaping loop adds
 * half of `paint.letterSpacing * size * scaleX` to the first code unit's advance, half to both
 * sides of every distinct cluster boundary and half to the last code unit (for letter spacing
 * capable scripts only), and Layout::adjustAdvanceLetterSpacingEdge then takes half a letter
 * space off the first and the last non-control capable code unit with a non-zero advance, and
 * off the total, because Paint.getTextWidths measures with the LEFT_EDGE and RIGHT_EDGE run
 * flags. A skew makes Skia disable hinting (atl_effective_hinting), which also selects the
 * linear advance source. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1widths(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text_ptr, jfloatArray widths, jint flags, jint hinting, jfloat letter_spacing, jfloat skew_x)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	float text_size = (float)pango_font_description_get_size(paint->font) / PANGO_SCALE;
	bool flag_linear = (flags & ATL_LINEAR_TEXT_FLAG) != 0;
	int eff_hinting = atl_effective_hinting(hinting, skew_x);
	bool linear = flag_linear || eff_hinting == 0;
	float size = flag_linear ? text_size : (float)(int)text_size;

	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text_ptr, NULL);
	pango_layout_set_text(layout, str, -1);

	int width_count = widths ? (*env)->GetArrayLength(env, widths) : 0;
	float *out = (widths && width_count > 0) ? g_new0(float, width_count) : NULL;

	/* minikin's letter spacing is a pixel amount (MinikinPaint::getLetterSpacingInPx() =
	 * letterSpacing * size * scaleX; ATL exposes no text scaleX, SkFont's default is 1). The
	 * per-code-unit advances are kept in a scratch array because the edge adjustment below needs
	 * them after the shaping loop has added the spacing. */
	int unit_total = atl_utf16_units(str, (int)strlen(str));
	double letter_space = (double)letter_spacing * (double)size;
	bool spacing_on = letter_space != 0.0 && unit_total > 0;
	int units_len = (out && width_count < unit_total) ? width_count : unit_total;
	double *acc = spacing_on ? g_new0(double, unit_total) : NULL;
	uint32_t *unit_cp = spacing_on ? g_new0(uint32_t, unit_total) : NULL;

	int size26 = (int)(size * 64.f);
	FT_Library library = size26 > 0 ? atl_ft_library_new() : NULL;
	struct atl_face_cache face_cache = { .library = library };
	double total = 0;
	if (library) {
		FT_Int32 load_flags = atl_ft_load_flags(eff_hinting);
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			/* Each run is measured with the face that actually serves it (DejaVu for A/B, the CJK
			 * face for 世), so the advances come from the font that run resolved to. */
			FT_Face face = atl_run_face(&face_cache, paint, ((PangoGlyphItem *)run->data)->item->analysis.font, size26);
			if (!face) continue;
			/* Minikin shapes with HarfBuzz and positions are 1/256 px; the pair kerning is scaled
			 * by 256 * x_ppem (the font's integer pixel size), matching get_text_bounds. */
			double kern_scale = 256.0 * face->size->metrics.x_ppem;
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			/* PangoGlyphString cluster offsets are relative to the run, so the run's item offset
			 * gives the absolute byte position in the layout text (and thus the code unit). */
			int run_offset = ((PangoGlyphItem *)run->data)->item->offset;
			/* The pair adjustment the shaping result carries for this run (1/256 px per glyph),
			 * or NULL when there is none to apply. */
			int *pair_kerns = atl_run_pair_kerns(&face_cache, size, str + run_offset,
			                                     ((PangoGlyphItem *)run->data)->item->length,
			                                     glyphs, NULL, NULL);
			/* The shaping loop decides letter spacing per script run; a Pango run is one script,
			 * so its first code point selects it. */
			double run_ls = 0, run_ls_half = 0;
			if (spacing_on && glyphs->num_glyphs > 0 &&
			    atl_letter_spacing_capable_code_point(atl_utf8_code_point(str, run_offset + glyphs->log_clusters[0]))) {
				run_ls = letter_space;
				run_ls_half = letter_space * 0.5;
			}
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				int unit = atl_utf16_units(str, run_offset + glyphs->log_clusters[i]);
				uint32_t cp = atl_utf8_code_point(str, run_offset + glyphs->log_clusters[i]);
				bool ctrl = atl_is_control_code_point(cp);
				if (unit_cp && unit >= 0 && unit < unit_total)
					unit_cp[unit] = cp;
				if (run_ls != 0.0 && i == 0 && !ctrl) {
					/* half a letter space before the first code unit */
					if (unit >= 0 && unit < unit_total)
						acc[unit] += run_ls_half;
				} else if (run_ls != 0.0 && i > 0 && glyphs->log_clusters[i] != glyphs->log_clusters[i - 1]) {
					/* half on both sides of a distinct cluster boundary; the pen (and thus the ink
					 * box) gets a full letter space when neither code point is a control, half when
					 * exactly one is, which get_text_bounds reproduces */
					int prev_unit = atl_utf16_units(str, run_offset + glyphs->log_clusters[i - 1]);
					if (unit >= 0 && unit < unit_total)
						acc[unit] += run_ls_half;
					if (prev_unit >= 0 && prev_unit < unit_total)
						acc[prev_unit] += run_ls_half;
				}
				FT_UInt glyph = glyphs->glyphs[i].glyph;
				bool loaded = !FT_Load_Glyph(face, glyph, load_flags);
				int64_t hb_advance = atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale, pair_kerns ? pair_kerns[i] : 0);
				double advance = (double)hb_advance * (1.0 / 256.0);
				if (acc) {
					if (unit >= 0 && unit < unit_total)
						acc[unit] += advance;
				} else {
					if (out && unit >= 0 && unit < width_count)
						out[unit] += (float)advance;
					total += advance;
				}
			}
			if (run_ls != 0.0 && glyphs->num_glyphs > 0) {
				/* half a letter space on the last code unit (its pen twin follows the ink) */
				int last = glyphs->num_glyphs - 1;
				if (!atl_is_control_code_point(atl_utf8_code_point(str, run_offset + glyphs->log_clusters[last]))) {
					int unit = atl_utf16_units(str, run_offset + glyphs->log_clusters[last]);
					if (unit >= 0 && unit < unit_total)
						acc[unit] += run_ls_half;
				}
			}
			g_free(pair_kerns);
		}
		if (face_cache.face) FT_Done_Face(face_cache.face);
		atl_face_cache_clear_hb(&face_cache);
		g_free(face_cache.path);
		FT_Done_FreeType(library);
	}
	if (acc) {
		/* Layout::adjustAdvanceLetterSpacingEdge with LEFT_EDGE|RIGHT_EDGE (the run flags
		 * Paint.getTextWidths sets): the first and the last code unit that is not a control and
		 * has a non-zero advance lose half a letter space again - and so does the total. */
		int left_unit = -1, right_unit = -1;
		for (int i = 0; i < units_len; i++)
			if (!atl_is_control_code_point(unit_cp[i]) && acc[i] != 0.0) { left_unit = i; break; }
		for (int i = units_len - 1; i >= 0; i--)
			if (!atl_is_control_code_point(unit_cp[i]) && acc[i] != 0.0) { right_unit = i; break; }
		if (left_unit >= 0 && atl_letter_spacing_capable_code_point(unit_cp[left_unit]))
			acc[left_unit] -= letter_space * 0.5;
		if (right_unit >= 0 && atl_letter_spacing_capable_code_point(unit_cp[right_unit]))
			acc[right_unit] -= letter_space * 0.5;
		total = 0;
		for (int i = 0; i < units_len; i++) {
			total += acc[i];
			if (out)
				out[i] = (float)acc[i];
		}
		g_free(acc);
		g_free(unit_cp);
	}
	if (widths && out) {
		(*env)->SetFloatArrayRegion(env, widths, 0, width_count, out);
		g_free(out);
	}
	(*env)->ReleaseStringUTFChars(env, text_ptr, str);
	g_object_unref(layout);
	return (jfloat)total;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1text_1align(JNIEnv *env, jclass clazz, jlong paint_ptr, jint align)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->alignment = align;
}

/* Android (Skia) derives FontMetrics.top/bottom from the font's bounding box (the
 * FreeType head table's yMax/yMin), scaled with the requested text size, whereas
 * ascent/descent/leading come from the hhea/OS-2 tables. Pango's font metrics only
 * expose the latter, so ask the resolved FreeType face of the PangoFcFont for the true
 * glyph extents. Mirrors SkScalerContext_FreeType::generateFontMetrics (skia 5086063):
 *   fTop = -head.yMax / unitsPerEm * size, fBottom = -head.yMin / unitsPerEm * size.
 * Returns FALSE when the resolved font is not FreeType-backed or no absolute text size
 * is set (nothing to scale the unscaled font units with). */
static bool atl_paint_font_bbox(PangoContext *context, struct AndroidPaint *paint, float *top, float *bottom)
{
	int size = pango_font_description_get_size(paint->font);
	if (size <= 0 || !pango_font_description_get_size_is_absolute(paint->font))
		return false;
	PangoFont *font = pango_context_load_font(context, paint->font);
	if (!font)
		return false;
	bool have_extents = false;
	if (PANGO_IS_FC_FONT(font)) {
		FT_Face face = pango_fc_font_lock_face(PANGO_FC_FONT(font));
		if (face && face->units_per_EM > 0) {
			double scale = ((double)size / PANGO_SCALE) / (double)face->units_per_EM;
			*top = (float)(-(double)face->bbox.yMax * scale);
			*bottom = (float)(-(double)face->bbox.yMin * scale);
			have_extents = true;
		}
		if (face)
			pango_fc_font_unlock_face(PANGO_FC_FONT(font));
	}
	g_object_unref(font);
	return have_extents;
}

/* The float FontMetrics exactly as Android exposes them (GraphicsJNI::set_metrics takes
 * the SkFontMetrics fields unchanged). top/bottom come from the font bounding box and
 * fall back to the ascent/descent extents when the face exposes no bbox. */
static void atl_paint_get_font_metrics(PangoContext *context, struct AndroidPaint *paint, PangoFontMetrics *font_metrics, float *top, float *ascent, float *descent, float *bottom, float *leading)
{
	float height = (float)pango_font_metrics_get_height(font_metrics) / PANGO_SCALE;
	*ascent = -(float)pango_font_metrics_get_ascent(font_metrics) / PANGO_SCALE;
	*descent = (float)pango_font_metrics_get_descent(font_metrics) / PANGO_SCALE;
	*top = *ascent;
	*bottom = *descent;
	atl_paint_font_bbox(context, paint, top, bottom);
	*leading = height + *ascent - *descent; /* pango ascent is positive, ours is negative */
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1font_1metrics(JNIEnv *env, jclass clazz, jlong paint_ptr, jobject metrics)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoContext *context = atl_paint_context(paint, gtk_widget_get_pango_context(window));
	PangoFontMetrics *font_metrics = pango_context_get_metrics(context, paint->font, NULL);
	float top, ascent, descent, bottom, leading;
	atl_paint_get_font_metrics(context, paint, font_metrics, &top, &ascent, &descent, &bottom, &leading);
	if (metrics) {
		_SET_FLOAT_FIELD(metrics, "top", top);
		_SET_FLOAT_FIELD(metrics, "ascent", ascent);
		_SET_FLOAT_FIELD(metrics, "descent", descent);
		_SET_FLOAT_FIELD(metrics, "bottom", bottom);
		_SET_FLOAT_FIELD(metrics, "leading", leading);
	}
	pango_font_metrics_unref(font_metrics);
	g_object_unref(context);
	return descent - ascent + leading;
}

JNIEXPORT jint JNICALL Java_android_graphics_Paint_native_1get_1font_1metrics_1int(JNIEnv *env, jclass clazz, jlong paint_ptr, jobject metrics)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoContext *context = atl_paint_context(paint, gtk_widget_get_pango_context(window));
	PangoFontMetrics *font_metrics = pango_context_get_metrics(context, paint->font, NULL);
	float top, ascent, descent, bottom, leading;
	atl_paint_get_font_metrics(context, paint, font_metrics, &top, &ascent, &descent, &bottom, &leading);
	/* GraphicsJNI::set_metrics_int: top/bottom are rounded outward, the others to nearest,
	 * where SkScalarRoundToInt(x) == floor(x + 0.5). */
	int int_top = (int)floorf(top);
	int int_ascent = (int)floorf(ascent + 0.5f);
	int int_descent = (int)floorf(descent + 0.5f);
	int int_bottom = (int)ceilf(bottom);
	int int_leading = (int)floorf(leading + 0.5f);
	if (metrics) {
		_SET_INT_FIELD(metrics, "top", int_top);
		_SET_INT_FIELD(metrics, "ascent", int_ascent);
		_SET_INT_FIELD(metrics, "descent", int_descent);
		_SET_INT_FIELD(metrics, "bottom", int_bottom);
		_SET_INT_FIELD(metrics, "leading", int_leading);
	}
	pango_font_metrics_unref(font_metrics);
	g_object_unref(context);
	return int_descent - int_ascent + int_leading;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1typeface(JNIEnv *env, jclass cls, jlong ptr, jlong typeface)
{
	struct AndroidPaint *paint = _PTR(ptr);
	struct AndroidTypeface *font = _PTR(typeface);
	// Merge only family/style/variation, preserving Paint's current text size.
	pango_font_description_merge(paint->font, font->description, TRUE);
	pango_font_description_set_variations(paint->font, pango_font_description_get_variations(font->description));
	g_set_object(&paint->font_map, font->map);
}

/* Paint.setFontVariationSettings(String). Stores the settings on the paint's font description
 * (the measurement path applies them to the FreeType face) and mirrors AOSP's return value: the
 * settings are filtered against the resolved typeface's axes, and false is returned when none of
 * the requested axes exists on it (frameworks-base 94b4c163,
 * graphics/java/android/graphics/Paint.java:2149). */
JNIEXPORT jboolean JNICALL Java_android_graphics_Paint_native_1set_1font_1variation_1settings(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring settings_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (!settings_ptr) {
		pango_font_description_set_variations(paint->font, NULL);
		return JNI_TRUE;
	}
	const char *settings = (*env)->GetStringUTFChars(env, settings_ptr, NULL);
	if (!settings)
		return JNI_FALSE;
	pango_font_description_set_variations(paint->font, settings);
	/* Resolve the file Pango would use and check the axes against the face. If the face cannot be
	 * inspected (no file, not a variable font) the settings are left in place and accepted. */
	jboolean supported = JNI_TRUE;
	int font_index = 0;
	char *font_path = atl_paint_font_file(paint, &font_index);
	FT_Library library = NULL;
	FT_Face face = NULL;
	if (font_path && !FT_Init_FreeType(&library) && !FT_New_Face(library, font_path, font_index, &face)) {
		FT_MM_Var *mm = NULL;
		if ((face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS) && !FT_Get_MM_Var(face, &mm) && mm) {
			int matched = 0;
			const char *p = settings;
			while (*p) {
				while (*p == ' ' || *p == '\t' || *p == ',') p++;
				if (!*p) break;
				char tag[4];
				int n = 0;
				if (*p == '\'' || *p == '"') {
					char quote = *p++;
					while (*p && *p != quote && n < 4) tag[n++] = *p++;
					if (*p == quote) p++;
				} else {
					while (*p && *p != '=' && *p != ',' && *p != ' ' && *p != '\t' && n < 4) tag[n++] = *p++;
				}
				while (*p == ' ' || *p == '\t' || *p == '=') p++;
				char *end = NULL;
				(void)strtod(p, &end);
				if (end == p) break;
				p = end;
				if (n != 4) continue;
				FT_ULong want = FT_MAKE_TAG((FT_ULong)(unsigned char)tag[0], (FT_ULong)(unsigned char)tag[1], (FT_ULong)(unsigned char)tag[2], (FT_ULong)(unsigned char)tag[3]);
				for (FT_UInt a = 0; a < mm->num_axis; a++)
					if (mm->axis[a].tag == want) { matched++; break; }
			}
			if (matched == 0)
				supported = JNI_FALSE;
			FT_Done_MM_Var(library, mm);
		}
		FT_Done_Face(face);
	}
	if (library)
		FT_Done_FreeType(library);
	g_free(font_path);
	(*env)->ReleaseStringUTFChars(env, settings_ptr, settings);
	return supported;
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1text_1advance(JNIEnv *env, jclass cls, jlong ptr, jstring text)
{
	struct AndroidPaint *paint = _PTR(ptr);
	char *utf8 = atl_text_to_utf8(env, text);
	if (!utf8) return 0;
	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	pango_layout_set_single_paragraph_mode(layout, TRUE);
	pango_layout_set_text(layout, utf8, -1);
	int width;
	pango_layout_get_size(layout, &width, NULL);
	g_object_unref(layout); g_free(utf8);
	return (float)width / PANGO_SCALE;
}
