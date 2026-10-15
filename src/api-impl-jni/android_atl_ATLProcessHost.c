/* SPDX-License-Identifier: GPL-3.0-only
 * JNI entry points of the parent-side process host. The hold itself belongs to
 * the launcher executable (the GtkApplication lives in main.c); this file is the
 * JNI library member that ART can resolve for a hax class. The executable is
 * linked with -rdynamic, so these two references bind to main.c at load time.
 */
#include "generated_headers/android_atl_ATLProcessHost.h"

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
