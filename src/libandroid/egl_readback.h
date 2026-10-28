/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_EGL_READBACK_H
#define ATL_EGL_READBACK_H
#include <glib.h>
#include <stdbool.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#ifndef GL_CONTEXT_LOST
#define GL_CONTEXT_LOST 0x0507
#endif
GBytes *atl_gl_read_rgba(GLuint framebuffer, int width, int height);
GLenum bionic_glGetError(void);
void atl_gl_forget_errors(EGLContext context);
bool atl_gl_preserve_pending_errors(void);
void atl_gl_preserve_context_loss(void);
EGLContext bionic_eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
EGLBoolean bionic_eglDestroyContext(EGLDisplay, EGLContext);
#endif
