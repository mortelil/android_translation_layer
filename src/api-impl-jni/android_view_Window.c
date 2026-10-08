#include <gtk/gtk.h>

#include "defines.h"
#include "util.h"

#include "generated_headers/android_view_Window.h"

/* Optional pixel-coordinate root. Android measures and renders in physical
 * pixels inside this widget; GTK applies the inverse transform exactly once
 * when composing the root and when translating pointer coordinates. */
typedef struct {
	GtkWidget parent;
	GtkWidget *child;
	double scale;
} ATLScaleRoot;
typedef GtkWidgetClass ATLScaleRootClass;
G_DEFINE_TYPE(ATLScaleRoot, atl_scale_root, GTK_TYPE_WIDGET)

static GtkSizeRequestMode scale_root_request_mode(GtkWidget *widget)
{
	return gtk_widget_get_request_mode(((ATLScaleRoot *)widget)->child);
}

static void scale_root_measure(GtkWidget *widget, GtkOrientation orientation, int for_size,
                               int *minimum, int *natural, int *min_baseline, int *nat_baseline)
{
	ATLScaleRoot *root = (ATLScaleRoot *)widget;
	int min_px, nat_px;
	gtk_widget_measure(root->child, orientation, for_size < 0 ? -1 : (int)(for_size * root->scale + 0.5),
	                   &min_px, &nat_px, NULL, NULL);
	*minimum = (int)(min_px / root->scale + 0.999);
	*natural = (int)(nat_px / root->scale + 0.999);
	*min_baseline = *nat_baseline = -1;
}

static void scale_root_allocate(GtkWidget *widget, int width, int height, int baseline)
{
	ATLScaleRoot *root = (ATLScaleRoot *)widget;
	int pixel_width = (int)(width * root->scale + 0.5);
	int pixel_height = (int)(height * root->scale + 0.5);
	JNIEnv *env = get_jni_env();
	jclass display = (*env)->FindClass(env, "android/view/Display");
	_SET_STATIC_INT_FIELD(display, "window_width", pixel_width);
	_SET_STATIC_INT_FIELD(display, "window_height", pixel_height);
	(*env)->DeleteLocalRef(env, display);
	gtk_widget_allocate(root->child, pixel_width, pixel_height, -1,
	                    gsk_transform_scale(NULL, 1.0 / root->scale, 1.0 / root->scale));
}

static void scale_root_snapshot(GtkWidget *widget, GtkSnapshot *snapshot)
{
	gtk_widget_snapshot_child(widget, ((ATLScaleRoot *)widget)->child, snapshot);
}

static void scale_root_dispose(GObject *object)
{
	ATLScaleRoot *root = (ATLScaleRoot *)object;
	if (root->child) {
		gtk_widget_unparent(root->child);
		root->child = NULL;
	}
	G_OBJECT_CLASS(atl_scale_root_parent_class)->dispose(object);
}

static void atl_scale_root_class_init(ATLScaleRootClass *klass)
{
	klass->get_request_mode = scale_root_request_mode;
	klass->measure = scale_root_measure;
	klass->size_allocate = scale_root_allocate;
	klass->snapshot = scale_root_snapshot;
	G_OBJECT_CLASS(klass)->dispose = scale_root_dispose;
}

static void atl_scale_root_init(ATLScaleRoot *root) { root->scale = 1; }

JNIEXPORT void JNICALL Java_android_view_Window_set_1widget_1as_1root(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	if (getenv("ATL_RENDER_SCALE")) {
		jclass metrics = (*env)->FindClass(env, "android/util/DisplayMetrics");
		double scale = (*env)->GetStaticIntField(env, metrics, _STATIC_FIELD_ID(metrics, "DENSITY_DEVICE", "I")) / 160.0;
		(*env)->DeleteLocalRef(env, metrics);
		GtkWidget *existing = gtk_window_get_child(gtk_window);
		if (existing && G_TYPE_CHECK_INSTANCE_TYPE(existing, atl_scale_root_get_type()) && ((ATLScaleRoot *)existing)->child == gtk_widget)
			return;
		ATLScaleRoot *root = g_object_new(atl_scale_root_get_type(), NULL);
		root->scale = scale;
		root->child = gtk_widget;
		gtk_widget_set_parent(gtk_widget, GTK_WIDGET(root));
		gtk_window_set_child(gtk_window, GTK_WIDGET(root));
		return;
	}
	if (gtk_widget != gtk_window_get_child(gtk_window)) {
		gtk_window_set_child(gtk_window, gtk_widget);
	}
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1title(JNIEnv *env, jobject this, jlong window, jstring title_jstr)
{
	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	const char *title = (*env)->GetStringUTFChars(env, title_jstr, NULL);
	gtk_window_set_title(gtk_window, title);
	(*env)->ReleaseStringUTFChars(env, title_jstr, title);
}

// FIXME put this in a header file
struct input_queue {
	int fd;
	GtkEventController *controller;
};

JNIEXPORT void JNICALL Java_android_view_Window_take_1input_1queue(JNIEnv *env, jobject this, jlong native_window, jobject callback, jobject queue)
{
	GtkWidget *window = _PTR(native_window);
	printf("in Java_android_view_Window_take_1input_1queue\n");

	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_widget_add_controller(window, controller);

	struct input_queue *input_queue = malloc(sizeof(struct input_queue));
	input_queue->fd = -1;
	input_queue->controller = controller;

	_SET_LONG_FIELD(queue, "native_ptr", _INTPTR(input_queue));

	// we need to keep these for later, so they can be called after OnCreate finishes
	g_object_set_data(G_OBJECT(window), "input_queue_callback", (gpointer)_REF(callback));
	g_object_set_data(G_OBJECT(window), "input_queue", (gpointer)_REF(queue));
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1layout(JNIEnv *env, jobject this, jlong window, jint width, jint height)
{
	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	if (width > 0 && height > 0)
		gtk_window_set_default_size(gtk_window, width, height);
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1jobject(JNIEnv *env, jclass this, jlong window, jobject window_jobj)
{
	g_object_set_data(G_OBJECT(window), "jobject", _WEAK_REF(window_jobj));
}

JNIEXPORT void JNICALL Java_android_view_Window_remove_1gtk_1background(JNIEnv *env, jobject this, jlong window)
{
	gtk_widget_add_css_class(GTK_WIDGET(_PTR(window)), "ATL-no-background");
}

#define FLOAT_TO_POINTER(f) GINT_TO_POINTER(*(uint32_t *)(&f))
#define POINTER_TO_FLOAT(p) (*((float *)(&p)))

void set_brightness_done(GObject *source_object, GAsyncResult *res, gpointer data)
{
	float brightness = POINTER_TO_FLOAT(data);
	GVariant *result = g_dbus_connection_call_finish(G_DBUS_CONNECTION(source_object), res, NULL);
	if (result) {
		g_variant_unref(result);
	} else { // GNOME settings daemon not available. Try fallback to sysfs
		GDir *dir = g_dir_open("/sys/class/backlight", 0, NULL);
		if (!dir)
			return;

		const gchar *name;
		gchar *path;
		FILE *fp;
		while ((name = g_dir_read_name(dir))) {
			path = g_build_filename("/sys/class/backlight/", name, "/max_brightness", NULL);
			fp = fopen(path, "r");
			if (!fp) {
				g_printerr("Failed to read %s: %s\n", path, g_strerror(errno));
				g_free(path);
				continue;
			}
			g_free(path);
			int max = 0;
			fscanf(fp, "%d", &max);
			fclose(fp);

			path = g_build_filename("/sys/class/backlight/", name, "/brightness", NULL);
			fp = fopen(path, "w");
			if (!fp) {
				g_printerr("Failed to write %s: %s\n", path, g_strerror(errno));
				g_free(path);
				continue;
			}
			g_free(path);
			fprintf(fp, "%d", (int)(brightness * max));
			fclose(fp);
		}

		g_dir_close(dir);
	}
}

JNIEXPORT void JNICALL Java_android_view_Window_set_1screen_1brightness(JNIEnv *env, jobject this, jfloat brightness)
{
	GDBusConnection *connection = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, NULL);
	if (!connection)
		return;

	g_dbus_connection_call(connection, "org.gnome.SettingsDaemon.Power", "/org/gnome/SettingsDaemon/Power", "org.freedesktop.DBus.Properties", "Set",
	                       g_variant_new("(ssv)", "org.gnome.SettingsDaemon.Power.Screen", "Brightness", g_variant_new_int32(brightness * 100)),
	                       NULL, G_DBUS_CALL_FLAGS_NONE, -1, NULL, set_brightness_done, FLOAT_TO_POINTER(brightness));

	g_object_unref(connection);
}
