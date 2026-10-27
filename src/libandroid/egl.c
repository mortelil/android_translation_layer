#include <gdk/wayland/gdkwayland.h>
#include <gdk/x11/gdkx.h>
#include <gtk/gtk.h>

#include <wayland-egl.h>
#include <dlfcn.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <GL/gl.h>
#include <GLES2/gl2.h>

// FIXME: put the header in a common place
#include "../api-impl-jni/defines.h"
#include "../api-impl-jni/widgets/android_view_SurfaceView.h"

#include "egl_lifecycle.h"
#include "egl_attributes.h"
#include "egl_readback.h"
#include "native_window.h"

extern GtkWindow *window; // TODO: how do we get rid of this? the app won't pass anything useful to eglGetDisplay

/*
 * The GdkDisplay EGL must attach to.
 *
 * An app process owns the ATL window (main-executable/main.c:735) whose display
 * also backs the app's SurfaceViews. A service/GPU child process spawned by the
 * process host does not: main.c's run_service_child() runs "before any
 * GtkApplication exists, because a GtkApplication starts GTK and would need an X
 * display for a process that only hosts services and has no window", so `window`
 * stays NULL and gtk_root_get_display(GTK_ROOT(NULL)) is an invalid call (it
 * asserts and returns NULL). gdk_display_get_default() is NULL in such a process
 * as well, because nothing ever called gtk_init().
 */
static GdkDisplay *egl_gdk_display(void)
{
	if (window && GTK_IS_ROOT(window))
		return gtk_root_get_display(GTK_ROOT(window));
	return gdk_display_get_default();
}

static GHashTable *egl_surface_hashtable;
static GHashTable *texture_surface_hashtable;
static GHashTable *draw_surface_hashtable;
static GHashTable *read_surface_hashtable;

static gboolean surface_matches(gpointer key, gpointer value, gpointer surface)
{
	return value == surface;
}

// temporary for debugging
static void PrintConfigAttributes(EGLDisplay display, EGLConfig config)
{
	EGLint value;
	printf("-------------------------------------------------------------------------------\n");
	eglGetConfigAttrib(display, config, EGL_CONFIG_ID, &value);
	printf("EGL_CONFIG_ID %d\n", value);

	eglGetConfigAttrib(display, config, EGL_BUFFER_SIZE, &value);
	printf("EGL_BUFFER_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_RED_SIZE, &value);
	printf("EGL_RED_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_GREEN_SIZE, &value);
	printf("EGL_GREEN_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_BLUE_SIZE, &value);
	printf("EGL_BLUE_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &value);
	printf("EGL_ALPHA_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_DEPTH_SIZE, &value);
	printf("EGL_DEPTH_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_STENCIL_SIZE, &value);
	printf("EGL_STENCIL_SIZE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SAMPLE_BUFFERS, &value);
	printf("EGL_SAMPLE_BUFFERS %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SAMPLES, &value);
	printf("EGL_SAMPLES %d\n", value);

	eglGetConfigAttrib(display, config, EGL_CONFIG_CAVEAT, &value);
	switch (value) {
		case EGL_NONE:
			printf("EGL_CONFIG_CAVEAT EGL_NONE\n");
			break;
		case EGL_SLOW_CONFIG:
			printf("EGL_CONFIG_CAVEAT EGL_SLOW_CONFIG\n");
			break;
	}

	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_WIDTH, &value);
	printf("EGL_MAX_PBUFFER_WIDTH %d\n", value);
	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_HEIGHT, &value);
	printf("EGL_MAX_PBUFFER_HEIGHT %d\n", value);
	eglGetConfigAttrib(display, config, EGL_MAX_PBUFFER_PIXELS, &value);
	printf("EGL_MAX_PBUFFER_PIXELS %d\n", value);
	eglGetConfigAttrib(display, config, EGL_NATIVE_RENDERABLE, &value);
	printf("EGL_NATIVE_RENDERABLE %s \n", (value ? "true" : "false"));
	eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &value);
	printf("EGL_NATIVE_VISUAL_ID %d\n", value);
	eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_TYPE, &value);
	printf("EGL_NATIVE_VISUAL_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_RENDERABLE_TYPE, &value);
	printf("EGL_RENDERABLE_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_SURFACE_TYPE, &value);
	printf("EGL_SURFACE_TYPE %d\n", value);
	eglGetConfigAttrib(display, config, EGL_TRANSPARENT_TYPE, &value);
	printf("EGL_TRANSPARENT_TYPE %d\n", value);
	printf("-------------------------------------------------------------------------------\n");
}

// this is an extension that only android implements, we can hopefully get away with just stubbing it
EGLBoolean bionic_eglPresentationTimeANDROID(EGLDisplay dpy, EGLSurface surface, EGLnsecsANDROID time)
{
	return EGL_TRUE;
}


EGLContext bionic_eglCreateContext(EGLDisplay display, EGLConfig config,
                                 EGLContext shared, const EGLint *attributes)
{
	EGLContext context = eglCreateContext(display, config, shared, attributes);
	if (context != EGL_NO_CONTEXT) atl_gl_forget_errors(context);
	return context;
}

EGLBoolean bionic_eglDestroyContext(EGLDisplay display, EGLContext context)
{
	EGLBoolean result = eglDestroyContext(display, context);
	if (result && context != eglGetCurrentContext()) atl_gl_forget_errors(context);
	return result;
}

EGLDisplay bionic_eglGetDisplay(EGLNativeDisplayType native_display)
{
	/*
	 * On android, at least SDL passes 0 (EGL_DISPLAY_DEFAULT) to eglGetDisplay and uses the resulting display.
	 * We obviously want to make the app use the correct display, which may happen to be a different one
	 * than the "default" display (especially on Wayland)
	 */
	GdkDisplay *display = egl_gdk_display();
	EGLDisplay egl_display;

	if (!display) {
		/*
		 * No GDK connection (a process without an ATL window, e.g. the Gecko
		 * GPU process): EGL_DEFAULT_DISPLAY is EGL_CAST(EGLNativeDisplayType,0)
		 * (/usr/include/EGL/egl.h:251, android.opengl.EGL14.EGL_DEFAULT_DISPLAY
		 * in the SDK sources) and denotes the default display, not a missing
		 * one, so the host EGL resolves it from the DISPLAY the spawned child
		 * inherits (g_get_environ() in atl_binder_spawn,
		 * src/api-impl-jni/android_os_ATLBinderConnection.c:91). Returning NULL
		 * here instead made Gecko's GPU process fail every
		 * eglGetDisplay(EGL_DEFAULT_DISPLAY) with FEATURE_FAILURE_NO_DISPLAY.
		 */
		return eglGetDisplay(native_display);
	}

	if (GDK_IS_WAYLAND_DISPLAY(display)) {
		struct wl_display *wl_display = gdk_wayland_display_get_wl_display(display);
		egl_display = eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, wl_display, NULL);
	} else if (GDK_IS_X11_DISPLAY(display)) {
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
		Display *x11_display = gdk_x11_display_get_xdisplay(display);
#pragma GCC diagnostic pop
		egl_display = eglGetPlatformDisplay(EGL_PLATFORM_X11_KHR, x11_display, NULL);
	} else {
		return NULL;
	}
	atl_egl_retain_host_display(egl_display);
	return egl_display;
}

EGLBoolean bionic_eglChooseConfig(EGLDisplay display, EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config)
{
	GdkDisplay *gdk_display = egl_gdk_display();

	/* No GDK connection: the display comes from the host EGL's default display
	 * (bionic_eglGetDisplay), whose platform we cannot query here. The pbuffer
	 * workaround below exists only for Wayland, which always has a GDK display,
	 * so pass the request through unchanged - X11 and surfaceless EGL both
	 * support the caller's attributes as given. */
	if (!gdk_display || !attrib_list)
		return eglChooseConfig(display, attrib_list, configs, config_size, num_config);

	if (GDK_IS_X11_DISPLAY(gdk_display)) {
		/* X11 supports pbuffers just fine */
		return eglChooseConfig(display, attrib_list, configs, config_size, num_config);
	} else {
		bool has_pbuffer_bit = false;
		int attrib_list_size = 0;
		for (EGLint *attr = attrib_list; *attr != EGL_NONE; attr += 2) {
			if (*attr == EGL_SURFACE_TYPE && (*(attr + 1) & EGL_PBUFFER_BIT) && *(attr + 1) != EGL_DONT_CARE) {
				has_pbuffer_bit = true;
			}
			attrib_list_size += 2;
		}
		attrib_list_size += 1; // for EGL_NONE
		if (has_pbuffer_bit) {
			/* copy the list in case it's mapped read-only */
			EGLint *new_attrib_list = malloc(sizeof(EGLint) * attrib_list_size);
			memcpy(new_attrib_list, attrib_list, sizeof(EGLint) * attrib_list_size);
			for (EGLint *attr = new_attrib_list; *attr != EGL_NONE; attr += 2) {
				if (*attr == EGL_SURFACE_TYPE && *(attr + 1) != EGL_DONT_CARE) {
					*(attr + 1) &= ~EGL_PBUFFER_BIT;
					*(attr + 1) |= EGL_WINDOW_BIT;
				}
			}
			EGLBoolean ret = eglChooseConfig(display, new_attrib_list, configs, config_size, num_config);
			free(new_attrib_list);
			return ret;
		} else {
			return eglChooseConfig(display, attrib_list, configs, config_size, num_config);
		}
	}
}

EGLSurface bionic_eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, EGLint const *attrib_list)
{
	GdkDisplay *gdk_display = egl_gdk_display();

	/* See bionic_eglChooseConfig: without a GDK connection the display is the
	 * host EGL default display and the Wayland pbuffer emulation does not apply. */
	if (!gdk_display)
		return eglCreatePbufferSurface(display, config, attrib_list);

	if (GDK_IS_X11_DISPLAY(gdk_display)) {
		/* X11 supports pbuffers just fine */
		return eglCreatePbufferSurface(display, config, attrib_list);
	} else {
		struct wl_compositor *wl_compositor = gdk_wayland_display_get_wl_compositor(gdk_display);
		struct wl_surface *wayland_surface = wl_compositor_create_surface(wl_compositor);
		EGLint width = 0;
		EGLint height = 0;
		EGLint *new_attrib_list = atl_pbuffer_window_attributes(attrib_list, &width, &height);
		struct wl_egl_window *egl_window = wl_egl_window_create(wayland_surface, width, height);
		EGLSurface surface = eglCreateWindowSurface(display, config, (EGLNativeWindowType)egl_window, new_attrib_list);
		g_free(new_attrib_list);
		return surface;
	}
}

#define NUM_BUFFERS 3

struct _ATLSurface {
	GObject parent;
	int width;
	int height;
	SurfaceViewWidget *surface_view_widget;
	int32_t framebuffer_format;
	int32_t renderbuffer_format;
	unsigned int renderbuffer_attachment;
	uint32_t renderbuffer;
	struct atl_surface_buffer {
		int width, height;
		struct _ATLSurface *surface;
		uint32_t gl_texture;
		uint32_t gl_framebuffer;
	} buffers[NUM_BUFFERS];
	struct atl_surface_buffer *back_buffer;
	GAsyncQueue *vsync;
	GAsyncQueue *unused_buffers;
	gboolean destroyed;
};
G_DECLARE_FINAL_TYPE(ATLSurface, atl_surface, ATL, SURFACE, GObject);
static void atl_surface_dispose(GObject *object)
{
	ATLSurface *atl_surface = ATL_SURFACE(object);
	if (atl_surface->unused_buffers) g_async_queue_unref(atl_surface->unused_buffers);
	if (atl_surface->vsync) g_async_queue_unref(atl_surface->vsync);
	g_object_unref(atl_surface->surface_view_widget);
}
static void atl_surface_class_init(ATLSurfaceClass *class)
{
	G_OBJECT_CLASS(class)->dispose = atl_surface_dispose;
}
static void atl_surface_init(ATLSurface *self) {}
G_DEFINE_TYPE(ATLSurface, atl_surface, G_TYPE_OBJECT)

/*
 * Android exports eglCreateImageKHR (with EGLAttrib) as a direct libEGL.so symbol,
 * while Mesa exports the core EGL 1.5 function eglCreateImage as its linkable entry
 * point instead. The wrappers bridge this ABI naming difference.
 */
EGLImage bionic_eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLAttrib *attrib_list)
{
	return eglCreateImage(dpy, ctx, target, buffer, attrib_list);
}

EGLBoolean bionic_eglDestroyImageKHR(EGLDisplay dpy, EGLImage image)
{
	return eglDestroyImage(dpy, image);
}

/*
 * EGL_ANDROID_get_native_client_buffer (API 29) is not exported by the libEGL
 * Mesa provides, which is the libEGL.so ATL links against, so the host extension
 * must not receive ATL-private buffers. ATL's AHardwareBuffer is malloc memory that cannot be
 * imported by a GPU driver, so the
 * documented NULL "no client buffer" result is returned rather than a fabricated
 * buffer that would fail later during eglCreateImageKHR.
 */
EGLClientBuffer bionic_eglGetNativeClientBufferANDROID(const struct AHardwareBuffer *buffer)
{
	/* ATL buffers are not host gralloc objects, even if Mesa exposes this symbol. */
	(void)buffer;
	return NULL;
}

EGLSurface bionic_eglCreateWindowSurface(EGLDisplay display, EGLConfig config, struct ANativeWindow *native_window, EGLint const *attrib_list)
{
	// better than crashing (TODO: check if apps try to use the NULL value anyway)
	if (!native_window)
		return NULL;
	if (native_window->surface_texture) {
		if (native_window->width <= 0 || native_window->height <= 0 || (int64_t)native_window->width * native_window->height * 4 > INT32_MAX)
			return EGL_NO_SURFACE;
		EGLint size[] = {EGL_WIDTH, native_window->width, EGL_HEIGHT, native_window->height, EGL_NONE};
		EGLSurface target = eglCreatePbufferSurface(display, config, size);
		if (target != EGL_NO_SURFACE) {
			if (!texture_surface_hashtable)
				texture_surface_hashtable = g_hash_table_new(NULL, NULL);
			ANativeWindow_acquire(native_window);
			g_hash_table_insert(texture_surface_hashtable, target, native_window);
		}
		return target;
	}

	if (!egl_surface_hashtable)
		egl_surface_hashtable = g_hash_table_new(NULL, NULL);

	ANativeWindow_acquire(native_window);

	PrintConfigAttributes(display, config);
	EGLSurface surface;
	if (getenv("ATL_DIRECT_EGL")) {
		surface = eglCreateWindowSurface(display, config, native_window->egl_window, attrib_list);
	} else {
		ATLSurface *atl_surface = g_object_new(atl_surface_get_type(), NULL);
		atl_surface->width = native_window->width;
		atl_surface->height = native_window->height;
		atl_surface->surface_view_widget = SURFACE_VIEW_WIDGET(g_object_ref(native_window->surface_view_widget));
		EGLint alpha_size = 0;
		EGLint depth_size = 0;
		EGLint stencil_size = 0;
		eglGetConfigAttrib(display, config, EGL_ALPHA_SIZE, &alpha_size);
		eglGetConfigAttrib(display, config, EGL_DEPTH_SIZE, &depth_size);
		eglGetConfigAttrib(display, config, EGL_STENCIL_SIZE, &stencil_size);
		if (alpha_size > 0) {
			atl_surface->framebuffer_format = GL_RGBA;
		} else {
			atl_surface->framebuffer_format = GL_RGB;
		}
		if (depth_size > 0 && stencil_size > 0) {
			atl_surface->renderbuffer_format = GL_DEPTH24_STENCIL8;
			atl_surface->renderbuffer_attachment = GL_DEPTH_STENCIL_ATTACHMENT;
		} else if (depth_size > 0) {
			atl_surface->renderbuffer_format = GL_DEPTH_COMPONENT16;
			atl_surface->renderbuffer_attachment = GL_DEPTH_ATTACHMENT;
		} else if (stencil_size > 0) {
			atl_surface->renderbuffer_format = GL_STENCIL_INDEX8;
			atl_surface->renderbuffer_attachment = GL_STENCIL_ATTACHMENT;
		}
		surface = atl_surface;
	}

	printf("EGL::: create window surface via %s (native_window->egl_window: %p, only used by ATL_DIRECT_EGL)\n",
	       getenv("ATL_DIRECT_EGL") ? "ATL_DIRECT_EGL window" : "ATLSurface route (readback present)",
	       native_window->egl_window);

	printf("EGL::: ret: %p\n", surface);

	g_hash_table_insert(egl_surface_hashtable, surface, native_window);

	if (!surface)
		ANativeWindow_release(native_window);

	return surface;
}

EGLBoolean bionic_eglDestroySurface(EGLDisplay display, EGLSurface surface)
{
	struct ANativeWindow *texture_window = texture_surface_hashtable ? g_hash_table_lookup(texture_surface_hashtable, surface) : NULL;
	if (texture_window) {
		EGLBoolean success = eglDestroySurface(display, surface);
		if (success) {
			g_hash_table_remove(texture_surface_hashtable, surface);
			ANativeWindow_release(texture_window);
		}
		return success;
	}
	struct ANativeWindow *native_window = egl_surface_hashtable ? g_hash_table_lookup(egl_surface_hashtable, surface) : NULL;

	if (!native_window)
		return eglDestroySurface(display, surface);

	EGLBoolean ret = EGL_TRUE;
	if (getenv("ATL_DIRECT_EGL")) {
		ret = eglDestroySurface(display, surface);
	} else {
		ATLSurface *atl_surface = surface;
		g_atomic_int_set(&atl_surface->destroyed, TRUE);
		if (draw_surface_hashtable)
			g_hash_table_foreach_remove(draw_surface_hashtable, surface_matches, surface);
		if (read_surface_hashtable)
			g_hash_table_foreach_remove(read_surface_hashtable, surface_matches, surface);
		for (int i = 0; i < NUM_BUFFERS; i++) {
			if (atl_surface->buffers[i].gl_framebuffer)
				glDeleteFramebuffers(1, &atl_surface->buffers[i].gl_framebuffer);
			if (atl_surface->buffers[i].gl_texture)
				glDeleteTextures(1, &atl_surface->buffers[i].gl_texture);
		}
		g_object_unref(atl_surface);
	}
	if (ret) {
		g_hash_table_remove(egl_surface_hashtable, surface);
		ANativeWindow_release(native_window);
	}

	return ret;
}

/* Only the back buffer may be resized: it is the only buffer the application
 * can be rendering into, and presentation keeps no GL buffer alive (the pixels
 * GTK displays are a CPU copy). */
static void resize_back_buffer(ATLSurface *surface, struct ANativeWindow *window)
{
	struct atl_surface_buffer *buffer = surface->back_buffer;
	int width = g_atomic_int_get(&window->width), height = g_atomic_int_get(&window->height);
	if (!buffer || width <= 0 || height <= 0 || (buffer->width == width && buffer->height == height))
		return;
	GLint texture, renderbuffer;
	glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
	glBindTexture(GL_TEXTURE_2D, buffer->gl_texture);
	glTexImage2D(GL_TEXTURE_2D, 0, surface->framebuffer_format, width, height, 0,
	             surface->framebuffer_format, GL_UNSIGNED_BYTE, NULL);
	glBindTexture(GL_TEXTURE_2D, texture);
	if (surface->renderbuffer) {
		glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
		glBindRenderbuffer(GL_RENDERBUFFER, surface->renderbuffer);
		glRenderbufferStorage(GL_RENDERBUFFER, surface->renderbuffer_format, width, height);
		glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
	}
	buffer->width = width;
	buffer->height = height;
	if (getenv("ATL_DEBUG_SURFACE"))
		g_printerr("ATL EGL buffer resized %dx%d\n", width, height);
}

EGLBoolean bionic_eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context)
{
	if (getenv("ATL_DIRECT_EGL"))
		return eglMakeCurrent(display, draw, read, context);
	struct ANativeWindow *native_window = egl_surface_hashtable ? g_hash_table_lookup(egl_surface_hashtable, draw) : NULL;
	if (!native_window) {
		if (draw_surface_hashtable)
			g_hash_table_remove(draw_surface_hashtable, context);
		if (read_surface_hashtable)
			g_hash_table_remove(read_surface_hashtable, context);
		return eglMakeCurrent(display, draw, read, context);
	}
	if (!draw_surface_hashtable)
		draw_surface_hashtable = g_hash_table_new(NULL, NULL);
	g_hash_table_insert(draw_surface_hashtable, context, draw);
	if (!read_surface_hashtable)
		read_surface_hashtable = g_hash_table_new(NULL, NULL);
	g_hash_table_insert(read_surface_hashtable, context, read);
	EGLBoolean ret = eglMakeCurrent(display, NULL, NULL, context);
	if (!ret) return ret;
	ATLSurface *atl_surface = draw;
	if (!atl_surface->unused_buffers) {
		atl_surface->vsync = g_async_queue_new();
		g_async_queue_push(atl_surface->vsync, GINT_TO_POINTER(1));
		atl_surface->unused_buffers = g_async_queue_new();
		GLint previousTexture;
		glGetIntegerv(GL_TEXTURE_BINDING_2D, &previousTexture); // Save current binding
		if (atl_surface->renderbuffer_format) {
			GLint previous_renderbuffer;
			glGetIntegerv(GL_RENDERBUFFER_BINDING, &previous_renderbuffer);
			glGenRenderbuffers(1, &atl_surface->renderbuffer);
			glBindRenderbuffer(GL_RENDERBUFFER, atl_surface->renderbuffer);
			glRenderbufferStorage(GL_RENDERBUFFER, atl_surface->renderbuffer_format, native_window->width, native_window->height);
			glBindRenderbuffer(GL_RENDERBUFFER, previous_renderbuffer);
		}

		for (int i = 0; i < NUM_BUFFERS; i++) {
			atl_surface->buffers[i].surface = atl_surface;
			atl_surface->buffers[i].width = native_window->width;
			atl_surface->buffers[i].height = native_window->height;
			glGenTextures(1, &atl_surface->buffers[i].gl_texture);
			glBindTexture(GL_TEXTURE_2D, atl_surface->buffers[i].gl_texture);
			glTexImage2D(GL_TEXTURE_2D, 0, atl_surface->framebuffer_format, native_window->width, native_window->height, 0, atl_surface->framebuffer_format, GL_UNSIGNED_BYTE, NULL);

			glGenFramebuffers(1, &atl_surface->buffers[i].gl_framebuffer);
			glBindFramebuffer(GL_FRAMEBUFFER, atl_surface->buffers[i].gl_framebuffer);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, atl_surface->buffers[i].gl_texture, 0);
			if (atl_surface->renderbuffer)
				glFramebufferRenderbuffer(GL_FRAMEBUFFER, atl_surface->renderbuffer_attachment, GL_RENDERBUFFER, atl_surface->renderbuffer);
			g_async_queue_push(atl_surface->unused_buffers, &atl_surface->buffers[i]);
		}
		glBindTexture(GL_TEXTURE_2D, previousTexture);
	}
	if (!atl_surface->back_buffer) {
		atl_surface->back_buffer = g_async_queue_pop(atl_surface->unused_buffers);
	}
	resize_back_buffer(atl_surface, native_window);
	glBindFramebuffer(GL_FRAMEBUFFER, atl_surface->back_buffer->gl_framebuffer);
	if (getenv("ATL_DEBUG_SURFACE"))
		fprintf(stderr, "ATL EGL make_current draw=%p ctx=%p buffer=%dx%d\n", (void *)draw, (void *)context,
		        atl_surface->back_buffer->width, atl_surface->back_buffer->height);
	return ret;
}

/* runs on main thread */
static void frame_callback(SurfaceViewWidget *surface_view_widget)
{
	g_async_queue_push(surface_view_widget->frame_callback_data, GINT_TO_POINTER(1));
}

/*
 * Presentation of an application GL surface.
 *
 * The application renders into an ATL-owned framebuffer with the Mesa EGL
 * context the application created itself. Presenting that framebuffer must not
 * depend on a GDK GL context: gdk_surface_create_gl_context() cannot be made
 * current on a host without GLX/DRI3 (its failure is the reason the previous
 * GdkGLTextureBuilder path silently presented nothing). Instead the rendered
 * pixels are read back on the CPU and handed to the surface widget as a
 * GdkMemoryTexture, which the widget composite path already supports (it is
 * the same path that draws Bitmap/MediaCodec/SurfaceControl content).
 *
 * Pixel contract: glReadPixels() returns rows bottom-up (OpenGL row 0 is the
 * bottom of the framebuffer), tightly packed width*4 RGBA bytes. That is the
 * layout the widget's needs_flip=TRUE convention expects - the same convention
 * the GL-texture path used - so the buffer is passed through unflipped and the
 * snapshot code flips it once.
 */
struct atl_frame {
	ATLSurface *surface;
	GBytes *bytes;
	struct atl_surface_buffer *buffer;
	int width;
	int height;
};

static gboolean present_frame(gpointer data)
{
	struct atl_frame *frame = data;
	ATLSurface *atl_surface = frame->surface;
	if (getenv("ATL_DEBUG_SURFACE"))
		fprintf(stderr, "ATL EGL present %dx%d destroyed=%d\n", frame->width, frame->height, g_atomic_int_get(&atl_surface->destroyed));
	if (!g_atomic_int_get(&atl_surface->destroyed)) {
		GdkTexture *texture = gdk_memory_texture_new(frame->width, frame->height,
		                                             GDK_MEMORY_R8G8B8A8_PREMULTIPLIED,
		                                             frame->bytes, (size_t)frame->width * 4);
		/* Ask the widget to report back through ->vsync once it has drawn this
		 * frame, which paces bionic_eglSwapBuffers(). */
		surface_view_widget_set_frame_callback(atl_surface->surface_view_widget,
		                                      frame_callback, g_async_queue_ref(atl_surface->vsync),
		                                      (GDestroyNotify)g_async_queue_unref);
		surface_view_widget_set_texture(atl_surface->surface_view_widget, texture, TRUE);
	}
	g_async_queue_push(atl_surface->unused_buffers, frame->buffer);
	g_bytes_unref(frame->bytes);
	g_object_unref(atl_surface);
	g_free(frame);
	return G_SOURCE_REMOVE;
}

/* Runs on the application's GL thread, with the application's context current. */
static bool queue_frame_readback(ATLSurface *atl_surface, struct atl_surface_buffer *buffer)
{
	GBytes *bytes = atl_gl_read_rgba(buffer->gl_framebuffer, buffer->width, buffer->height);
	if (!bytes)
		return false;
	struct atl_frame *frame = g_new0(struct atl_frame, 1);
	frame->surface = g_object_ref(atl_surface);
	frame->buffer = buffer;
	frame->bytes = bytes;
	frame->width = buffer->width;
	frame->height = buffer->height;
	g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, present_frame, frame, NULL);
	return true;
}

EGLBoolean bionic_eglSwapBuffers(EGLDisplay display, EGLSurface surface)
{
	struct ANativeWindow *texture_window = texture_surface_hashtable ? g_hash_table_lookup(texture_surface_hashtable, surface) : NULL;
	if (getenv("ATL_DEBUG_SURFACE"))
		fprintf(stderr, "ATL EGL swap surface=%p texture_window=%s\n", (void *)surface, texture_window ? "yes" : "no");
	if (texture_window) {
		int width = texture_window->width, height = texture_window->height;
		size_t stride = (size_t)width * 4;
		unsigned char *pixels = g_malloc(stride * height);
		GLint alignment;
		glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
		glPixelStorei(GL_PACK_ALIGNMENT, 1);
		glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		glPixelStorei(GL_PACK_ALIGNMENT, alignment);
		GLenum read_error = glGetError();
		if (read_error != GL_NO_ERROR) {
			g_warning("Surface image readback failed: GL error 0x%x", read_error);
			g_free(pixels);
			return EGL_FALSE;
		}
		// GL rows start at the bottom; the Java image/canvas queue uses top-down RGBA.
		unsigned char *row = g_malloc(stride);
		for (int y = 0; y < height / 2; ++y) {
			memcpy(row, pixels + y * stride, stride);
			memcpy(pixels + y * stride, pixels + (height - y - 1) * stride, stride);
			memcpy(pixels + (height - y - 1) * stride, row, stride);
		}
		g_free(row);
		gboolean posted = atl_native_window_post_rgba(texture_window, pixels, width, height);
		g_free(pixels);
		return posted ? EGL_TRUE : EGL_FALSE;
	}
	if (getenv("ATL_DIRECT_EGL"))
		return eglSwapBuffers(display, surface);
	struct ANativeWindow *native_window = egl_surface_hashtable ? g_hash_table_lookup(egl_surface_hashtable, surface) : NULL;
	ATLSurface *atl_surface = surface;
	if (getenv("ATL_DEBUG_SURFACE"))
		fprintf(stderr, "ATL EGL swap atl_surface=%p native_window=%s back_buffer=%s\n", (void *)atl_surface, native_window ? "yes" : "no",
		        (native_window && atl_surface->back_buffer) ? "yes" : "no");
	if (!native_window) {
		return eglSwapBuffers(display, surface);
	}

	GLint app_framebuffer, app_read_framebuffer = 0;
	EGLint version = 2;
	eglQueryContext(display, eglGetCurrentContext(), EGL_CONTEXT_CLIENT_VERSION, &version);
	glGetIntegerv(GL_FRAMEBUFFER_BINDING, &app_framebuffer);
	if (version >= 3) glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &app_read_framebuffer);
	GLuint old_back = atl_surface->back_buffer ? atl_surface->back_buffer->gl_framebuffer : 0;
	glFlush();
	if (atl_surface->back_buffer) {
		g_async_queue_timeout_pop(atl_surface->vsync, 50000); // 50ms
		/* Retain the buffer until presentation to bound queued CPU frames. */
		if (!queue_frame_readback(atl_surface, atl_surface->back_buffer))
			return EGL_FALSE;
		atl_surface->back_buffer = NULL;
	}
	atl_surface->back_buffer = g_async_queue_timeout_pop(atl_surface->unused_buffers, 100000); // 100ms
	resize_back_buffer(atl_surface, native_window);
	if (!atl_surface->back_buffer) return EGL_FALSE;
	if ((GLuint)app_framebuffer == old_back)
		glBindFramebuffer(version >= 3 ? GL_DRAW_FRAMEBUFFER : GL_FRAMEBUFFER,
		                  atl_surface->back_buffer->gl_framebuffer);
	if (version >= 3 && (GLuint)app_read_framebuffer == old_back)
		glBindFramebuffer(GL_READ_FRAMEBUFFER, atl_surface->back_buffer->gl_framebuffer);

	return EGL_TRUE;
}

EGLBoolean bionic_eglQuerySurface(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint *value)
{
	if (getenv("ATL_DIRECT_EGL"))
		return eglQuerySurface(display, surface, attribute, value);
	struct ANativeWindow *native_window = egl_surface_hashtable ? g_hash_table_lookup(egl_surface_hashtable, surface) : NULL;
	if (!native_window)
		return eglQuerySurface(display, surface, attribute, value);
	if (attribute == EGL_WIDTH) {
		*value = g_atomic_int_get(&native_window->width);
	} else if (attribute == EGL_HEIGHT) {
		*value = g_atomic_int_get(&native_window->height);
	} else {
		printf("bionic_eglQuerySurface(%p, %p, %d, %p): attribute not implemented\n", display, surface, attribute, value);
		return eglQuerySurface(display, surface, attribute, value);
	}
	return EGL_TRUE;
}

EGLSurface bionic_eglGetCurrentSurface(EGLint readdraw)
{
	if (getenv("ATL_DIRECT_EGL"))
		return eglGetCurrentSurface(readdraw);
	EGLContext current_context = eglGetCurrentContext();
	if (readdraw == EGL_READ)
		return read_surface_hashtable && g_hash_table_contains(read_surface_hashtable, current_context) ? g_hash_table_lookup(read_surface_hashtable, current_context) : eglGetCurrentSurface(readdraw);
	else if (readdraw == EGL_DRAW)
		return draw_surface_hashtable && g_hash_table_contains(draw_surface_hashtable, current_context) ? g_hash_table_lookup(draw_surface_hashtable, current_context) : eglGetCurrentSurface(readdraw);

	return NULL;
}

void bionic_glBindFramebuffer(GLenum target, GLuint framebuffer)
{
	if (getenv("ATL_DIRECT_EGL") || framebuffer != 0)
		return glBindFramebuffer(target, framebuffer);
	ATLSurface *atl_surface = draw_surface_hashtable ? g_hash_table_lookup(draw_surface_hashtable, eglGetCurrentContext()) : NULL;
	return glBindFramebuffer(target, atl_surface && atl_surface->back_buffer ? atl_surface->back_buffer->gl_framebuffer : 0);
}
