// SPDX-License-Identifier: GPL-3.0-only
#include "egl_lifecycle.h"
#include <glib.h>

struct display_lifetime {
	guint64 clients;
	gboolean host_owned;
};
static GMutex display_mutex;
static GHashTable *displays;

static struct display_lifetime *get_lifetime(EGLDisplay display)
{
	if (!displays)
		displays = g_hash_table_new_full(g_direct_hash, g_direct_equal, NULL, g_free);
	struct display_lifetime *lifetime = g_hash_table_lookup(displays, display);
	if (!lifetime) {
		lifetime = g_new0(struct display_lifetime, 1);
		g_hash_table_insert(displays, display, lifetime);
	}
	return lifetime;
}

void atl_egl_retain_host_display(EGLDisplay display)
{
	if (display == EGL_NO_DISPLAY)
		return;
	g_mutex_lock(&display_mutex);
	get_lifetime(display)->host_owned = TRUE;
	g_mutex_unlock(&display_mutex);
}

EGLBoolean bionic_eglInitialize(EGLDisplay display, EGLint *major, EGLint *minor)
{
	g_mutex_lock(&display_mutex);
	EGLBoolean result = eglInitialize(display, major, minor);
	if (result)
		get_lifetime(display)->clients++;
	g_mutex_unlock(&display_mutex);
	return result;
}

EGLBoolean bionic_eglTerminate(EGLDisplay display)
{
	g_mutex_lock(&display_mutex);
	struct display_lifetime *lifetime = displays ? g_hash_table_lookup(displays, display) : NULL;
	EGLBoolean result = EGL_TRUE;
	if (lifetime && lifetime->clients)
		lifetime->clients--;
	/* Android engines share a display and balance initialization individually.
	 * GTK also owns the platform display returned by ATL's eglGetDisplay.
	 * Its contexts must survive termination by the last Android client; GTK
	 * remains responsible for the host display's eventual teardown. */
	if (!lifetime || (!lifetime->clients && !lifetime->host_owned)) {
		result = eglTerminate(display);
		if (result && lifetime)
			g_hash_table_remove(displays, display);
	}
	g_mutex_unlock(&display_mutex);
	return result;
}
