#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "AdapterView.h"
#include "WrapperWidget.h"

#include "../generated_headers/android_view_View.h"
#include "../generated_headers/android_widget_Spinner.h"

static void range_list_model_init(RangeListModel *list_model) {}
static void range_list_model_class_init(RangeListModelClass *class) {}

static guint range_list_model_get_n_items(GListModel *list_model)
{
	return (RANGE_LIST_MODEL(list_model))->n_items;
}

static gpointer range_list_model_get_item(GListModel *list_model, guint index)
{
	if (index >= RANGE_LIST_MODEL(list_model)->n_items)
		return NULL;
	RangeListItem *item = g_object_new(range_list_item_get_type(), NULL);
	item->model = RANGE_LIST_MODEL(list_model);
	return item;
}

static void range_list_model_model_init(GListModelInterface *iface)
{
	iface->get_n_items = range_list_model_get_n_items;
	iface->get_item_type = (GType (*)(GListModel *))range_list_item_get_type;
	iface->get_item = range_list_model_get_item;
}

G_DEFINE_TYPE_WITH_CODE(RangeListModel, range_list_model, G_TYPE_OBJECT,
                        G_IMPLEMENT_INTERFACE(G_TYPE_LIST_MODEL, range_list_model_model_init))

static void range_list_item_class_init(RangeListItemClass *cls) {}
static void range_list_item_init(RangeListItem *self) {}
G_DEFINE_TYPE(RangeListItem, range_list_item, G_TYPE_OBJECT)

static void bind_listitem_cb(GtkListItemFactory *factory, GtkListItem *list_item, jobject this)
{
	JNIEnv *env = get_jni_env();

	guint index = gtk_list_item_get_position(list_item);
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_list_item_get_child(list_item));
	RangeListModel *model = RANGE_LIST_ITEM(gtk_list_item_get_item(list_item))->model;
	int n_items = g_list_model_get_n_items(G_LIST_MODEL(model));
	if (index >= n_items) {
		printf("invalid index: %d >= %d\n", index, n_items);
		exit(0);
	}
	jmethodID getView = _METHOD(_CLASS(model->adapter), "getDropDownView", "(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;");
	jobject view = (*env)->CallObjectMethod(env, model->adapter, getView, index, wrapper ? wrapper->jobj : NULL, model->jobject);
	view = _REF(view);
	GtkWidget *child = gtk_widget_get_parent(GTK_WIDGET(_PTR(_GET_LONG_FIELD(view, "widget"))));
	jobject background_drawable = _GET_OBJ_FIELD(this, "popupBackground", "Landroid/graphics/drawable/Drawable;");
	GdkPaintable *background_paintable = background_drawable ? GDK_PAINTABLE(_PTR(_GET_LONG_FIELD(background_drawable, "paintable"))) : NULL;
	wrapper_widget_set_background(WRAPPER_WIDGET(child), background_paintable);
	gtk_list_item_set_child(list_item, child);
}

static void on_selected_changed(GtkDropDown *dropdown, GParamSpec *pspec, jobject spinner);

static void spinner_pressed(GtkGestureClick *gesture, int n, double x, double y, gpointer data)
{
	GtkWidget *wrapper = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(gesture));
	GdkEvent *event = gtk_event_controller_get_current_event(GTK_EVENT_CONTROLLER(gesture));
	/* A popup has its own surface. Its row clicks must not reopen the spinner. */
	if (event && gdk_event_get_surface(event) == gtk_native_get_surface(gtk_widget_get_native(wrapper)))
		gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
	else
		gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_DENIED);
}

static void spinner_released(GtkGestureClick *gesture, int n, double x, double y, gpointer data)
{
	JNIEnv *env = get_jni_env();
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(gesture)));
	(*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.view.performClick);
	if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
}

JNIEXPORT jlong JNICALL Java_android_widget_Spinner_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	WrapperWidget *wrapper = g_object_ref(WRAPPER_WIDGET(wrapper_widget_new()));
	wrapper_widget_set_jobject(wrapper, env, this);
	GtkListItemFactory *factory = gtk_signal_list_item_factory_new();
	g_signal_connect(factory, "bind", G_CALLBACK(bind_listitem_cb), wrapper->jobj);
	RangeListModel *model = g_object_new(range_list_model_get_type(), NULL);
	GtkWidget *dropdown = gtk_drop_down_new(G_LIST_MODEL(model), NULL);
	gtk_drop_down_set_factory(GTK_DROP_DOWN(dropdown), factory);
	model->list_view = dropdown;
	model->jobject = _WEAK_REF(this);
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), dropdown);
	gtk_widget_set_name(dropdown, "Spinner");
	/* The Android subtree is in density-scaled pixels. GtkDropDown's built-in
	 * arrow otherwise stays at GTK's 16px icon size and is shrunk a second
	 * time by ATLScaleRoot. Scope the density adjustment to Spinner arrows;
	 * window decorations and application-provided drawables are unaffected. */
	GdkDisplay *display = gtk_widget_get_display(dropdown);
	if (!g_object_get_data(G_OBJECT(display), "atl-spinner-density-style")) {
		jclass metrics = (*env)->FindClass(env, "android/util/DisplayMetrics");
		double density = (*env)->GetStaticIntField(env, metrics,
		    _STATIC_FIELD_ID(metrics, "DENSITY_DEVICE", "I")) / 160.0;
		(*env)->DeleteLocalRef(env, metrics);
		GtkCssProvider *provider = gtk_css_provider_new();
		char *css = g_strdup_printf("#Spinner arrow { min-width: %dpx; min-height: %dpx; -gtk-icon-size: %dpx; }",
		    (int)(16 * density + 0.5), (int)(16 * density + 0.5), (int)(16 * density + 0.5));
		gtk_css_provider_load_from_string(provider, css);
		g_free(css);
		gtk_style_context_add_provider_for_display(display, GTK_STYLE_PROVIDER(provider),
		    GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
		g_object_set_data_full(G_OBJECT(display), "atl-spinner-density-style", provider, g_object_unref);
	}

	g_signal_connect(dropdown, "notify::selected", G_CALLBACK(on_selected_changed), wrapper->jobj);
	/* Invoke the Java override before GtkDropDown opens its own menu. AppCompat
	 * supplies a PopupWindow; opening both menus steals the first row click. */
	GListModel *controllers = gtk_widget_observe_controllers(GTK_WIDGET(wrapper));
	for (guint i = g_list_model_get_n_items(controllers); i > 0; i--) {
		GtkEventController *controller = g_list_model_get_item(controllers, i - 1);
		if (GTK_IS_GESTURE_CLICK(controller)) gtk_widget_remove_controller(GTK_WIDGET(wrapper), controller);
		g_object_unref(controller);
	}
	g_object_unref(controllers);
	GtkGesture *click = gtk_gesture_click_new();
	gtk_event_controller_set_propagation_phase(GTK_EVENT_CONTROLLER(click), GTK_PHASE_CAPTURE);
	g_signal_connect(click, "pressed", G_CALLBACK(spinner_pressed), NULL);
	g_signal_connect(click, "released", G_CALLBACK(spinner_released), NULL);
	gtk_widget_add_controller(GTK_WIDGET(wrapper), GTK_EVENT_CONTROLLER(click));
	return _INTPTR(dropdown);
}

JNIEXPORT jboolean JNICALL Java_android_widget_Spinner_native_1performClick(JNIEnv *env, jobject this, jlong ptr)
{
	return gtk_widget_activate(gtk_widget_get_first_child(GTK_WIDGET(_PTR(ptr))));
}

JNIEXPORT void JNICALL Java_android_widget_Spinner_native_1setAdapter(JNIEnv *env, jobject this, jlong widget_ptr, jobject adapter)
{
	GtkDropDown *dropdown = GTK_DROP_DOWN(_PTR(widget_ptr));
	RangeListModel *model = RANGE_LIST_MODEL(gtk_drop_down_get_model(dropdown));

	if (model->adapter)
		_UNREF(model->adapter);
	model->adapter = adapter ? _REF(adapter) : NULL;
	guint old_n_items = model->n_items;
	model->n_items = adapter ? (*env)->CallIntMethod(env, adapter, _METHOD(_CLASS(adapter), "getCount", "()I")) : 0;
	g_list_model_items_changed(G_LIST_MODEL(model), 0, old_n_items, model->n_items);
}

static void on_selected_changed(GtkDropDown *dropdown, GParamSpec *pspec, jobject spinner)
{
	JNIEnv *env = get_jni_env();
	guint selected = gtk_drop_down_get_selected(dropdown);
	jmethodID changed = _METHOD(_CLASS(spinner), "onNativeSelectionChanged", "(I)V");
	(*env)->CallVoidMethod(env, spinner, changed, selected == GTK_INVALID_LIST_POSITION ? -1 : (jint)selected);
}

JNIEXPORT void JNICALL Java_android_widget_Spinner_native_1setSelection(JNIEnv *env, jobject this, jlong ptr, jint position)
{
	GtkDropDown *dropdown = GTK_DROP_DOWN(_PTR(ptr));
	guint count = g_list_model_get_n_items(gtk_drop_down_get_model(dropdown));
	gtk_drop_down_set_selected(dropdown, position >= 0 && (guint)position < count ? (guint)position : GTK_INVALID_LIST_POSITION);
}

JNIEXPORT void JNICALL Java_android_widget_Spinner_native_1setBackgroundDrawable(JNIEnv *env, jobject this, jlong widget_ptr, jlong paintable_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	// background must be set to the GtkToggleButton which is the first child of the GtkDropDown
	Java_android_view_View_native_1setBackgroundDrawable(env, this, _INTPTR(gtk_widget_get_first_child(widget)), paintable_ptr);
}

JNIEXPORT void JNICALL Java_android_widget_Spinner_native_1setBackgroundColor(JNIEnv *env, jobject this, jlong widget_ptr, jint color)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	// background must be set to the GtkToggleButton which is the first child of the GtkDropDown
	Java_android_view_View_native_1setBackgroundColor(env, this, _INTPTR(gtk_widget_get_first_child(widget)), color);
}
