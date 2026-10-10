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
#include <hb.h>
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
	FT_Library library = NULL;
	FT_Face face = size26 > 0 ? atl_paint_ft_face(paint, text_size, &library) : NULL;

	if (face) {
		FT_Set_Char_Size(face, size26, size26, 72, 72);
		FT_Int32 load_flags = atl_ft_load_flags(eff_hinting);
		/* Minikin shapes with HarfBuzz and positions are 1/256 px, so the pair kerning is
		 * scaled by 256 * x_ppem (the font's integer pixel size), not by the fractional size. */
		double kern_scale = 256.0 * face->size->metrics.x_ppem;
		/* Paint.isFakeBoldText(), or FontFamily::computeFakery(wanted, actual). */
		bool embolden = (flags & ATL_FAKE_BOLD_TEXT_FLAG) != 0;
		if (!embolden && typeface_weight >= 600)
			embolden = (typeface_weight - atl_ft_face_weight(face)) >= 200;
		FT_Pos embolden_strength = embolden ? FT_MulFix(face->units_per_EM, face->size->metrics.y_scale) / 34 : 0;
		/* minikin's letter spacing in pixels; ATL exposes no text scaleX (SkFont's default 1). */
		double letter_space = (double)letter_spacing * (double)size;
		double letter_space_half = letter_space * 0.5;
		double pen = 0; /* pen position in px */
		double min_left = 0, min_top = 0, max_right = 0, max_bottom = 0;
		bool have_ink = false;
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			/* Letter spacing is applied per script run and only to letter spacing capable scripts
			 * (a Pango run is one script, so its first code point selects it). */
			double run_ls = 0, run_ls_half = 0;
			if (letter_space != 0.0 && glyphs->num_glyphs > 0 &&
			    atl_letter_spacing_capable_code_point(atl_utf8_code_point(str, glyphs->log_clusters[0]))) {
				run_ls = letter_space;
				run_ls_half = letter_space_half;
			}
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				uint32_t cp = atl_utf8_code_point(str, glyphs->log_clusters[i]);
				bool ctrl = atl_is_control_code_point(cp);
				/* LayoutCore's shaping loop moves the pen by half a letter space before the first
				 * code unit and by a full one (half when exactly one of the two code points is a
				 * control) at every distinct cluster boundary, and the pen is what places the ink
				 * boxes; getTextBounds passes no run flags, so the edge adjustment of
				 * adjustAdvanceLetterSpacingEdge does not apply here. */
				if (run_ls != 0.0 && i == 0 && !ctrl) {
					pen += run_ls_half;
				} else if (run_ls != 0.0 && i > 0 && glyphs->log_clusters[i] != glyphs->log_clusters[i - 1]) {
					bool prev_ctrl = atl_is_control_code_point(atl_utf8_code_point(str, glyphs->log_clusters[i - 1]));
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
						double glyph_pen = pen;
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
				pen += (double)atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale) * (1.0 / 256.0);
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
		FT_Int32 load_flags = atl_ft_load_flags(eff_hinting);
		PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
		for (GSList *run = line ? line->runs : NULL; run; run = run->next) {
			PangoGlyphString *glyphs = ((PangoGlyphItem *)run->data)->glyphs;
			/* The shaping loop decides letter spacing per script run; a Pango run is one script,
			 * so its first code point selects it. */
			double run_ls = 0, run_ls_half = 0;
			if (spacing_on && glyphs->num_glyphs > 0 &&
			    atl_letter_spacing_capable_code_point(atl_utf8_code_point(str, glyphs->log_clusters[0]))) {
				run_ls = letter_space;
				run_ls_half = letter_space * 0.5;
			}
			for (int i = 0; i < glyphs->num_glyphs; i++) {
				int unit = atl_utf16_units(str, glyphs->log_clusters[i]);
				uint32_t cp = atl_utf8_code_point(str, glyphs->log_clusters[i]);
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
					int prev_unit = atl_utf16_units(str, glyphs->log_clusters[i - 1]);
					if (unit >= 0 && unit < unit_total)
						acc[unit] += run_ls_half;
					if (prev_unit >= 0 && prev_unit < unit_total)
						acc[prev_unit] += run_ls_half;
				}
				FT_UInt glyph = glyphs->glyphs[i].glyph;
				bool loaded = !FT_Load_Glyph(face, glyph, load_flags);
				int64_t hb_advance = atl_hb_glyph_advance(face, glyphs, i, loaded, linear, kern_scale);
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
				if (!atl_is_control_code_point(atl_utf8_code_point(str, glyphs->log_clusters[last]))) {
					int unit = atl_utf16_units(str, glyphs->log_clusters[last]);
					if (unit >= 0 && unit < unit_total)
						acc[unit] += run_ls_half;
				}
			}
		}
		FT_Done_Face(face);
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
