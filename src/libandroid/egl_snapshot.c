/* SPDX-License-Identifier: GPL-3.0-only */
#include "egl_snapshot.h"
#include "egl_readback.h"
#include <EGL/eglext.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>

/* Export a separate immutable allocation, never the application's reusable
 * back buffer. The dma-buf fds retain storage after EGLImage/GL destruction;
 * GTK's texture owns only those fds, not the surface, widget or GL context. */
struct snapshot {
	int width, height, planes, fourcc;
	EGLuint64KHR modifier;
	int fd[4], stride[4], offset[4];
};

static void snapshot_free(gpointer data)
{
	struct snapshot *s = data;
	for (int i = 0; i < 4; i++) if (s->fd[i] >= 0) close(s->fd[i]);
	g_free(s);
}

static bool extension(const char *list, const char *name)
{
	size_t length = strlen(name);
	for (const char *p = list; p && (p = strstr(p, name)); p += length)
		if ((p == list || p[-1] == ' ') && (!p[length] || p[length] == ' ')) return true;
	return false;
}

static struct snapshot *export_snapshot(GLuint framebuffer, int width, int height)
{
	EGLDisplay display = eglGetCurrentDisplay();
	EGLContext context = eglGetCurrentContext();
	if (context == EGL_NO_CONTEXT || width <= 0 || height <= 0) return NULL;
	const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
	if (!extension(extensions, "EGL_MESA_image_dma_buf_export") ||
	    !extension(extensions, "EGL_KHR_gl_texture_2D_image")) return NULL;
	PFNEGLCREATEIMAGEKHRPROC create = (void *)eglGetProcAddress("eglCreateImageKHR");
	PFNEGLDESTROYIMAGEKHRPROC destroy = (void *)eglGetProcAddress("eglDestroyImageKHR");
	PFNEGLEXPORTDMABUFIMAGEQUERYMESAPROC query = (void *)eglGetProcAddress("eglExportDMABUFImageQueryMESA");
	PFNEGLEXPORTDMABUFIMAGEMESAPROC export = (void *)eglGetProcAddress("eglExportDMABUFImageMESA");
	if (!create || !destroy || !query || !export || !atl_gl_preserve_pending_errors()) return NULL;
	EGLint version = 2;
	eglQueryContext(display, context, EGL_CONTEXT_CLIENT_VERSION, &version);
	GLenum target = version >= 3 ? GL_READ_FRAMEBUFFER : GL_FRAMEBUFFER;
	GLint binding, texture, pbo = 0, read_buffer = 0;
	glGetIntegerv(version >= 3 ? GL_READ_FRAMEBUFFER_BINDING : GL_FRAMEBUFFER_BINDING, &binding);
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
	if (version >= 3) {
		glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &pbo);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
	}
	glBindFramebuffer(target, framebuffer);
	if (version >= 3) {
		glGetIntegerv(GL_READ_BUFFER, &read_buffer);
		glReadBuffer(framebuffer ? GL_COLOR_ATTACHMENT0 : GL_BACK);
	}
	GLuint copy = 0;
	bool complete = glCheckFramebufferStatus(target) == GL_FRAMEBUFFER_COMPLETE;
	if (complete) {
		glGenTextures(1, &copy);
		glBindTexture(GL_TEXTURE_2D, copy);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
		glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
		/* Explicit completion until a supported cross-API fence path exists. */
		glFinish();
	}
	bool failed = false;
	for (unsigned i = 0; i < 16; i++) {
		GLenum error = glGetError();
		if (error == GL_NO_ERROR) break;
		failed = true;
		if (error == GL_CONTEXT_LOST) {atl_gl_preserve_context_loss(); break;}
	}
	if (version >= 3) {
		glReadBuffer(read_buffer);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
	}
	glBindFramebuffer(target, binding);
	glBindTexture(GL_TEXTURE_2D, texture);
	struct snapshot *s = NULL;
	if (complete && !failed) {
		EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
		EGLImageKHR image = create(display, context, EGL_GL_TEXTURE_2D_KHR, (void *)(uintptr_t)copy, attrs);
		if (image != EGL_NO_IMAGE_KHR) {
			s = g_new0(struct snapshot, 1);
			s->width = width; s->height = height;
			for (int i = 0; i < 4; i++) s->fd[i] = -1;
			if (!query(display, image, &s->fourcc, &s->planes, &s->modifier) ||
			    s->planes < 1 || s->planes > 4 ||
			    !export(display, image, s->fd, s->stride, s->offset)) {
				snapshot_free(s); s = NULL;
			}
			if (s) for (int i = 0; i < s->planes; i++) {
				if (s->fd[i] < 0 || fcntl(s->fd[i], F_SETFD, FD_CLOEXEC) < 0) {
					snapshot_free(s); s = NULL; break;
				}
			}
			destroy(display, image);
		}
	}
	glDeleteTextures(1, &copy);
	return s;
}

/* GTK display initialization and import are confined to its main context. */
static GdkTexture *import_snapshot(struct snapshot **snapshot)
{
	struct snapshot *s = *snapshot;
	GdkDisplay *display = gdk_display_get_default();
	if (!display) return NULL;
	GdkDmabufTextureBuilder *b = gdk_dmabuf_texture_builder_new();
	gdk_dmabuf_texture_builder_set_display(b, display);
	gdk_dmabuf_texture_builder_set_width(b, s->width);
	gdk_dmabuf_texture_builder_set_height(b, s->height);
	gdk_dmabuf_texture_builder_set_fourcc(b, s->fourcc);
	gdk_dmabuf_texture_builder_set_modifier(b, s->modifier);
	gdk_dmabuf_texture_builder_set_n_planes(b, s->planes);
	gdk_dmabuf_texture_builder_set_premultiplied(b, TRUE);
	for (int i = 0; i < s->planes; i++) {
		gdk_dmabuf_texture_builder_set_fd(b, i, s->fd[i]);
		gdk_dmabuf_texture_builder_set_stride(b, i, s->stride[i]);
		gdk_dmabuf_texture_builder_set_offset(b, i, s->offset[i]);
	}
	GError *error = NULL;
	GdkTexture *texture = gdk_dmabuf_texture_builder_build(b, snapshot_free, s, &error);
	g_clear_error(&error);
	g_object_unref(b);
	if (texture) *snapshot = NULL; /* texture now owns the descriptors */
	return texture;
}

struct import_request {
	gatomicrefcount refs;
	GMutex mutex;
	GCond cond;
	bool canceled, done;
	struct snapshot *snapshot;
	GdkTexture *texture;
};

static void request_unref(gpointer data)
{
	struct import_request *r = data;
	if (!g_atomic_ref_count_dec(&r->refs)) return;
	if (r->snapshot) snapshot_free(r->snapshot);
	g_clear_object(&r->texture);
	g_cond_clear(&r->cond);
	g_mutex_clear(&r->mutex);
	g_free(r);
}

static gboolean import_on_main(gpointer data)
{
	struct import_request *r = data;
	g_mutex_lock(&r->mutex);
	bool canceled = r->canceled;
	g_mutex_unlock(&r->mutex);
	if (canceled) return G_SOURCE_REMOVE;
	/* Do not hold the mutex across GTK: the producer must be able to time out
 * even when GTK/its renderer is blocked. The source retains this request. */
	GdkTexture *texture = import_snapshot(&r->snapshot);
	g_mutex_lock(&r->mutex);
	if (!r->canceled) r->texture = texture;
	else g_clear_object(&texture);
	r->done = true;
	g_cond_signal(&r->cond);
	g_mutex_unlock(&r->mutex);
	return G_SOURCE_REMOVE;
}

GdkTexture *atl_egl_snapshot(GLuint framebuffer, int width, int height)
{
	struct snapshot *s = export_snapshot(framebuffer, width, height);
	if (!s) return NULL;
	GMainContext *main = g_main_context_default();
	if (g_main_context_is_owner(main)) {
		/* GDK may initialize its renderer and change the thread's EGL context. */
		EGLDisplay display = eglGetCurrentDisplay();
		EGLContext context = eglGetCurrentContext();
		EGLenum api = eglQueryAPI();
		EGLSurface draw = eglGetCurrentSurface(EGL_DRAW), read = eglGetCurrentSurface(EGL_READ);
		/* Do not leave GDK's thread-local current-context cache referring to a
		 * renderer context after restoring the application's EGL context. */
		gdk_gl_context_clear_current();
		GdkTexture *texture = import_snapshot(&s);
		gdk_gl_context_clear_current();
		eglBindAPI(api);
		eglMakeCurrent(display, draw, read, context);
		if (s) snapshot_free(s);
		return texture;
	}
	struct import_request *r = g_new0(struct import_request, 1);
	g_atomic_ref_count_init(&r->refs);
	g_mutex_init(&r->mutex); g_cond_init(&r->cond);
	r->snapshot = s;
	GSource *source = g_idle_source_new();
	g_source_set_priority(source, G_PRIORITY_HIGH_IDLE);
	g_atomic_ref_count_inc(&r->refs);
	g_source_set_callback(source, import_on_main, r, request_unref);
	g_source_attach(source, main);
	gint64 deadline = g_get_monotonic_time() + 8000;
	g_mutex_lock(&r->mutex);
	while (!r->done && g_cond_wait_until(&r->cond, &r->mutex, deadline)) {}
	GdkTexture *texture = g_steal_pointer(&r->texture);
	r->canceled = true;
	g_mutex_unlock(&r->mutex);
	g_source_destroy(source);
	g_source_unref(source);
	request_unref(r);
	return texture;
}
