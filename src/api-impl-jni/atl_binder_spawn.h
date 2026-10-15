/* SPDX-License-Identifier: GPL-3.0-only
 * Internal (non-JNI) interface of the ATL-to-ATL child spawn. One C body is
 * shared by the two spawn entry points so the per-child environment can never
 * drift between them:
 *
 *   Java_android_os_ATLBinderConnection_spawn            service_child = FALSE
 *       the ordinary transport spawn (lane A's probe launch child): windowed
 *       launcher boot, no service-host marker.
 *   Java_android_atl_ATLProcessHost_nativeSpawnServiceChild  service_child = TRUE
 *       the service spawn of the loader-side process host: the ONLY caller that
 *       adds ATL_SERVICE_HOST to the CHILD's environment, which is what selects
 *       the windowless service-child boot in src/main-executable/main.c
 *       (ATL_SERVICE_FD && ATL_SERVICE_HOST). The marker is a property of the
 *       child environment, never of this process' own environment: this
 *       process legitimately hosts the process host, and its environment is
 *       inherited by every spawn (including lane A's transport child).
 */
#ifndef ATL_BINDER_SPAWN_H
#define ATL_BINDER_SPAWN_H

#include <glib.h>
#include <jni.h>

/* Spawns one child of this process and returns {channel fd, pid}, or throws
 * java.io.IOException and returns NULL. service_child adds ATL_SERVICE_HOST=1
 * to the per-call child environment only. Implemented in
 * src/api-impl-jni/android_os_ATLBinderConnection.c. */
jintArray atl_binder_spawn(JNIEnv *env, jstring apk, jstring process, jint sdk,
                           jstring parentPackage, jstring parentProcess, gboolean service_child);

#endif
