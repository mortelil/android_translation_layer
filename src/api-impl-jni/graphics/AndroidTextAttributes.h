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
	char *utf8 = g_utf16_to_utf8(utf16, (*env)->GetStringLength(env, text), NULL, NULL, NULL);
	(*env)->ReleaseStringChars(env, text, utf16);
	if (!utf8)
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Invalid UTF-16 text");
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
