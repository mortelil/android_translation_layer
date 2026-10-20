/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <jni.h>
#include <pango/pango.h>
#include <string.h>
#include <math.h>

static char *atl_text_to_utf8(JNIEnv *env, jstring text)
{
	const jchar *utf16 = (*env)->GetStringChars(env, text, NULL);
	if (!utf16) return NULL;
	/* Minikin treats an isolated UTF-16 surrogate as U+FFFD rather than rejecting
	 * the whole string (frameworks/minikin, Layout.cpp). Android text can contain
	 * such units, e.g. when an app builds text from untrusted/network data. Pango
	 * requires valid UTF-8, so normalize one invalid code unit at a time while
	 * preserving valid supplementary characters and UTF-16 offsets. */
	jsize length = (*env)->GetStringLength(env, text);
	GString *converted = g_string_sized_new((gsize)length * 3);
	for (jsize i = 0; i < length; i++) {
		gunichar codepoint = utf16[i];
		if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
			if (i + 1 < length && utf16[i + 1] >= 0xdc00 && utf16[i + 1] <= 0xdfff) {
				codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (utf16[++i] - 0xdc00);
			} else {
				codepoint = 0xfffd;
			}
		} else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
			codepoint = 0xfffd;
		}
		g_string_append_unichar(converted, codepoint);
	}
	char *utf8 = g_string_free(converted, FALSE);
	(*env)->ReleaseStringChars(env, text, utf16);
	return utf8;
}

static void atl_text_attributes_apply(JNIEnv *env, PangoAttrList *attrs, jintArray encoded)
{
	jsize count = (*env)->GetArrayLength(env, encoded);
	jint *values = (*env)->GetIntArrayElements(env, encoded, NULL);
	if (!values) return;
	for (jsize i = 0; i + 3 < count; i += 4) {
		PangoAttribute *attr = NULL, *alpha = NULL;
		if (values[i + 2] == 1) {
			guint32 color = values[i + 3];
			attr = pango_attr_foreground_new(((color >> 16) & 255) * 257, ((color >> 8) & 255) * 257, (color & 255) * 257);
			alpha = pango_attr_foreground_alpha_new((color >> 24) * 257);
		} else if (values[i + 2] == 2) {
			float size;
			memcpy(&size, &values[i + 3], sizeof(size));
			if (isfinite(size) && size >= 0 && size <= G_MAXINT / (float)PANGO_SCALE)
				attr = pango_attr_size_new_absolute(size * PANGO_SCALE);
		}
		if (attr) {
			attr->start_index = values[i]; attr->end_index = values[i + 1];
			pango_attr_list_change(attrs, attr);
		}
		if (alpha) {
			alpha->start_index = values[i]; alpha->end_index = values[i + 1];
			pango_attr_list_change(attrs, alpha);
		}
	}
	(*env)->ReleaseIntArrayElements(env, encoded, values, JNI_ABORT);
}
