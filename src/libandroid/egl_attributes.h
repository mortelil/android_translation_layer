/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_EGL_ATTRIBUTES_H
#define ATL_EGL_ATTRIBUTES_H
#include <EGL/egl.h>
#include <glib.h>

/* Wayland's emulated pbuffer consumes dimensions itself. The returned EGL
 * attribute list owns correctly sized storage, including its terminator. */
static inline EGLint *atl_pbuffer_window_attributes(const EGLint *attrs, EGLint *width, EGLint *height)
{
	*width = *height = 0;
	if (!attrs) return NULL;
	size_t count = 0;
	while (attrs[count] != EGL_NONE) count += 2;
	EGLint *result = g_new(EGLint, count + 1);
	size_t out = 0;
	for (size_t i = 0; i < count; i += 2) {
		if (attrs[i] == EGL_WIDTH) *width = attrs[i + 1];
		else if (attrs[i] == EGL_HEIGHT) *height = attrs[i + 1];
		else { result[out++] = attrs[i]; result[out++] = attrs[i + 1]; }
	}
	result[out] = EGL_NONE;
	return result;
}
#endif
