/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include "AndroidPaint.h"
#include <pango/pango.h>

/* Android satisfies a bold or italic request the resolved font family cannot serve by
 * synthesising the style when the text is drawn. Both syntheses belong to the paint, so the
 * drawing code asks them of the paint per layout run.
 *
 * Bold: SkUserConfigManual.h:12,21 defines SK_BUILD_FOR_ANDROID_FRAMEWORK together with
 * SK_USE_FREETYPE_EMBOLDEN, so SkFontHost_FreeType.cpp:1703-1723 calls
 * FT_Outline_Embolden(&glyph->outline, FT_MulFix(face->units_per_EM, face->size->metrics.y_scale)
 * / SK_OUTLINE_EMBOLDEN_DIVISOR) with the divisor 34 on every glyph of an emboldened run. The
 * strength is the run's em divided by 34 and it reaches the outline only: the advances, and with
 * them the layout and the measurement, are untouched. The flag itself is SkFont::setEmbolden(),
 * set by Paint.setFakeBoldText (hwui/jni/Paint.cpp:782) or by minikin's
 * FontFamily::computeFakery when the requested typeface weight is at least 600 and the face that
 * serves the run is at least 200 lighter, in which case the run the layout drew is bold as well.
 *
 * Italic: minikin's fakery is MinikinSkia.cpp:171-178, which turns a fake italic run into
 *     skFont->setSkewX(skFont->getSkewX() - 0.25f)
 * on top of Paint.getTextSkewX() (hwui/jni/Paint.cpp:1018). The shear is positive to the right
 * with y increasing downwards, i.e. a run drawn with a negative skew leans right about its
 * baseline, and the layout's advances are unaffected.
 */
typedef struct {
	/* Strength the run's outlines are dilated by, in the units the snapshot is drawn in
	 * (dp), or 0 to draw the run as the layout shaped it. */
	double embolden;
	/* Shear applied to the run about its baseline, in SkFont::skewX terms. */
	float skew_x;
} AtlTextSynthesis;

/* The synthetic bold/oblique PAINT asks for in the layout run FONT of size SIZE_DP, resolved
 * exactly like the measurement path resolves the run (same font file, same weight source). */
AtlTextSynthesis atl_paint_run_synthesis(struct AndroidPaint *paint, PangoFont *font, double size_dp);
