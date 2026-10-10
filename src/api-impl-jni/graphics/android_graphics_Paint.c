#include <glib.h>
#include <gsk/gsk.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "AndroidPaint.h"
#include "AndroidTypeface.h"
#include "AndroidTextAttributes.h"
#include "pango/pango-font.h"
#include "pango/pangofc-font.h"
#include "pango/pangofc-fontmap.h"
#include <fontconfig/fontconfig.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include FT_MODULE_H
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

/* Opens a private FreeType library/face for the font file Pango resolves for this paint, with
 * the classic TrueType interpreter (version 35) Android's emulator runs, so the hinted advances
 * and ink boxes match. The face is not given a size here; callers set it (Skia sizes with
 * FT_Set_Char_Size(round(size*64), 72, 72)). Returns NULL and *library_out=NULL on failure. */
static FT_Face atl_paint_ft_face(struct AndroidPaint *paint, float text_size, FT_Library *library_out)
{
	int font_index = 0;
	char *font_path = atl_paint_font_file(paint, &font_index);
	FT_Library library = NULL;
	FT_Face face = NULL;
	if (font_path && text_size > 0 && !FT_Init_FreeType(&library)) {
		FT_UInt interpreter_version = 35; /* classic TrueType interpreter, as on Android */
		FT_Property_Set(library, "truetype", "interpreter-version", &interpreter_version);
		if (FT_New_Face(library, font_path, font_index, &face)) {
			FT_Done_FreeType(library);
			library = NULL;
		}
	}
	g_free(font_path);
	*library_out = library;
	return face;
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

/* The HarfBuzz advance of glyph GLYPH_INDEX of GLYPHS in 1/256 px, including the pair kern
 * adjustment HarfBuzz adds to the *first* glyph of a pair. GLYPH_LOADED tells whether that glyph
 * is currently in FACE's slot; see the comment on get_text_widths below for the derivation. */
static int64_t atl_hb_glyph_advance(FT_Face face, PangoGlyphString *glyphs, int glyph_index, bool glyph_loaded, bool linear, double kern_scale)
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
	if (glyph_index + 1 < glyphs->num_glyphs && face->units_per_EM > 0) {
		FT_Vector kern;
		if (!FT_Get_Kerning(face, glyphs->glyphs[glyph_index].glyph, glyphs->glyphs[glyph_index + 1].glyph, FT_KERNING_UNSCALED, &kern))
			hb_advance += llround((double)kern.x * kern_scale / (double)face->units_per_EM);
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
 * The emulator's FreeType runs the classic TrueType interpreter (interpreter-version 35) while
 * the system FreeType defaults to 40, which changes both the hinted advances and the ink boxes,
 * so the measurement is done on a private interp-35 FreeType library/face rather than on the one
 * Pango uses (setting the property on the shared library would change the rendering of all other
 * text and is not reachable there anyway). Text alignment never participates in the result. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1get_1text_1bounds(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text_ptr, jobject bounds, jint flags, jint hinting, jint typeface_weight)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text_ptr, NULL);
	pango_layout_set_text(layout, str, -1);
	(*env)->ReleaseStringUTFChars(env, text_ptr, str);

	int left = 0, top = 0, right = 0, bottom = 0;
	float text_size = (float)pango_font_description_get_size(paint->font) / PANGO_SCALE;
	/* MinikinUtils::prepareMinikinPaint: size = isLinearMetrics() ? getSize() : (int)getSize()
	 * (the flag, not the hinting-derived linearity), and SkScalerContext's FT_Set_Char_Size
	 * truncates SkScalarToFDot6(size) = (int)(size * 64). */
	bool flag_linear = (flags & ATL_LINEAR_TEXT_FLAG) != 0;
	bool linear = flag_linear || hinting == 0;
	float size = flag_linear ? text_size : (float)(int)text_size;
	int size26 = (int)(size * 64.f);
	FT_Library library = NULL;
	FT_Face face = size26 > 0 ? atl_paint_ft_face(paint, text_size, &library) : NULL;

	if (face) {
		FT_Set_Char_Size(face, size26, size26, 72, 72);
		FT_Int32 load_flags = atl_ft_load_flags(hinting);
		/* Minikin shapes with HarfBuzz and positions are 1/256 px, so the pair kerning is
		 * scaled by 256 * x_ppem (the font's integer pixel size), not by the fractional size. */
		double kern_scale = 256.0 * face->size->metrics.x_ppem;
		/* Paint.isFakeBoldText(), or FontFamily::computeFakery(wanted, actual). */
		bool embolden = (flags & ATL_FAKE_BOLD_TEXT_FLAG) != 0;
		if (!embolden && typeface_weight >= 600)
			embolden = (typeface_weight - atl_ft_face_weight(face)) >= 200;
		FT_Pos embolden_strength = embolden ? FT_MulFix(face->units_per_EM, face->size->metrics.y_scale) / 34 : 0;
		double pen = 0; /* pen position in 1/256 px */
		double min_left = 0, min_top = 0, max_right = 0, max_bottom = 0;
		bool have_ink = false;
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				FT_UInt glyph = glyphs->glyphs[i].glyph;
				bool loaded = !FT_Load_Glyph(face, glyph, load_flags);
				if (loaded) {
					if (embolden && face->glyph->format == FT_GLYPH_FORMAT_OUTLINE)
						FT_Outline_Embolden(&face->glyph->outline, embolden_strength);
					FT_BBox cbox;
					FT_Outline_Get_CBox(&face->glyph->outline, &cbox);
					/* roundOut() of the box in pixels, y down. */
					int glyph_left = (int)floor(cbox.xMin / 64.0);
					int glyph_top = (int)floor(-cbox.yMax / 64.0);
					int glyph_right = (int)ceil(cbox.xMax / 64.0);
					int glyph_bottom = (int)ceil(-cbox.yMin / 64.0);
					if (glyph_right > glyph_left && glyph_bottom > glyph_top) {
						double glyph_pen = pen / 256.0;
						if (!have_ink || glyph_pen + glyph_left < min_left)
							min_left = glyph_pen + glyph_left;
						if (!have_ink || glyph_pen + glyph_right > max_right)
							max_right = glyph_pen + glyph_right;
						if (!have_ink || glyph_top < min_top)
							min_top = glyph_top;
						if (!have_ink || glyph_bottom > max_bottom)
							max_bottom = glyph_bottom;
						have_ink = true;
					}
				}
				pen += atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale);
			}
		}
		if (have_ink) {
			left = (int)floor(min_left);
			top = (int)floor(min_top);
			right = (int)ceil(max_right);
			bottom = (int)ceil(max_bottom);
		}
		FT_Done_Face(face);
		FT_Done_FreeType(library);
	} else {
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
 * measureText()'s ceil() is applied to. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1widths(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text_ptr, jfloatArray widths, jint flags, jint hinting)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	float text_size = (float)pango_font_description_get_size(paint->font) / PANGO_SCALE;
	bool flag_linear = (flags & ATL_LINEAR_TEXT_FLAG) != 0;
	bool linear = flag_linear || hinting == 0;
	float size = flag_linear ? text_size : (float)(int)text_size;

	PangoLayout *layout = atl_paint_layout(paint, gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text_ptr, NULL);
	pango_layout_set_text(layout, str, -1);

	int width_count = widths ? (*env)->GetArrayLength(env, widths) : 0;
	float *out = (widths && width_count > 0) ? g_new0(float, width_count) : NULL;

	FT_Library library = NULL;
	FT_Face face = atl_paint_ft_face(paint, size, &library);
	double total = 0;
	if (face) {
		/* FT_Set_Char_Size(SkScalarToFDot6(size)) truncates, unlike roundf(). */
		int size26 = (int)(size * 64.f);
		FT_Set_Char_Size(face, size26, size26, 72, 72);
		/* Minikin shapes with HarfBuzz and positions are 1/256 px; the pair kerning is scaled
		 * by 256 * x_ppem (the font's integer pixel size), matching get_text_bounds. */
		double kern_scale = 256.0 * face->size->metrics.x_ppem;
		FT_Int32 load_flags = atl_ft_load_flags(hinting);
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				FT_UInt glyph = glyphs->glyphs[i].glyph;
				bool loaded = !FT_Load_Glyph(face, glyph, load_flags);
				int64_t hb_advance = atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale);
				if (out) {
					int unit = atl_utf16_units(str, glyphs->log_clusters[i]);
					if (unit >= 0 && unit < width_count)
						out[unit] += (float)(hb_advance * (1.0 / 256.0));
				}
				total += (double)(hb_advance * (1.0 / 256.0));
			}
		}
		FT_Done_Face(face);
		FT_Done_FreeType(library);
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
