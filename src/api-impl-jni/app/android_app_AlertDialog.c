#include <gtk/gtk.h>
#include <jni.h>

#include "../defines.h"
#include "../util.h"
#include "../android_view_Window.h"
#include "../widgets/AndroidLineBox.h"
#include "../generated_headers/android_app_AlertDialog.h"

struct _ListEntry {
	GObject parent;
	const char *text;
};
G_DECLARE_FINAL_TYPE(ListEntry, list_entry, ATL, LIST_ENTRY, GObject);
static void list_entry_class_init(ListEntryClass *cls) {}
static void list_entry_init(ListEntry *self) {}
G_DEFINE_TYPE(ListEntry, list_entry, G_TYPE_OBJECT)

static void setup_listitem_cb(GtkListItemFactory *factory, GtkListItem *list_item)
{
	gtk_list_item_set_child(list_item, gtk_label_new(""));
}

static void bind_listitem_cb(GtkListItemFactory *factory, GtkListItem *list_item)
{
	GtkWidget *label = gtk_list_item_get_child(list_item);
	ListEntry *entry = gtk_list_item_get_item(list_item);

	atl_safe_gtk_label_set_text(GTK_LABEL(label), entry->text);
}

static void activate_cb(GtkListView *list, guint position, gpointer user_data)
{
	JNIEnv *env = get_jni_env();

	jobject this = g_object_get_data(G_OBJECT(list), "this");
	jobject on_click = g_object_get_data(G_OBJECT(list), "on_click");
	jmethodID on_click_method = _METHOD(_CLASS(on_click), "onClick", "(Landroid/content/DialogInterface;I)V");
	(*env)->CallVoidMethod(env, on_click, on_click_method, this, position);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_app_AlertDialog_nativeConstruct(JNIEnv *env, jobject this, jlong ptr, jlong message_ptr, jlong button_positive_ptr, jlong button_negative_ptr, jlong button_neutral_ptr)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	GtkBox *content_area = GTK_BOX(gtk_box_new(GTK_ORIENTATION_VERTICAL, 1));

	GtkWidget *message_box = GTK_WIDGET(_PTR(message_ptr));
	GtkWidget *label = gtk_widget_get_first_child(message_box);
	if (ATL_IS_LINE_BOX(label)) label = atl_line_box_get_label(ATL_LINE_BOX(label));
	gtk_label_set_max_width_chars(GTK_LABEL(label), 50);
	/* Keep the message in the real Java decor hierarchy. Retain the decor
	 * while replacing the window's old (possibly scaled) content root. */
	GtkWidget *decor = g_object_ref(atl_window_get_content(dialog));
	gtk_window_set_child(dialog, NULL);
	gtk_box_append(content_area, decor);
	g_object_unref(decor);

	GtkListItemFactory *factory = gtk_signal_list_item_factory_new();
	g_signal_connect(factory, "setup", G_CALLBACK(setup_listitem_cb), NULL);
	g_signal_connect(factory, "bind", G_CALLBACK(bind_listitem_cb), NULL);
	GtkListView *list = GTK_LIST_VIEW(gtk_list_view_new(NULL, factory));
	gtk_list_view_set_single_click_activate(list, TRUE);
	g_signal_connect(list, "activate", G_CALLBACK(activate_cb), NULL);
	gtk_box_append(content_area, GTK_WIDGET(list));
	g_object_set_data_full(G_OBJECT(dialog), "list", g_object_ref(list), g_object_unref);

	gtk_box_append(content_area, gtk_widget_get_parent(GTK_WIDGET(_PTR(button_positive_ptr))));
	gtk_box_append(content_area, gtk_widget_get_parent(GTK_WIDGET(_PTR(button_negative_ptr))));
	gtk_box_append(content_area, gtk_widget_get_parent(GTK_WIDGET(_PTR(button_neutral_ptr))));

	atl_window_set_content(env, dialog, GTK_WIDGET(content_area));
}

JNIEXPORT void JNICALL Java_android_app_AlertDialog_nativeSetItems(JNIEnv *env, jobject this, jlong ptr, jobjectArray items, jobject on_click)
{
	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));
	GtkListView *list = g_object_get_data(G_OBJECT(dialog), "list");

	GListStore *store = g_list_store_new(list_entry_get_type());
	int stringCount = (*env)->GetArrayLength(env, items);
	for (int i = 0; i < stringCount; i++) {
		ListEntry *entry = ATL_LIST_ENTRY(g_object_new(list_entry_get_type(), NULL));
		entry->text = _CSTRING((*env)->GetObjectArrayElement(env, items, i));
		g_list_store_append(store, entry);
	}
	GtkSelectionModel *model = GTK_SELECTION_MODEL(gtk_single_selection_new(G_LIST_MODEL(store)));
	gtk_list_view_set_model(list, model);

	g_object_set_data(G_OBJECT(list), "this", _REF(this));
	g_object_set_data(G_OBJECT(list), "on_click", _REF(on_click));
}
