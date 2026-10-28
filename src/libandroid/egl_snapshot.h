/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_EGL_SNAPSHOT_H
#define ATL_EGL_SNAPSHOT_H
#include <gtk/gtk.h>
#include <GLES3/gl3.h>
/* Called with the app GL context current. NULL means use CPU readback.
 * Caller preserves its EGL error around this internal operation. */
GdkTexture *atl_egl_snapshot(GLuint framebuffer, int width, int height);
#endif
