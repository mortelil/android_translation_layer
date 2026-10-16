#include <gdk/gdk.h>

#include "../defines.h"
#include "../generated_headers/android_graphics_drawable_DrawableContainer.h"

struct _ContainerPaintable {
	GObject parent_instance;
	GdkPaintable *child;
};
G_DECLARE_FINAL_TYPE(ContainerPaintable, container_paintable, CONTAINER, PAINTABLE, GObject)

static void container_paintable_snapshot(GdkPaintable *paintable, GdkSnapshot *snapshot, double width, double height)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(paintable);
	if (container->child)
		gdk_paintable_snapshot(container->child, snapshot, width, height);
}

static double container_paintable_get_intrinsic_aspect_ratio(GdkPaintable *paintable)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(paintable);
	if (container->child)
		return gdk_paintable_get_intrinsic_aspect_ratio(container->child);
	else
		return 0;
}

static int container_paintable_get_intrinsic_width(GdkPaintable *paintable)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(paintable);
	if (container->child)
		return gdk_paintable_get_intrinsic_width(container->child);
	else
		return 0;
}

static int container_paintable_get_intrinsic_height(GdkPaintable *paintable)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(paintable);
	if (container->child)
		return gdk_paintable_get_intrinsic_height(container->child);
	else
		return 0;
}

static void container_paintable_init(ContainerPaintable *container_paintable)
{
}

static void container_paintable_paintable_init(GdkPaintableInterface *iface)
{
	iface->snapshot = container_paintable_snapshot;
	iface->get_intrinsic_aspect_ratio = container_paintable_get_intrinsic_aspect_ratio;
	iface->get_intrinsic_width = container_paintable_get_intrinsic_width;
	iface->get_intrinsic_height = container_paintable_get_intrinsic_height;
}

static void container_paintable_dispose(GObject *object);
static void child_contents_changed(GdkPaintable *child, ContainerPaintable *container)
{
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(container));
}
static void child_size_changed(GdkPaintable *child, ContainerPaintable *container)
{
	gdk_paintable_invalidate_size(GDK_PAINTABLE(container));
}
static void container_paintable_class_init(ContainerPaintableClass *class)
{
	G_OBJECT_CLASS(class)->dispose = container_paintable_dispose;
}

G_DEFINE_TYPE_WITH_CODE(ContainerPaintable, container_paintable, G_TYPE_OBJECT,
                        G_IMPLEMENT_INTERFACE(GDK_TYPE_PAINTABLE, container_paintable_paintable_init))

static void container_paintable_dispose(GObject *object)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(object);
	if (container->child) {
		g_signal_handlers_disconnect_by_data(container->child, container);
		g_clear_object(&container->child);
	}
	G_OBJECT_CLASS(container_paintable_parent_class)->dispose(object);
}

JNIEXPORT jlong JNICALL Java_android_graphics_drawable_DrawableContainer_native_1constructor(JNIEnv *env, jobject this)
{
	return _INTPTR(g_object_new(container_paintable_get_type(), NULL));
}

JNIEXPORT void JNICALL Java_android_graphics_drawable_DrawableContainer_native_1selectChild(JNIEnv *env, jobject this, jlong ptr, jlong child_ptr)
{
	ContainerPaintable *container = CONTAINER_PAINTABLE(_PTR(ptr));
	GdkPaintable *child = child_ptr ? GDK_PAINTABLE(_PTR(child_ptr)) : NULL;
	if (child == container->child) return;
	if (container->child)
		g_signal_handlers_disconnect_by_data(container->child, container);
	g_set_object(&container->child, child);
	if (child) {
		g_signal_connect(child, "invalidate-contents", G_CALLBACK(child_contents_changed), container);
		g_signal_connect(child, "invalidate-size", G_CALLBACK(child_size_changed), container);
	}
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(container));
	gdk_paintable_invalidate_size(GDK_PAINTABLE(container));
}
