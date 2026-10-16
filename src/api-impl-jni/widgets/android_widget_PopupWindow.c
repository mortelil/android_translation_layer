#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "../android_view_Window.h"

static double popup_density(JNIEnv *env)
{
	jclass metrics = (*env)->FindClass(env, "android/util/DisplayMetrics");
	double scale = (*env)->GetStaticIntField(env, metrics,
	    _STATIC_FIELD_ID(metrics, "DENSITY_DEVICE", "I")) / 160.0;
	(*env)->DeleteLocalRef(env, metrics);
	return scale;
}


#include "WrapperWidget.h"
#include "PopupPlacement.h"

#include "../generated_headers/android_widget_PopupWindow.h"

JNIEXPORT jlong JNICALL Java_android_widget_PopupWindow_native_1constructor(JNIEnv *env, jobject this)
{
	GtkWidget *popover = g_object_ref_sink(gtk_popover_new());
	gtk_popover_set_has_arrow(GTK_POPOVER(popover), false);
	gtk_widget_add_css_class(popover, "ATL-popup");
	/* The app supplies the background. GTK padding/arrow/shadow changes both
	 * the Android content size and the requested screen coordinates. */
	GdkDisplay *display = gtk_widget_get_display(popover);
	if (!g_object_get_data(G_OBJECT(display), "atl-popup-css")) {
		GtkCssProvider *css = gtk_css_provider_new();
		gtk_css_provider_load_from_string(css,
			"popover.ATL-popup > contents { padding: 0; border: none; box-shadow: none; background: transparent; }");
		gtk_style_context_add_provider_for_display(display,
			GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
		g_object_set_data_full(G_OBJECT(display), "atl-popup-css", css, g_object_unref);
	}
	gtk_widget_set_name(popover, "PopupWindow");
	/* autohiding works by the widget grabbing events, which is not something apps expect */
	gtk_popover_set_autohide(GTK_POPOVER(popover), false);
	return _INTPTR(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setContentView(JNIEnv *env, jobject this, jlong popover_ptr, jlong content_ptr)
{
	GtkWidget *content = content_ptr ? gtk_widget_get_parent(GTK_WIDGET(_PTR(content_ptr))) : NULL;
	GtkWidget *existing = gtk_popover_get_child(GTK_POPOVER(_PTR(popover_ptr)));
	if (existing && gtk_widget_get_first_child(existing) == content) return;
	/* Popovers have their own surface and do not inherit the window's
	 * content-density transform. Do not update Display from popup allocation. */
	gtk_popover_set_child(GTK_POPOVER(_PTR(popover_ptr)),
	    content ? atl_scaled_content_new(env, content, FALSE) : NULL);
}

/* Keep a strong reference on the popover across reparenting (constructor).
 * All geometry is relative to the app root, never the monitor or title bar. */
JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1show(JNIEnv *env, jobject this,
        jlong popover_ptr, jlong anchor_ptr, jlong frame_ptr, jint anchor_width, jint anchor_height, jint x, jint y,
        jint gravity, jboolean absolute, jboolean attached, jboolean overlap)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(popover_ptr));
	GtkPopover *popover = GTK_POPOVER(widget);
	GtkWidget *anchor = gtk_widget_get_parent(GTK_WIDGET(_PTR(anchor_ptr)));
	GtkWidget *frame = gtk_widget_get_parent(GTK_WIDGET(_PTR(frame_ptr)));
	if (!gtk_widget_get_root(anchor)) return;
	if (gtk_widget_get_parent(widget) != anchor) {
		if (gtk_widget_get_parent(widget)) gtk_widget_unparent(widget);
		gtk_widget_set_parent(widget, anchor);
	}
	/* An unmapped popover reports zero preferred size. Measure its visible
	 * content instead, respecting the explicit PopupWindow size request. */
	GtkWidget *content = gtk_popover_get_child(popover);
	if (!content) return;
	int width, height, requested_width, requested_height;
	gtk_widget_measure(content, GTK_ORIENTATION_HORIZONTAL, -1, NULL, &width, NULL, NULL);
	gtk_widget_get_size_request(widget, &requested_width, &requested_height);
	width = MAX(width, requested_width);
	gtk_widget_measure(content, GTK_ORIENTATION_VERTICAL, width, NULL, &height, NULL, NULL);
	height = MAX(height, requested_height);
	/* GTK measures the popup in logical pixels, while the anchor/frame and
	 * Android gravity calculation use Android pixels. */
	double density = popup_density(env);
	width = (int)(width * density + 0.5);
	height = (int)(height * density + 0.5);
	graphene_rect_t bounds;
	if (!gtk_widget_compute_bounds(anchor, frame, &bounds)) return;
	AtlPopupPoint p = atl_popup_position(bounds.origin.x, bounds.origin.y,
		anchor_width, anchor_height,
		gtk_widget_get_width(frame), gtk_widget_get_height(frame),
		width, height, x, y, gravity, absolute, attached, overlap);
	if (getenv("ATL_TRACE_POPUP"))
		g_printerr("popup anchor=%g,%g %dx%d frame=%dx%d content=%dx%d gravity=%d absolute=%d result=%d,%d\n",
			bounds.origin.x, bounds.origin.y, anchor_width, anchor_height,
			gtk_widget_get_width(frame), gtk_widget_get_height(frame), width, height, gravity, absolute, p.x, p.y);
	/* GTK anchors the top edge below a nonempty rectangle. Remove its one
	 * pixel height so (p.x,p.y) remains the requested top-left corner. */
	GdkRectangle rect = {p.x - (int)bounds.origin.x,
		p.y - (int)bounds.origin.y - 1, 1, 1};
	gtk_widget_set_halign(widget, gtk_widget_get_direction(widget) == GTK_TEXT_DIR_RTL
		? GTK_ALIGN_END : GTK_ALIGN_START);
	gtk_popover_set_position(popover, GTK_POS_BOTTOM);
	gtk_popover_set_offset(popover, 0, 0);
	gtk_popover_set_pointing_to(popover, &rect);
	gtk_popover_present(popover);
	gtk_popover_popup(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setWidth(JNIEnv *env, jobject this, jlong popover_ptr, jint width)
{
	int height;
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_get_size_request(popover, NULL, &height);
	gtk_widget_set_size_request(popover, width < 0 ? width : (int)(width / popup_density(env) + 0.5), height);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setHeight(JNIEnv *env, jobject this, jlong popover_ptr, jint height)
{
	int width;
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_get_size_request(popover, &width, NULL);
	gtk_widget_set_size_request(popover, width, height < 0 ? height : (int)(height / popup_density(env) + 0.5));
}

JNIEXPORT jint JNICALL Java_android_widget_PopupWindow_native_1getWidth(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	GtkRequisition natural_size;
	gtk_widget_get_preferred_size(popover, &natural_size, NULL);
	return (int)(natural_size.width * popup_density(env) + 0.5);
}

JNIEXPORT jint JNICALL Java_android_widget_PopupWindow_native_1getHeight(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	GtkRequisition natural_size;
	gtk_widget_get_preferred_size(popover, &natural_size, NULL);
	return (int)(natural_size.height * popup_density(env) + 0.5);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setTouchable(JNIEnv *env, jobject this, jlong popover_ptr, jboolean touchable)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	gtk_widget_set_sensitive(popover, touchable);
}

JNIEXPORT jboolean JNICALL Java_android_widget_PopupWindow_native_1isTouchable(JNIEnv *env, jobject this, jlong popover_ptr)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(popover_ptr));
	return gtk_widget_is_sensitive(popover);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1setTouchModal(JNIEnv *env, jobject this, jlong popover_ptr, jboolean touch_modal)
{
	GtkPopover *popover = GTK_POPOVER(_PTR(popover_ptr));
	/* FIXME: we should only add grab (not autohide), however we need to remove it again in umap;
	 * GtkPopover is not final, so we should subclass it and check whether it's modal in map/unmap
	 * to add/remove grab, which is the desired part of what GtkPopover does with autohide enabled */
	gtk_popover_set_autohide(popover, touch_modal);
}

static void on_closed_cb(GtkPopover *popover, jobject listener)
{
	JNIEnv *env = get_jni_env();
	jmethodID onDismiss = _METHOD(_CLASS(listener), "onDismiss", "()V");
	(*env)->CallVoidMethod(env, listener, onDismiss);
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_setOnDismissListener(JNIEnv *env, jobject this, jobject listener)
{
	GtkWidget *popover = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "popover")));
	if (listener)
		g_signal_connect(popover, "closed", G_CALLBACK(on_closed_cb), _REF(listener));
}

JNIEXPORT jboolean JNICALL Java_android_widget_PopupWindow_native_1isShowing(JNIEnv *env, jobject this, jlong popover_ptr)
{
	return gtk_widget_get_visible(GTK_WIDGET(_PTR(popover_ptr)));
}

JNIEXPORT void JNICALL Java_android_widget_PopupWindow_native_1dismiss(JNIEnv *env, jobject this, jlong popover_ptr)
{
	gtk_popover_popdown(GTK_POPOVER(_PTR(popover_ptr)));
}
