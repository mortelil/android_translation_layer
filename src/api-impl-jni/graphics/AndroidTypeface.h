// SPDX-License-Identifier: GPL-3.0-only
#ifndef ATL_ANDROID_TYPEFACE_H
#define ATL_ANDROID_TYPEFACE_H
#include <fontconfig/fontconfig.h>
#include <pango/pango.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
struct AndroidTypeface {
	PangoFontDescription *description;
	PangoFontMap *map;
};

/* Query the supplied configuration directly. Config pointers can be recycled and
 * application fonts can be added later; a process-global pointer-keyed cache is
 * neither a stable identity nor safe for concurrent Typeface creation. */
static inline bool atl_font_family_installed(FcConfig *config, const char *family)
{
	FcPattern *pattern = FcPatternCreate();
	bool installed = false;
	if (pattern) {
		FcPatternAddString(pattern, FC_FAMILY, (const FcChar8 *)family);
		FcConfigSubstitute(config, pattern, FcMatchPattern);
		FcDefaultSubstitute(pattern);
		FcResult result;
		FcPattern *match = FcFontMatch(config, pattern, &result);
		if (match) {
			FcChar8 *matched = NULL;
			if (FcPatternGetString(match, FC_FAMILY, 0, &matched) == FcResultMatch)
				installed = strcasecmp((const char *)matched, family) == 0;
			FcPatternDestroy(match);
		}
		FcPatternDestroy(pattern);
	}
	return installed;
}

/* Android's font config names the concrete face of every generic family - /system/etc/fonts.xml (the
 * file ATL ships as res/fonts.xml) declares "<family name=\"sans-serif\">" with Roboto-Regular.ttf at
 * every weight, so Android's "sans-serif" is Roboto. ATL hands the family name to fontconfig, whose
 * own "sans-serif" generic resolves to its default sans (DejaVu Sans here) - a different face, so
 * every measured advance and every position derived from it differ from Android's. Resolve the
 * Android name to the Android face when that face is installed, and keep the name itself when it is
 * not, so an absent face still resolves exactly as before (through fontconfig's substitution chain,
 * as Android falls back when a family's fonts are missing). Families Android's font config does not
 * name are returned unchanged, and no size or weight is touched. */
static inline const char *atl_resolve_android_family(FcConfig *config, const char *family)
{
	static const struct { const char *android_family, *android_face; } android_faces[] = {
		/* res/fonts.xml:20 <family name="sans-serif"> -> Roboto-Thin/Light/Regular/Medium/Black*/
		{ "sans-serif", "Roboto" },
	};
	if (!family)
		return family;
	for (size_t i = 0; i < G_N_ELEMENTS(android_faces); i++)
		if (strcmp(family, android_faces[i].android_family) == 0)
			return atl_font_family_installed(config, android_faces[i].android_face)
			           ? android_faces[i].android_face : family;
	return family;
}

/* The value of an OpenType axis in a Pango variations string ("tag=value", ", "-separated, tag
 * optionally quoted: "'wght' 700"), or -1 when the axis is absent. Mirrors the tag lookup AOSP's
 * FontFileUtil.analyzeStyle does when it derives the resolved style weight from 'wght'
 * (frameworks-base 94b4c163, fonts/FontFileUtil.java:76). */
static inline int atl_variation_axis_value(const char *variations, const char *axis_tag)
{
	if (!variations) return -1;
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
		if (n == 4 && memcmp(tag, axis_tag, 4) == 0)
			return (int)value;
	}
	return -1;
}
#endif
