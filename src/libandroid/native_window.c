/*
 * parts of this file originally from AOSP:
 *
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include "../api-impl-jni/widgets/android_view_SurfaceView.h"
#include <stdlib.h>

#include <EGL/egl.h>

#include <wayland-client.h>
#include <wayland-egl.h>

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/shape.h>

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan_wayland.h>
#include <vulkan/vulkan_xlib.h>

#include <gdk/wayland/gdkwayland.h>
#include <gdk/x11/gdkx.h>
#include <gtk/gtk.h>

// FIXME: move this together with the other stuff that doesn't belong in this file
#include <openxr/openxr.h>
#define XR_USE_PLATFORM_EGL
#include <openxr/openxr_platform.h>
#ifndef XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT
	#define XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT 1000426000
#endif

#include <assert.h>
#include <dlfcn.h>

#include <jni.h>

// FIXME: put the header in a common place
#include "../api-impl-jni/defines.h"

#include "native_window.h"

/**
 * Transforms that can be applied to buffers as they are displayed to a window.
 *
 * Supported transforms are any combination of horizontal mirror, vertical
 * mirror, and clockwise 90 degree rotation, in that order. Rotations of 180
 * and 270 degrees are made up of those basic transforms.
 */
enum ANativeWindowTransform {
	ANATIVEWINDOW_TRANSFORM_IDENTITY = 0x00,
	ANATIVEWINDOW_TRANSFORM_MIRROR_HORIZONTAL = 0x01,
	ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL = 0x02,
	ANATIVEWINDOW_TRANSFORM_ROTATE_90 = 0x04,

	ANATIVEWINDOW_TRANSFORM_ROTATE_180 = ANATIVEWINDOW_TRANSFORM_MIRROR_HORIZONTAL | ANATIVEWINDOW_TRANSFORM_MIRROR_VERTICAL,
	ANATIVEWINDOW_TRANSFORM_ROTATE_270 = ANATIVEWINDOW_TRANSFORM_ROTATE_180 | ANATIVEWINDOW_TRANSFORM_ROTATE_90,
};

/**
 * Opaque type that provides access to a native window.
 *
 * A pointer can be obtained using {@link ANativeWindow_fromSurface()}.
 */
typedef struct ANativeWindow ANativeWindow;

/**
 * Struct that represents a windows buffer.
 *
 * A pointer can be obtained using {@link ANativeWindow_lock()}.
 */
typedef struct ANativeWindow_Buffer {
	// The number of pixels that are show horizontally.
	int32_t width;

	// The number of pixels that are shown vertically.
	int32_t height;

	// The number of *pixels* that a line in the buffer takes in
	// memory. This may be >= width.
	int32_t stride;

	// The format of the buffer. One of AHARDWAREBUFFER_FORMAT_*
	int32_t format;

	// The actual bits.
	void *bits;

	// Do not touch.
	uint32_t reserved[6];
} ANativeWindow_Buffer;

/**
 * Acquire a reference on the given {@link ANativeWindow} object. This prevents the object
 * from being deleted until the reference is removed.
 */
extern GThread *main_thread_id;
void ANativeWindow_acquire(struct ANativeWindow *window)
{
	if (window) g_atomic_int_inc(&window->refcount);
}

/* GTK resources and signals must be released on GTK's thread. The final
 * reference is consumed by this callback, so a queued resize cannot use freed memory. */
static gboolean destroy_widget_window(gpointer data)
{
	struct ANativeWindow *window = data;
	g_clear_signal_handler(&window->resize_handler, window->surface_view_widget);
	g_clear_signal_handler(&window->unrealize_handler, window->surface_view_widget);
	if (window->wayland_display) {
		wl_egl_window_destroy((struct wl_egl_window *)window->egl_window);
		wl_subsurface_destroy(window->wayland_subsurface);
		wl_surface_destroy(window->wayland_surface);
	} else if (window->x11_display && !gdk_surface_is_destroyed(window->parent_surface)) {
		XDestroyWindow(window->x11_display, window->egl_window);
	}
	g_clear_object(&window->surface_view_widget);
	g_clear_object(&window->parent_surface);
	g_clear_object(&window->display);
	g_mutex_clear(&window->pending_lock);
	free(window);
	return G_SOURCE_REMOVE;
}
void ANativeWindow_release(struct ANativeWindow *window)
{
	if (!window || !g_atomic_int_dec_and_test(&window->refcount)) return;
	if (window->surface_texture || window->remote_producer) {
		JNIEnv *env;
		gboolean attached = (*window->jvm)->GetEnv(window->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
		if (attached && (*window->jvm)->AttachCurrentThread(window->jvm, (void **)&env, NULL) != JNI_OK)
			g_error("Cannot attach thread to release SurfaceTexture reference");
		if (window->surface_texture) (*env)->DeleteWeakGlobalRef(env, window->surface_texture);
		if (window->remote_producer) (*env)->DeleteGlobalRef(env, window->remote_producer);
		if (attached) (*window->jvm)->DetachCurrentThread(window->jvm);
		free(window);
	} else if (g_thread_self() == main_thread_id) {
		destroy_widget_window(window);
	} else {
		// invoke() may execute inline on a worker owning the context; an idle cannot.
		g_idle_add_full(G_PRIORITY_DEFAULT, destroy_widget_window, window, NULL);
	}
}
int32_t ANativeWindow_getWidth(struct ANativeWindow *window) { return g_atomic_int_get(&window->width); }
int32_t ANativeWindow_getHeight(struct ANativeWindow *window) { return g_atomic_int_get(&window->height); }

gboolean atl_native_window_is_valid(JNIEnv *env, struct ANativeWindow *window)
{
	if (!window) return FALSE;
	if (window->remote_producer) {
        jclass cls = (*env)->GetObjectClass(env, window->remote_producer);
        jintArray size = (*env)->CallObjectMethod(env, window->remote_producer, (*env)->GetMethodID(env, cls, "size", "()[I"));
        jint dims[2] = {0};
        if (size && !(*env)->ExceptionCheck(env)) (*env)->GetIntArrayRegion(env, size, 0, 2, dims);
        if (size) (*env)->DeleteLocalRef(env, size);
        (*env)->DeleteLocalRef(env, cls);
        return dims[0] > 0 && dims[1] > 0 && !(*env)->ExceptionCheck(env);
    }
    if (!window->surface_texture) return !g_atomic_int_get(&window->abandoned);
	jobject texture = (*env)->NewLocalRef(env, window->surface_texture);
	if (!texture) return FALSE;
	jclass cls = (*env)->GetObjectClass(env, texture);
	jboolean released = (*env)->CallBooleanMethod(env, texture, (*env)->GetMethodID(env, cls, "isReleased", "()Z"));
	(*env)->DeleteLocalRef(env, cls);
	(*env)->DeleteLocalRef(env, texture);
	return !released && !(*env)->ExceptionCheck(env);
}

jobject ANativeWindow_toSurface(JNIEnv *env, struct ANativeWindow *window)
{
	if (!window) return NULL;
	jclass cls = (*env)->FindClass(env, "android/view/Surface");
	if (!cls) return NULL;
	jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(JLandroid/graphics/SurfaceTexture;)V");
	if (!ctor) { (*env)->DeleteLocalRef(env, cls); return NULL; }
	jobject texture = window->surface_texture ? (*env)->NewLocalRef(env, window->surface_texture) : NULL;
	ANativeWindow_acquire(window);
	jobject surface = (*env)->NewObject(env, cls, ctor, (jlong)(intptr_t)window, texture);
	if (!surface) ANativeWindow_release(window);
    if (surface && window->remote_producer) {
        // A native roundtrip must forward the original capability, not create
        // a new consumer in this process (which has no GTK widget).
        jclass remote_cls = (*env)->GetObjectClass(env, window->remote_producer);
        jfieldID endpoint_field = (*env)->GetFieldID(env, remote_cls, "endpoint", "Landroid/os/IBinder;");
        jobject endpoint = (*env)->GetObjectField(env, window->remote_producer, endpoint_field);
        (*env)->SetObjectField(env, surface, (*env)->GetFieldID(env, cls, "producer", "Landroid/os/IBinder;"), endpoint);
        (*env)->DeleteLocalRef(env, endpoint);
        (*env)->DeleteLocalRef(env, remote_cls);
    }
	if (texture) (*env)->DeleteLocalRef(env, texture);
	(*env)->DeleteLocalRef(env, cls);
	return surface;
}

/**
 * Return the current pixel format (AHARDWAREBUFFER_FORMAT_*) of the window surface.
 *
 * \return a negative value on error.
 */
int32_t ANativeWindow_getFormat(ANativeWindow *window)
{
	return -1;
}

/**
 * Change the format and size of the window buffers.
 *
 * The width and height control the number of pixels in the buffers, not the
 * dimensions of the window on screen. If these are different than the
 * window's physical size, then its buffer will be scaled to match that size
 * when compositing it to the screen. The width and height must be either both zero
 * or both non-zero.
 *
 * For all of these parameters, if 0 is supplied then the window's base
 * value will come back in force.
 *
 * \param width width of the buffers in pixels.
 * \param height height of the buffers in pixels.
 * \param format one of AHARDWAREBUFFER_FORMAT_* constants.
 * \return 0 for success, or a negative value on error.
 */
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *window,
                                         int32_t width, int32_t height, int32_t format)
{
	return -1;
}

/**
 * Lock the window's next drawing surface for writing.
 * inOutDirtyBounds is used as an in/out parameter, upon entering the
 * function, it contains the dirty region, that is, the region the caller
 * intends to redraw. When the function returns, inOutDirtyBounds is updated
 * with the actual area the caller needs to redraw -- this region is often
 * extended by {@link ANativeWindow_lock}.
 *
 * \return 0 for success, or a negative value on error.
 */

typedef void ARect;

int32_t ANativeWindow_lock(ANativeWindow *window, ANativeWindow_Buffer *outBuffer,
                           ARect *inOutDirtyBounds)
{
	return -1;
}

/**
 * Unlock the window's drawing surface after previously locking it,
 * posting the new buffer to the display.
 *
 * \return 0 for success, or a negative value on error.
 */
int32_t ANativeWindow_unlockAndPost(ANativeWindow *window)
{
	return -1;
}

int32_t ANativeWindow_setFrameRate(ANativeWindow *window, float frameRate, int8_t compatibility)
{
	return 0; // success
}

/**
 * Set a transform that will be applied to future buffers posted to the window.
 *
 * \param transform combination of {@link ANativeWindowTransform} flags
 * \return 0 for success, or -EINVAL if \p transform is invalid
 */
int32_t ANativeWindow_setBuffersTransform(ANativeWindow *window, int32_t transform)
{
	return -1;
}

/**
 * Set the data space of future buffers posted to the window.
 *
 * The value has nowhere to take effect: ATL presents the window through a
 * GTK/Wayland surface rather than a BufferQueue, which is why
 * {@link ANativeWindow_setBuffersGeometry} and
 * {@link ANativeWindow_setBuffersTransform} above report failure. Return the NDK negative error result because no data-space change is applied.
 */
int32_t ANativeWindow_setBuffersDataSpace(ANativeWindow *window, int32_t dataSpace)
{
	(void)window;
	(void)dataSpace;
	return -ENOSYS;
}

void wl_registry_global_handler(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version)
{
	struct wl_subcompositor **subcompositor = data;
	printf("interface: '%s', version: %u, name: %u\n", interface, version, name);
	if (!strcmp(interface, "wl_subcompositor")) {
		*subcompositor = wl_registry_bind(registry, name, &wl_subcompositor_interface, 1);
	}
}

void wl_registry_global_handler_compositor(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version)
{
	struct wl_subcompositor **compositor = data;
	if (!strcmp(interface, "wl_compositor")) {
		*compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 1);
	}
}

void wl_registry_global_remove_handler(void *data, struct wl_registry *registry, uint32_t name)
{
	printf("removed: %u\n", name);
}

static void on_resize(GtkWidget *self, gint width, gint height, ANativeWindow *native_window)
{
	if (g_atomic_int_get(&native_window->abandoned)) return;
	/* EGL buffers cannot have a zero extent. Keep the temporary 1x1 backing
	 * surface until GTK assigns a real allocation. */
	width = MAX(width, 1);
	height = MAX(height, 1);
	g_atomic_int_set(&native_window->width, width);
	g_atomic_int_set(&native_window->height, height);
	if (native_window->wayland_display) {
		wl_egl_window_resize((struct wl_egl_window *)native_window->egl_window, width, height, 0, 0);
	} else if (native_window->x11_display) {
		XResizeWindow(native_window->x11_display, (Window)native_window->egl_window, width, height);
	}
}

static void on_unrealize(GtkWidget *widget, ANativeWindow *window)
{
	g_atomic_int_set(&window->abandoned, TRUE);
}
static ANativeWindow *window_from_surface_on_main(JNIEnv *env, jobject surface)
{
	if (!surface)
		return NULL;
	if (_GET_BOOL_FIELD(surface, "released")) return NULL;
	jobject texture = (*env)->CallObjectMethod(env, surface, _METHOD(_CLASS(surface), "atlGetTexture", "()Landroid/graphics/SurfaceTexture;"));
	if (texture) {
		ANativeWindow *result = ANativeWindow_fromSurfaceTexture(env, texture);
		(*env)->DeleteLocalRef(env, texture);
		return result;
	}
	int width;
	int height;

	int ret;

	graphene_point_t pos;
	double off_x;
	double off_y;

	static struct wl_subcompositor *wl_subcompositor = NULL;
	static struct wl_registry_listener wl_registry_listener = {
		.global = wl_registry_global_handler,
		.global_remove = wl_registry_global_remove_handler
	};

	GtkWidget *wrapper = _PTR(_GET_LONG_FIELD(surface, "widget"));
	if (!wrapper) return NULL;
	GtkWidget *surface_view_widget = gtk_widget_get_first_child(wrapper);
	if (!surface_view_widget || !gtk_widget_get_realized(surface_view_widget)) return NULL;
	GtkWidget *window = GTK_WIDGET(gtk_widget_get_native(surface_view_widget));
	width = gtk_widget_get_width(surface_view_widget);
	if (!window) return NULL;
	height = gtk_widget_get_height(surface_view_widget);
	/* SurfaceHolder.surfaceCreated is delivered after GTK realizes the widget,
	 * but before GTK necessarily gives it its first allocation. Android still
	 * provides a valid Surface during that interval; returning NULL here makes
	 * EGL window-surface creation fail permanently for clients that create EGL
	 * synchronously from surfaceCreated (Flutter does this). Use a 1x1 backing
	 * size until the first GTK resize arrives; on_resize() then publishes the
	 * allocated size to EGL and its render buffers. */
	int buffer_width = MAX(width, 1);
	int buffer_height = MAX(height, 1);

	// get position of the SurfaceView widget wrt the toplevel window
	ret = gtk_widget_compute_point(surface_view_widget, window, &GRAPHENE_POINT_INIT(0, 0), &pos);
	assert(ret);
	// compensate for offset between the widget coordinates and the surface coordinates
	gtk_native_get_surface_transform(GTK_NATIVE(window), &off_x, &off_y);
	pos.x += off_x;
	pos.y += off_y;

	printf("XXXXX: SurfaceView widget: %p (%s), width: %d, height: %d\n", surface_view_widget, gtk_widget_get_name(surface_view_widget), width, height);
	printf("XXXXX: SurfaceView widget: x: %lf, y: %lf\n", pos.x, pos.y);
	printf("XXXXX: native offset: x: %lf, y: %lf\n", off_x, off_y);

	struct ANativeWindow *native_window = calloc(1, sizeof(struct ANativeWindow));
	if (!native_window) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "Native window allocation");
		return NULL;
	}
	native_window->refcount = 1;
	native_window->surface_view_widget = g_object_ref(surface_view_widget);
	native_window->parent_surface = g_object_ref(gtk_native_get_surface(GTK_NATIVE(window)));
	native_window->width = buffer_width;
	native_window->height = buffer_height;

	GdkDisplay *display = gtk_root_get_display(GTK_ROOT(window));
	native_window->display = g_object_ref(display);

	if (!getenv("ATL_DIRECT_EGL")) {
		// nothing to do
	} else if (GDK_IS_WAYLAND_DISPLAY(display)) {
		struct wl_display *wl_display = gdk_wayland_display_get_wl_display(display);
		struct wl_compositor *wl_compositor = gdk_wayland_display_get_wl_compositor(display);

		if (!wl_subcompositor) { // FIXME this assumes the wl_display doesn't change
			struct wl_registry *wl_registry = wl_display_get_registry(wl_display);
			wl_registry_add_listener(wl_registry, &wl_registry_listener, &wl_subcompositor);
			wl_display_roundtrip(wl_display);
			wl_registry_destroy(wl_registry);
			printf("XXX: wl_subcompositor: %p\n", wl_subcompositor);
		}

		struct wl_surface *toplevel_surface = gdk_wayland_surface_get_wl_surface(gtk_native_get_surface(GTK_NATIVE(window)));

		struct wl_surface *wayland_surface = wl_compositor_create_surface(wl_compositor);

		struct wl_subsurface *subsurface = wl_subcompositor_get_subsurface(wl_subcompositor, wayland_surface, toplevel_surface);
		wl_subsurface_set_desync(subsurface);
		wl_subsurface_set_position(subsurface, pos.x, pos.y);

#if (GTK_MAJOR_VERSION >= 4 && GTK_MINOR_VERSION >= 22)
		/* in Gtk 4.22+ we are able to do hole punching */
		wl_subsurface_place_below(subsurface, toplevel_surface);
#endif

		struct wl_region *empty_region = wl_compositor_create_region(wl_compositor);
		wl_surface_set_input_region(wayland_surface, empty_region);
		wl_region_destroy(empty_region);

		struct wl_egl_window *egl_window = wl_egl_window_create(wayland_surface, buffer_width, buffer_height);
		native_window->egl_window = (EGLNativeWindowType)egl_window;
		native_window->wayland_display = wl_display;
		native_window->wayland_surface = wayland_surface;
		native_window->wayland_subsurface = subsurface;
		printf("EGL::: wayland_surface: %p\n", wayland_surface);
	} else if (GDK_IS_X11_DISPLAY(display)) {
		/* X11 support is deprecated, which means that if we decide to switch to Gtk 5 we will lose the ability to run ATL on X11.
		 * for now, silence the warnings */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"

		int major;
		int minor;

		/* Check if we support EGL */
		if (gdk_x11_display_get_egl_version(display, &major, &minor)) {
			printf("XXX: EGL version: %d.%d\n", major, minor);
		} else {
			fprintf(stderr, "ANativeWindow_fromSurface: crashing here;\n"
			                "The GTK X11 context was made using GLX, which isn't and won't be supported\n"
			                "Please use GDK_DEBUG='gl-egl' to use EGL\n");
			exit(1);
		}

		/* Get the X11 display server */
		Display *x11_display = gdk_x11_display_get_xdisplay(display);
		native_window->x11_display = x11_display;

		/* Get the top level window's X11 window ID */
		Window toplevel_window = gdk_x11_surface_get_xid(gtk_native_get_surface(GTK_NATIVE(window)));

		/*
		 * Make a new X11 window inheriting from the GTK top level window.
		 * The reason why it's first bound to the default root window and
		 * then reparented to the GTK window is because on NVIDIA drivers
		 * the GTK window selects a visual mode that's not compatible with
		 * NVIDIA's implementation of EGL for some reason.
		 */
		Window x11_window = XCreateSimpleWindow(x11_display, DefaultRootWindow(x11_display), 0, 0, buffer_width, buffer_height, 0, 0, 0xffffffff);
		XReparentWindow(x11_display, x11_window, toplevel_window, pos.x, pos.y);

		XMapWindow(x11_display, x11_window);

		/* Make the X11 window able to be clicked through */
		Region region = XCreateRegion();
		XRectangle rectangle;
		rectangle.x = 0;
		rectangle.y = 0;
		rectangle.width = 0;
		rectangle.height = 0;
		XUnionRectWithRegion(&rectangle, region, region);
		XShapeCombineRegion(x11_display, x11_window, ShapeInput, 0, 0, region, ShapeSet);
		XDestroyRegion(region);

		native_window->egl_window = (EGLNativeWindowType)x11_window;

#pragma GCC diagnostic pop
	}

	if (native_window->resize_handler == 0)
		native_window->resize_handler = g_signal_connect(surface_view_widget, "resize", G_CALLBACK(on_resize), native_window);

	native_window->unrealize_handler = g_signal_connect(surface_view_widget, "unrealize", G_CALLBACK(on_unrealize), native_window);
	return native_window;
}

struct window_request {
	JavaVM *vm;
	jobject surface;
	ANativeWindow *result;
	GMutex mutex;
	GCond done;
	gboolean completed;
	jthrowable exception;
};
static gboolean create_window_on_main(gpointer data)
{
	struct window_request *r = data;
	JNIEnv *env;
	(*r->vm)->GetEnv(r->vm, (void **)&env, JNI_VERSION_1_6);
	(*env)->PushLocalFrame(env, 32);
	(*env)->MonitorEnter(env, r->surface);
	r->result = window_from_surface_on_main(env, r->surface);
	(*env)->MonitorExit(env, r->surface);
	if ((*env)->ExceptionCheck(env)) {
		jthrowable error = (*env)->ExceptionOccurred(env);
		(*env)->ExceptionClear(env);
		r->exception = (*env)->NewGlobalRef(env, error);
	}
	(*env)->PopLocalFrame(env, NULL);
	g_mutex_lock(&r->mutex);
	r->completed = TRUE;
	g_cond_signal(&r->done);
	g_mutex_unlock(&r->mutex);
	return G_SOURCE_REMOVE;
}
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *env, jobject surface)
{
	if (!surface) return NULL;
	(*env)->MonitorEnter(env, surface);
	ANativeWindow *window = _PTR(_GET_LONG_FIELD(surface, "nativeWindow"));
	gboolean released = _GET_BOOL_FIELD(surface, "released");
	if (window && !released) ANativeWindow_acquire(window);
	if (window || released) {
		(*env)->MonitorExit(env, surface);
		return released ? NULL : window;
	}
	jobject texture = (*env)->CallObjectMethod(env, surface, _METHOD(_CLASS(surface), "atlGetTexture", "()Landroid/graphics/SurfaceTexture;"));
	if (texture) {
		window = ANativeWindow_fromSurfaceTexture(env, texture);
		(*env)->DeleteLocalRef(env, texture);
		(*env)->MonitorExit(env, surface);
		return window;
	}
	if (g_thread_self() == main_thread_id) {
		window = window_from_surface_on_main(env, surface);
		(*env)->MonitorExit(env, surface);
		return window;
	}
	(*env)->MonitorExit(env, surface);
	struct window_request r = {0};
	(*env)->GetJavaVM(env, &r.vm);
	r.surface = (*env)->NewGlobalRef(env, surface);
	g_mutex_init(&r.mutex); g_cond_init(&r.done);
	g_mutex_lock(&r.mutex);
	g_idle_add_full(G_PRIORITY_DEFAULT, create_window_on_main, &r, NULL);
	while (!r.completed) g_cond_wait(&r.done, &r.mutex);
	g_mutex_unlock(&r.mutex);
	g_mutex_clear(&r.mutex); g_cond_clear(&r.done);
	(*env)->DeleteGlobalRef(env, r.surface);
	if (r.exception) { (*env)->Throw(env, r.exception); (*env)->DeleteGlobalRef(env, r.exception); }
	return r.result;
}

ANativeWindow *ANativeWindow_fromSurfaceTexture(JNIEnv *env, jobject surfaceTexture)
{
	if (!surfaceTexture)
		return NULL;
	jclass cls = (*env)->GetObjectClass(env, surfaceTexture);
	jmethodID size_method = (*env)->GetMethodID(env, cls, "atlBufferSize", "()[I");
	jintArray size = (*env)->CallObjectMethod(env, surfaceTexture, size_method);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->DeleteLocalRef(env, cls);
		return NULL;
	}
	jint dimensions[2];
	(*env)->GetIntArrayRegion(env, size, 0, 2, dimensions);
	ANativeWindow *result = calloc(1, sizeof(*result));
	if (!result) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "Native window allocation");
		(*env)->DeleteLocalRef(env, size);
		(*env)->DeleteLocalRef(env, cls);
		return NULL;
	}
	result->width = dimensions[0];
	result->height = dimensions[1];
	result->refcount = 1;
	(*env)->GetJavaVM(env, &result->jvm);
	result->surface_texture = (*env)->NewWeakGlobalRef(env, surfaceTexture);
	result->queue_frame = (*env)->GetMethodID(env, cls, "atlQueueFrame", "([BIIJ)V");
	if (!result->surface_texture || !result->queue_frame) {
		if (result->surface_texture) (*env)->DeleteWeakGlobalRef(env, result->surface_texture);
		free(result); result = NULL;
	}
	(*env)->DeleteLocalRef(env, size);
	(*env)->DeleteLocalRef(env, cls);
	return result;
}

gboolean atl_native_window_post_rgba(ANativeWindow *window, const unsigned char *pixels, int width, int height)
{
	if (width <= 0 || height <= 0 || (int64_t)width * height * 4 > INT32_MAX) return FALSE;
	JNIEnv *env;
	gboolean attached = (*window->jvm)->GetEnv(window->jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED;
	if (attached && (*window->jvm)->AttachCurrentThread(window->jvm, (void **)&env, NULL) != JNI_OK)
		return FALSE;
    if (window->remote_producer) {
        gboolean ok = FALSE;
        size_t bytes = (size_t)width * height * 4;
        int fd = memfd_create("atl-surface-frame", MFD_CLOEXEC | MFD_ALLOW_SEALING);
        if (fd >= 0 && bytes <= 64 * 1024 * 1024 && ftruncate(fd, bytes) == 0) {
            void *mapping = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (mapping != MAP_FAILED) {
                memcpy(mapping, pixels, bytes);
                munmap(mapping, bytes);
                if (fcntl(fd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL) == 0)
                    ok = (*env)->CallBooleanMethod(env, window->remote_producer, window->queue_frame, fd, width, height);
            }
        }
        if (fd >= 0) close(fd);
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); ok = FALSE; }
        if (attached) (*window->jvm)->DetachCurrentThread(window->jvm);
        return ok;
    }
	jobject texture = (*env)->NewLocalRef(env, window->surface_texture);
	if (!texture) {
		if (attached) (*window->jvm)->DetachCurrentThread(window->jvm);
		return FALSE;
	}
	jbyteArray array = (*env)->NewByteArray(env, width * height * 4);
	if (array) {
		(*env)->SetByteArrayRegion(env, array, 0, width * height * 4, (const jbyte *)pixels);
		(*env)->CallVoidMethod(env, texture, window->queue_frame, array, width, height, (jlong)g_get_monotonic_time() * 1000);
		(*env)->DeleteLocalRef(env, array);
	}
	(*env)->DeleteLocalRef(env, texture);
	gboolean success = array != NULL && !(*env)->ExceptionCheck(env);
	if (!success) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
	if (attached)
		(*window->jvm)->DetachCurrentThread(window->jvm);
	return success;
}

// FIXME 1.5: this most likely belongs elsewhere

VkResult bionic_vkCreateAndroidSurfaceKHR(VkInstance instance, const VkAndroidSurfaceCreateInfoKHR *pCreateInfo, const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface)
{
	GdkDisplay *display = gtk_widget_get_display(pCreateInfo->window->surface_view_widget);

	if (GDK_IS_WAYLAND_DISPLAY(display)) {
		VkWaylandSurfaceCreateInfoKHR wayland_create_info = {
			.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
			.display = pCreateInfo->window->wayland_display,
			.surface = pCreateInfo->window->wayland_surface,
		};

		return vkCreateWaylandSurfaceKHR(instance, &wayland_create_info, pAllocator, pSurface);
	} else if (GDK_IS_X11_DISPLAY(display)) {
		VkXlibSurfaceCreateInfoKHR x11_create_info = {
			.sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
			.dpy = pCreateInfo->window->x11_display,
			.window = pCreateInfo->window->egl_window,
		};

		return vkCreateXlibSurfaceKHR(instance, &x11_create_info, pAllocator, pSurface);
	} else {
		fprintf(stderr, "bionic_vkCreateAndroidSurfaceKHR: the GDK backend is neither Wayland nor X11, no SurfaceView for you");
		return VK_ERROR_UNKNOWN;
	}
}

VkResult bionic_vkCreateInstance(VkInstanceCreateInfo *pCreateInfo, const VkAllocationCallbacks *pAllocator, VkInstance *pInstance)
{
	int original_extension_count = pCreateInfo->enabledExtensionCount;
	int new_extension_count = original_extension_count + 2;
	const char **enabled_exts = malloc(new_extension_count * sizeof(char *));
	memcpy(enabled_exts, pCreateInfo->ppEnabledExtensionNames, original_extension_count * sizeof(char *));
	enabled_exts[original_extension_count] = "VK_KHR_wayland_surface";
	enabled_exts[original_extension_count + 1] = "VK_KHR_xlib_surface";

	pCreateInfo->enabledExtensionCount = new_extension_count;
	pCreateInfo->ppEnabledExtensionNames = enabled_exts;
	return vkCreateInstance(pCreateInfo, pAllocator, pInstance);
}

PFN_vkVoidFunction bionic_vkGetInstanceProcAddr(VkInstance instance, const char *pName)
{
	if (__unlikely__(!strcmp(pName, "vkCreateInstance")))
		return (PFN_vkVoidFunction)bionic_vkCreateInstance;

	return vkGetInstanceProcAddr(instance, pName);
}

// FIXME 2: this BLATANTLY belongs elsewhere

typedef XrResult (*xr_func)(...);

/* avoid hard dependency on libopenxr_loader for the three functions that we only ever call when running a VR app */
/* NOTE: our use of __builtin_va_arg_pack means this only works as long as we don't need to call a function
 * that takes an integer type shorter than int or a floating point type shorter than double */
static void *openxr_loader_handle = NULL;
static inline __attribute__((__always_inline__)) XrResult xr_lazy_call(char *func_name, ...)
{
	if (!openxr_loader_handle) {
		openxr_loader_handle = dlopen("libopenxr_loader.so.1", RTLD_LAZY);
	}

	xr_func func = dlsym(openxr_loader_handle, func_name);
	return func(__builtin_va_arg_pack());
}

static XrResult bionic_xrInitializeLoaderKHR(void *loaderInitInfo)
{
	fprintf(stderr, "STUB: xrInitializeLoaderKHR noop called\n");
	return XR_SUCCESS;
}

struct XrGraphicsBindingOpenGLESAndroidKHR {
	XrStructureType type;
	const void *next;
	EGLDisplay display;
	EGLConfig config;
	EGLContext context;
};

XrResult bionic_xrCreateSession(XrInstance instance, XrSessionCreateInfo *createInfo, XrSession *session)
{
	struct XrGraphicsBindingOpenGLESAndroidKHR *android_bind = (struct XrGraphicsBindingOpenGLESAndroidKHR *)createInfo->next;
	XrGraphicsBindingEGLMNDX egl_bind = {XR_TYPE_GRAPHICS_BINDING_EGL_MNDX};

	if (android_bind->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) {
		egl_bind.getProcAddress = eglGetProcAddress;
		egl_bind.display = android_bind->display;
		egl_bind.config = android_bind->config;
		egl_bind.context = android_bind->context;
		createInfo->next = &egl_bind;
	} else {
		fprintf(stderr, "xrCreateSession: The graphics binding type = %d\n", android_bind->type);
	}

	return xr_lazy_call("xrCreateSession", instance, createInfo, session);
}

/*
 * Intercept XrInstanceProperties and notify the user of our meta-layer.
 */
XrResult bionic_xrGetInstanceProperties(XrInstance instance, XrInstanceProperties *instanceProperties)
{
	XrResult ret = xr_lazy_call("xrGetInstanceProperties", instance, instanceProperties);

	strncat(instanceProperties->runtimeName, " (With ATL meta-layer)",
	        XR_MAX_RUNTIME_NAME_SIZE - 1 - strlen(instanceProperties->runtimeName));

	return ret;
}

XrResult bionic_xrCreateInstance(XrInstanceCreateInfo *createInfo, XrInstance *instance)
{
	/* so that we can use simpler (and faster) code, we replace extensions which we
	 * want to remove with this extension rather than delete them and copy the following
	 * extensions over
	 */
	const char *harmless_extension = "XR_KHR_opengl_es_enable";

	const char *extra_exts[] = {
		"XR_MNDX_egl_enable",
		"XR_EXT_local_floor",
	};

	const char *const *old_names = createInfo->enabledExtensionNames;
	const char **new_names;
	int new_count = createInfo->enabledExtensionCount + ARRAY_SIZE(extra_exts);

	//FIXME: Leak?
	new_names = malloc(sizeof(*new_names) * new_count);
	memcpy(new_names, old_names, createInfo->enabledExtensionCount * sizeof(*old_names));

	for (int i = 0; i < createInfo->enabledExtensionCount; i++) {
		if (!strcmp(new_names[i], "XR_KHR_android_create_instance"))
			new_names[i] = harmless_extension;
	}

	for (int i = 0; i < ARRAY_SIZE(extra_exts); i++)
		new_names[createInfo->enabledExtensionCount + i] = extra_exts[i];

	createInfo->enabledExtensionCount = new_count;
	createInfo->enabledExtensionNames = new_names;

	fprintf(stderr, "## xrCreateInstance: Enabled extensions:\n");
	for (int i = 0; i < createInfo->enabledExtensionCount; ++i)
		fprintf(stderr, "## ---- %s\n", createInfo->enabledExtensionNames[i]);

	return xr_lazy_call("xrCreateInstance", createInfo, instance);
}

XrResult bionic_xrCreateReferenceSpace(XrSession session, const XrReferenceSpaceCreateInfo *createInfo, XrSpace *space)
{
	fprintf(stderr, "xrCreateReferenceSpace(s=0x%w64x, info={rs_type=%d})\n", (uint64_t)session, createInfo->referenceSpaceType);

	//FIXME: this is sad for oculus refspace extension it assumes we have...
	if (createInfo->referenceSpaceType > 100)
		*(int *)(&createInfo->referenceSpaceType) = XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT;

	return xr_lazy_call("xrCreateReferenceSpace", session, createInfo, space);
}

/*
 * NOTE: Here we implement a NIH OpenXR API layer.
 *
 * We should make sure all our overrides are available via
 * "xrGetInstanceProcAddr" so the table below should contain
 * all our special functions.
 *
 * Maybe we should implement a proper OpenXR layer lib and inject
 * it in xrCreateInstance or extend our XR runtime in a way where
 * we won't need to help it.
 */

/*
 * HACK: The name prop here is deliberately an in-struct
 * string so we can do bsearch with plain strcmp.
 * So it must always be first.
 */
struct xr_proc_override {
	char name[64];
	PFN_xrVoidFunction *func;
};

#define XR_PROC_BIONIC(name) {#name, (void (**)(void))bionic_##name}

/* Please keep the alphabetical order */
static const struct xr_proc_override xr_proc_override_tbl[] = {
	XR_PROC_BIONIC(xrCreateInstance),
	XR_PROC_BIONIC(xrCreateReferenceSpace),
	XR_PROC_BIONIC(xrCreateSession),
	XR_PROC_BIONIC(xrGetInstanceProperties),
	XR_PROC_BIONIC(xrInitializeLoaderKHR),
};

XrResult bionic_xrGetInstanceProcAddr(XrInstance instance, const char *name, PFN_xrVoidFunction *func)
{
	printf("xrGetInstanceProcAddr(%s)\n", name);

	struct xr_proc_override *match = bsearch(name, xr_proc_override_tbl,
	                                         ARRAY_SIZE(xr_proc_override_tbl),
	                                         sizeof(xr_proc_override_tbl[0]),
	                                         (int (*)(const void *, const void *))strcmp);

	if (match) {
		*func = (PFN_xrVoidFunction)match->func;
		return XR_SUCCESS;
	}

	return xr_lazy_call("xrGetInstanceProcAddr", instance, name, func);
}

typedef void *ANativeActivity;

void ANativeActivity_setWindowFormat(ANativeActivity *activity, int32_t format)
{
	printf("STUB: %s called\n", __func__);
}

/* A remote producer renders to a local pbuffer. The Binder capability sends
 * sealed RGBA frames to the owner; GTK handles only ever stay in the owner. */
ANativeWindow *atl_native_window_remote(JNIEnv *env, jobject producer)
{
    jclass cls = (*env)->GetObjectClass(env, producer);
    jintArray size = (*env)->CallObjectMethod(env, producer, (*env)->GetMethodID(env, cls, "size", "()[I"));
    jint dims[2] = {0};
    if (size && !(*env)->ExceptionCheck(env)) (*env)->GetIntArrayRegion(env, size, 0, 2, dims);
    ANativeWindow *result = NULL;
    if (!(*env)->ExceptionCheck(env) && dims[0] > 0 && dims[1] > 0 && (int64_t)dims[0]*dims[1]*4 <= 64*1024*1024) {
        result = g_new0(ANativeWindow, 1);
        result->width = dims[0]; result->height = dims[1]; result->refcount = 1;
        (*env)->GetJavaVM(env, &result->jvm);
        result->remote_producer = (*env)->NewGlobalRef(env, producer);
        result->queue_frame = (*env)->GetMethodID(env, cls, "post", "(III)Z");
        if (!result->remote_producer || !result->queue_frame) { ANativeWindow_release(result); result = NULL; }
    }
    if (size) (*env)->DeleteLocalRef(env, size);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

static gboolean present_remote_frame(gpointer data)
{
    ANativeWindow *window = data;
    g_mutex_lock(&window->pending_lock);
    GBytes *bytes = window->pending_frame;
    int width = window->pending_width, height = window->pending_height;
    window->pending_frame = NULL;
    g_mutex_unlock(&window->pending_lock);
    if (!g_atomic_int_get(&window->abandoned)) {
        GdkTexture *texture = gdk_memory_texture_new(width, height,
            GDK_MEMORY_R8G8B8A8_PREMULTIPLIED, bytes, (size_t)width * 4);
        surface_view_widget_set_texture(SURFACE_VIEW_WIDGET(window->surface_view_widget), texture, FALSE);
    }
    g_bytes_unref(bytes);
    ANativeWindow_release(window);
    return G_SOURCE_REMOVE;
}

gboolean atl_native_window_present_fd(ANativeWindow *window, int fd, int width, int height)
{
    if (!window || !window->surface_view_widget || g_atomic_int_get(&window->abandoned) ||
        width <= 0 || height <= 0 || (int64_t)width * height * 4 > 64*1024*1024) return FALSE;
    size_t length = (size_t)width * height * 4;
    struct stat st;
    int seals = fcntl(fd, F_GET_SEALS);
    int required = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK;
    if (seals < 0 || (seals & required) != required || fstat(fd, &st) || st.st_size != (off_t)length) return FALSE;
    void *mapped = mmap(NULL, length, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) return FALSE;
    GBytes *bytes = g_bytes_new(mapped, length);
    munmap(mapped, length);
    g_mutex_lock(&window->pending_lock);
    gboolean schedule = window->pending_frame == NULL;
    if (window->pending_frame) g_bytes_unref(window->pending_frame);
    window->pending_frame = bytes; window->pending_width = width; window->pending_height = height;
    if (schedule) ANativeWindow_acquire(window);
    g_mutex_unlock(&window->pending_lock);
    if (schedule) g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, present_remote_frame, window, NULL);
    return TRUE;
}
