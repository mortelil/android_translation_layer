/* SPDX-License-Identifier: GPL-3.0-only */
#include "egl_readback.h"

/* Internal GL work must not consume errors belonging to the app. Contexts can
 * migrate between threads, so retain errors by context, not by thread. */
static GMutex errors_mutex;
static GHashTable *errors;
static void preserve_error(EGLContext context, GLenum error)
{
	g_mutex_lock(&errors_mutex);
	if (!errors) errors = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, (GDestroyNotify)g_array_unref);
	GArray *list = g_hash_table_lookup(errors, context);
	if (!list) {
		list = g_array_new(FALSE, FALSE, sizeof(GLenum));
		g_hash_table_insert(errors, context, list);
	}
	bool found = false;
	for (guint i = 0; i < list->len; i++) found |= g_array_index(list, GLenum, i) == error;
	if (!found) g_array_append_val(list, error);
	g_mutex_unlock(&errors_mutex);
}

GLenum bionic_glGetError(void)
{
	EGLContext context = eglGetCurrentContext();
	GLenum error = GL_NO_ERROR;
	g_mutex_lock(&errors_mutex);
	GArray *list = errors ? g_hash_table_lookup(errors, context) : NULL;
	if (list && list->len) {
		error = g_array_index(list, GLenum, 0);
		g_array_remove_index(list, 0);
	}
	g_mutex_unlock(&errors_mutex);
	return error != GL_NO_ERROR ? error : glGetError();
}

void atl_gl_forget_errors(EGLContext context)
{
	g_mutex_lock(&errors_mutex);
	if (errors) g_hash_table_remove(errors, context);
	g_mutex_unlock(&errors_mutex);
}

void atl_gl_preserve_context_loss(void)
{
	preserve_error(eglGetCurrentContext(), GL_CONTEXT_LOST);
}

bool atl_gl_preserve_pending_errors(void)
{
	EGLContext context = eglGetCurrentContext();
	if (context == EGL_NO_CONTEXT) return false;
	for (unsigned i = 0; i < 16; i++) {
		GLenum error = glGetError();
		if (error == GL_NO_ERROR) return true;
		preserve_error(context, error);
		if (error == GL_CONTEXT_LOST) return false;
	}
	return false;
}

GBytes *atl_gl_read_rgba(GLuint framebuffer, int width, int height)
{
	EGLContext context = eglGetCurrentContext();
	if (context == EGL_NO_CONTEXT || width <= 0 || height <= 0 ||
	    (size_t)width > G_MAXSIZE / 4 / (size_t)height)
		return NULL;
	size_t size = (size_t)width * height * 4;
	guchar *pixels = g_try_malloc(size);
	if (!pixels) return NULL;
	/* A lost context can repeat its error indefinitely. Do not spin on it. */
	if (!atl_gl_preserve_pending_errors()) {g_free(pixels);return NULL;}
	EGLint version = 2;
	eglQueryContext(eglGetCurrentDisplay(), context, EGL_CONTEXT_CLIENT_VERSION, &version);
	GLint alignment, binding, pbo=0, row=0, skip_rows=0, skip_pixels=0, read_buffer=0;
	glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
	GLenum target = version >= 3 ? GL_READ_FRAMEBUFFER : GL_FRAMEBUFFER;
	glGetIntegerv(version >= 3 ? GL_READ_FRAMEBUFFER_BINDING : GL_FRAMEBUFFER_BINDING, &binding);
	if (version >= 3) {
		glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
		glGetIntegerv(GL_PACK_ROW_LENGTH, &row);
		glGetIntegerv(GL_PACK_SKIP_ROWS, &skip_rows);
		glGetIntegerv(GL_PACK_SKIP_PIXELS, &skip_pixels);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		glPixelStorei(GL_PACK_ROW_LENGTH, 0);
		glPixelStorei(GL_PACK_SKIP_ROWS, 0);
		glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
	}
	glPixelStorei(GL_PACK_ALIGNMENT, 1);
	glBindFramebuffer(target, framebuffer);
	if (version >= 3) {
		glGetIntegerv(GL_READ_BUFFER, &read_buffer);
		glReadBuffer(framebuffer ? GL_COLOR_ATTACHMENT0 : GL_BACK);
	}
	bool complete = glCheckFramebufferStatus(target) == GL_FRAMEBUFFER_COMPLETE;
	if (complete) glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
	GLenum error = glGetError();
	if (version >= 3) glReadBuffer(read_buffer);
	glBindFramebuffer(target, binding);
	glPixelStorei(GL_PACK_ALIGNMENT, alignment);
	if (version >= 3) {
		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
		glPixelStorei(GL_PACK_ROW_LENGTH, row);
		glPixelStorei(GL_PACK_SKIP_ROWS, skip_rows);
		glPixelStorei(GL_PACK_SKIP_PIXELS, skip_pixels);
	}
	if (!complete || error != GL_NO_ERROR) {g_free(pixels);return NULL;}
	return g_bytes_new_take(pixels, size);
}
