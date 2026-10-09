// SPDX-License-Identifier: GPL-3.0-only
#ifndef ATL_ANDROID_TYPEFACE_H
#define ATL_ANDROID_TYPEFACE_H
#include <pango/pango.h>
struct AndroidTypeface {
	PangoFontDescription *description;
	PangoFontMap *map;
};
#endif
