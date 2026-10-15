/* SPDX-License-Identifier: GPL-3.0-only
 * JNI entry points of the parent-side process host. The hold itself belongs to
 * the launcher executable (the GtkApplication lives in main.c); this file is the
 * JNI library member that ART can resolve for a hax class. The executable is
 * linked with -rdynamic, so these two references bind to main.c at load time.
 *
 * nativeMarkServiceHost() sets the explicit service-host marker of the
 * child-boot gate (architecture.md §8). It is called once by the loader-side
 * process host (ATLProcessHost's constructor) and only changes the loader's own
 * environment: ATLBinderConnection.spawn() hands the loader environment to the
 * child unchanged, so every child this host starts carries ATL_SERVICE_HOST,
 * while a bare transport spawn from a process that never ran this host does not.
 * src/main-executable/main.c:main() takes the windowless service-child boot only
 * when ATL_SERVICE_FD *and* ATL_SERVICE_HOST are present.
 */
#include "generated_headers/android_atl_ATLProcessHost.h"
#include <glib.h>

extern void atl_launcher_app_hold(void);
extern void atl_launcher_app_release(void);

JNIEXPORT void JNICALL Java_android_atl_ATLProcessHost_nativeHoldApp(JNIEnv *env, jclass cls)
{
	(void)env;
	(void)cls;
	atl_launcher_app_hold();
}

JNIEXPORT void JNICALL Java_android_atl_ATLProcessHost_nativeReleaseApp(JNIEnv *env, jclass cls)
{
	(void)env;
	(void)cls;
	atl_launcher_app_release();
}

/* g_setenv, not libc setenv: the marker must be visible to ATLBinderConnection.spawn's
 * g_get_environ(), which reads GLib's environment view (measured equal to libc's on
 * glib 2.90, this API keeps it that way by contract). */
JNIEXPORT void JNICALL Java_android_atl_ATLProcessHost_nativeMarkServiceHost(JNIEnv *env, jclass cls)
{
	(void)env;
	(void)cls;
	g_setenv("ATL_SERVICE_HOST", "1", TRUE);
}