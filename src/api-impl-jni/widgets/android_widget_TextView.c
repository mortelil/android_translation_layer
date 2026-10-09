#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"

#include "WrapperWidget.h"
#include "../graphics/AndroidTextAttributes.h"
#include "../graphics/AndroidTypeface.h"

#include "../generated_headers/android_widget_TextView.h"

static GtkLabel *box_get_label(JNIEnv *env, GtkWidget *box)
{
	GtkWidget *label = gtk_widget_get_last_child(GTK_WIDGET(box));
	if (!GTK_IS_LABEL(label))
		label = gtk_widget_get_prev_sibling(label);
	return GTK_LABEL(label);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setShowSoftInputOnFocus(JNIEnv *env, jobject this, jlong ptr, jboolean show)
{
	GtkWidget *widget = _PTR(ptr);
	if (!GTK_IS_TEXT(widget)) return; // Non-editable labels have no input method.
	GtkInputHints hints = gtk_text_get_input_hints(GTK_TEXT(widget));
	if (show) hints &= ~GTK_INPUT_HINT_INHIBIT_OSK;
	else hints |= GTK_INPUT_HINT_INHIBIT_OSK;
	gtk_text_set_input_hints(GTK_TEXT(widget), hints);
}

struct text_css_provider {
	GtkCssProvider *provider;
	GdkDisplay *display;
};

static void text_css_provider_free(gpointer data)
{
	struct text_css_provider *style = data;
	gtk_style_context_remove_provider_for_display(style->display, GTK_STYLE_PROVIDER(style->provider));
	g_object_unref(style->provider);
	g_object_unref(style->display);
	g_free(style);
}

static void set_text_css(JNIEnv *env, jlong ptr, const char *key, const char *css)
{
	GtkWidget *widget = _PTR(ptr);
	if (GTK_IS_BOX(widget)) widget = GTK_WIDGET(box_get_label(env, widget));
	if (!GTK_IS_TEXT(widget) && !GTK_IS_LABEL(widget)) return;
	// Selection/placeholder are child CSS nodes: a provider on the parent style
	// context alone does not reach them. Scope a display provider to this widget.
	char *name = g_strdup_printf("atl-text-%p", (void *)widget);
	gtk_widget_add_css_class(widget, name);
	char *scoped = css[0] == '*' ? g_strdup_printf(".%s%s", name, css + 1)
	                            : g_strdup_printf(".%s %s", name, css);
	struct text_css_provider *style = g_new0(struct text_css_provider, 1);
	style->provider = gtk_css_provider_new();
	style->display = g_object_ref(gtk_widget_get_display(widget));
	gtk_css_provider_load_from_string(style->provider, scoped);
	gtk_style_context_add_provider_for_display(style->display, GTK_STYLE_PROVIDER(style->provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data_full(G_OBJECT(widget), key, style, text_css_provider_free);
	g_free(scoped);
	g_free(name);
}

static char *text_color_string(jint argb)
{
	GdkRGBA color = {((argb >> 16) & 255) / 255.f, ((argb >> 8) & 255) / 255.f,
	                 (argb & 255) / 255.f, ((guint32)argb >> 24) / 255.f};
	return gdk_rgba_to_string(&color);
}

static void set_text_node_color(JNIEnv *env, jlong ptr, jint argb, const char *key, const char *node, const char *property)
{
	char *rgba = text_color_string(argb);
	char *css = g_strdup_printf("%s { %s: %s; }", node, property, rgba);
	set_text_css(env, ptr, key, css);
	g_free(css);
	g_free(rgba);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setShadowLayer(JNIEnv *env, jobject this, jlong ptr, jfloat radius, jfloat dx, jfloat dy, jint argb)
{
	if (radius <= 0) {
		set_text_css(env, ptr, "atl-text-shadow", "* { text-shadow: none; }");
		return;
	}
	char x[G_ASCII_DTOSTR_BUF_SIZE], y[G_ASCII_DTOSTR_BUF_SIZE], blur[G_ASCII_DTOSTR_BUF_SIZE];
	g_ascii_dtostr(x, sizeof(x), dx);
	g_ascii_dtostr(y, sizeof(y), dy);
	g_ascii_dtostr(blur, sizeof(blur), radius);
	char *rgba = text_color_string(argb);
	char *css = g_strdup_printf("* { text-shadow: %spx %spx %spx %s; }", x, y, blur, rgba);
	set_text_css(env, ptr, "atl-text-shadow", css);
	g_free(css);
	g_free(rgba);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setHighlightColor(JNIEnv *env, jobject this, jlong ptr, jint argb)
{
	set_text_node_color(env, ptr, argb, "atl-selection-style", "selection", "background-color");
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setHintTextColor(JNIEnv *env, jobject this, jlong ptr, jint argb)
{
	set_text_node_color(env, ptr, argb, "atl-hint-style", "placeholder", "color");
}

static gboolean remove_span_attribute(PangoAttribute *attr, gpointer unused)
{
	return attr->klass->type == PANGO_ATTR_FOREGROUND || attr->klass->type == PANGO_ATTR_FOREGROUND_ALPHA || attr->klass->type == PANGO_ATTR_ABSOLUTE_SIZE;
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTextAttributes(JNIEnv *env, jobject this, jintArray encoded)
{
	GtkWidget *widget = _PTR(_GET_LONG_FIELD(this, "widget"));
	GtkLabel *label = GTK_IS_TEXT(widget) ? NULL : box_get_label(env, widget);
	PangoAttrList *old = label ? gtk_label_get_attributes(label) : gtk_text_get_attributes(GTK_TEXT(widget));
	PangoAttrList *attrs = old ? pango_attr_list_copy(old) : pango_attr_list_new();
	PangoAttrList *removed = pango_attr_list_filter(attrs, remove_span_attribute, NULL);
	if (removed) pango_attr_list_unref(removed);
	atl_text_attributes_apply(env, attrs, encoded);
	if (label) gtk_label_set_attributes(label, attrs);
	else gtk_text_set_attributes(GTK_TEXT(widget), attrs);
	pango_attr_list_unref(attrs);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setFontFeatureSettings(JNIEnv *env, jobject this, jlong ptr, jstring settings)
{
	GtkWidget *widget = _PTR(ptr);
	GtkLabel *label = GTK_IS_BOX(widget) ? box_get_label(env, widget) : NULL;
	PangoAttrList *old = label ? gtk_label_get_attributes(label) :
	                     GTK_IS_TEXT(widget) ? gtk_text_get_attributes(GTK_TEXT(widget)) : NULL;
	if (!label && !GTK_IS_TEXT(widget)) {
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/UnsupportedOperationException"), "Font features require a text widget");
		return;
	}
	const char *features = settings ? (*env)->GetStringUTFChars(env, settings, NULL) : NULL;
	if (settings && !features)
		return;
	PangoAttrList *attrs = old ? pango_attr_list_copy(old) : pango_attr_list_new();
	pango_attr_list_change(attrs, pango_attr_font_features_new(features ? features : ""));
	if (label)
		gtk_label_set_attributes(label, attrs);
	else
		gtk_text_set_attributes(GTK_TEXT(widget), attrs);
	pango_attr_list_unref(attrs);
	if (features)
		(*env)->ReleaseStringUTFChars(env, settings, features);
}

JNIEXPORT jlong JNICALL Java_android_widget_TextView_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	const char *text = attribute_set_get_string(env, attrs, "text", NULL);

	//	_SET_OBJ_FIELD(this, "text", "Ljava/lang/String;", _JSTRING(text)); //TODO: sadly this might be needed, but it's not atm

	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 0);
	GtkWidget *label = gtk_label_new(text);
	gtk_label_set_wrap(GTK_LABEL(label), TRUE);
	gtk_label_set_xalign(GTK_LABEL(label), 0.f);
	gtk_label_set_yalign(GTK_LABEL(label), 0.f);
	gtk_widget_set_hexpand(label, TRUE);
	gtk_box_append(GTK_BOX(box), label);
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), box);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);

	PangoAttrList *pango_attrs = pango_attr_list_new();
	pango_attr_list_insert(pango_attrs, pango_attr_font_features_new("tnum"));
	gtk_label_set_attributes(GTK_LABEL(label), pango_attrs);
	pango_attr_list_unref(pango_attrs);

	return _INTPTR(box);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setText(JNIEnv *env, jobject this, jobject charseq)
{
	char *text = charseq ? atl_text_to_utf8(env, charseq) : NULL;
	if (charseq && !text) return;
	GtkWidget *widget = _PTR(_GET_LONG_FIELD(this, "widget"));
	if (GTK_IS_TEXT(widget))
		gtk_editable_set_text(GTK_EDITABLE(widget), text ?: "");
	else
		atl_safe_gtk_label_set_text(box_get_label(env, widget), text ?: "");
	g_free(text);
}

/* we kinda need per-widget css */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTextColor(JNIEnv *env, jobject this, jint color)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(_GET_LONG_FIELD(this, "widget")));

	GtkStyleContext *style_context = gtk_widget_get_style_context(widget);

	GtkCssProvider *old_provider = g_object_get_data(G_OBJECT(widget), "color_style_provider");
	if (old_provider)
		gtk_style_context_remove_provider(style_context, GTK_STYLE_PROVIDER(old_provider));

	GtkCssProvider *css_provider = gtk_css_provider_new();

	char *css_string = g_markup_printf_escaped("* { color: #%06x%02x; }", color & 0xFFFFFF, (color >> 24) & 0xFF);
	gtk_css_provider_load_from_string(css_provider, css_string);
	g_free(css_string);

	gtk_style_context_add_provider(style_context, GTK_STYLE_PROVIDER(css_provider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data(G_OBJECT(widget), "color_style_provider", css_provider);
}
#pragma GCC diagnostic pop

JNIEXPORT void JNICALL Java_android_widget_TextView_setTextSize(JNIEnv *env, jobject this, jfloat size)
{
	GtkLabel *label = box_get_label(env, _PTR(_GET_LONG_FIELD(this, "widget")));
	PangoAttrList *attrs;

	PangoAttrList *old_attrs = gtk_label_get_attributes(label);
	if (old_attrs)
		attrs = pango_attr_list_copy(old_attrs);
	else
		attrs = pango_attr_list_new();

	PangoAttribute *size_attr = pango_attr_size_new(size * PANGO_SCALE);
	pango_attr_list_change(attrs, size_attr);
	gtk_label_set_attributes(label, attrs);

	pango_attr_list_unref(attrs);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1set_1markup(JNIEnv *env, jobject this, jint value)
{
	if (GTK_IS_TEXT(_PTR(_GET_LONG_FIELD(this, "widget")))) return;
	GtkLabel *label = box_get_label(env, _PTR(_GET_LONG_FIELD(this, "widget")));

	gtk_label_set_use_markup(label, value);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundDrawables(JNIEnv *env, jobject this, jlong widget_ptr, jlong left, jlong top, jlong right, jlong bottom)
{
	GtkWidget *box = GTK_WIDGET(_PTR(widget_ptr));
	gtk_orientable_set_orientation(GTK_ORIENTABLE(box), (left || right) ? GTK_ORIENTATION_HORIZONTAL : GTK_ORIENTATION_VERTICAL);

	GdkPaintable *paintable = _PTR(left ?: top); // paintable before text
	GtkWidget *picture = gtk_widget_get_first_child(box);
	if (GTK_IS_PICTURE(picture)) {
		gtk_picture_set_paintable(GTK_PICTURE(picture), paintable);
	} else if (paintable) {
		picture = gtk_picture_new_for_paintable(paintable);
		gtk_widget_insert_after(picture, box, NULL);
	}

	paintable = _PTR(right ?: bottom); // paintable after text
	picture = gtk_widget_get_last_child(box);
	if (GTK_IS_PICTURE(picture)) {
		gtk_picture_set_paintable(GTK_PICTURE(picture), paintable);
	} else if (paintable) {
		picture = gtk_picture_new_for_paintable(paintable);
		gtk_widget_insert_before(picture, box, NULL);
	}
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setPasswordVisibility(JNIEnv *env, jobject this, jlong ptr, jboolean visible)
{
	GtkWidget *widget = _PTR(ptr);
	if (GTK_IS_TEXT(widget)) {
		gtk_text_set_visibility(GTK_TEXT(widget), visible);
		gtk_text_set_input_purpose(GTK_TEXT(widget), visible ? GTK_INPUT_PURPOSE_FREE_FORM : GTK_INPUT_PURPOSE_PASSWORD);
	}
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTypeface(JNIEnv *env, jobject this, jlong ptr, jlong typeface)
{
	GtkWidget *widget = _PTR(ptr);
	GtkLabel *label = GTK_IS_BOX(widget) ? box_get_label(env, widget) : NULL;
	if (!label && !GTK_IS_TEXT(widget)) return;
	struct AndroidTypeface *font = _PTR(typeface);
	GtkWidget *text = label ? GTK_WIDGET(label) : widget;
	gtk_widget_set_font_map(text, font->map);
	PangoAttrList *old = label ? gtk_label_get_attributes(label) : gtk_text_get_attributes(GTK_TEXT(widget));
	PangoAttrList *attrs = old ? pango_attr_list_copy(old) : pango_attr_list_new();
	pango_attr_list_change(attrs, pango_attr_font_desc_new(font->description));
	if (label) gtk_label_set_attributes(label, attrs);
	else gtk_text_set_attributes(GTK_TEXT(widget), attrs);
	pango_attr_list_unref(attrs);
}
