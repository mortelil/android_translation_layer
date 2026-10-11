// SPDX-License-Identifier: GPL-3.0-only
#ifndef ATL_ANDROID_TYPEFACE_H
#define ATL_ANDROID_TYPEFACE_H
#include <pango/pango.h>
#include <stdlib.h>
#include <string.h>
struct AndroidTypeface {
	PangoFontDescription *description;
	PangoFontMap *map;
};

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
