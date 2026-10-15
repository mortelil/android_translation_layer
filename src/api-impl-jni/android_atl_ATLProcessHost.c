/* SPDX-License-Identifier: GPL-3.0-only
 * JNI entry points of the parent-side process host. The hold itself belongs to
 * the launcher executable (the GtkApplication lives in main.c); this file is the
 * JNI library member that ART can resolve for a hax class. The executable is
 * linked with -rdynamic, so these two references bind to main.c at load time.
 *
 * nativeSpawnServiceChild() is this host's spawn: it is the ONLY caller that
 * adds the explicit service-host marker ATL_SERVICE_HOST to the CHILD's
 * environment (per-call, via the shared spawn body in android_os_ATLBinderConnection.c
 * declared in atl_binder_spawn.h). The marker selects the windowless
 * service-child boot in src/main-executable/main.c:main(), which takes that
 * branch only when ATL_SERVICE_FD *and* ATL_SERVICE_HOST are present; a bare
 * transport spawn (Java_android_os_ATLBinderConnection_spawn) never sets it, so
 * this process' own environment is never mutated and no other spawn of this
 * process - the transport probe's launcher child, for instance - inherits it.
 */
#include "generated_headers/android_atl_ATLProcessHost.h"
#include "atl_binder_spawn.h"
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

/* The service spawn: same body as the transport spawn, with the per-call
 * ATL_SERVICE_HOST marker set for this child only. */
JNIEXPORT jintArray JNICALL Java_android_atl_ATLProcessHost_nativeSpawnServiceChild(JNIEnv *env, jclass cls, jstring apk, jstring process, jint sdk, jstring parentPackage, jstring parentProcess)
{
	(void)cls;
	return atl_binder_spawn(env, apk, process, sdk, parentPackage, parentProcess, TRUE);
}