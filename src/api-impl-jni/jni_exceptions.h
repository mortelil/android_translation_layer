/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Uncaught exception routing for native-owned entrypoints.
 *
 * Provenance: ART Thread::HandleUncaughtExceptions and ExceptionDescribe,
 * art_standalone 327b9c2e. The latter restores the exception after printing;
 * ExceptionDescribe alone therefore does not permit subsequent ordinary JNI.
 *
 * Use only after a callback has unwound to a GTK/GLib entrypoint with no Java
 * caller, never inside a native method returning to Java. Internal helpers
 * return immediately with the exception pending so Java catch/recovery works.
 *
 * ExceptionOccurred supplies a rooted local reference; clearing the pending
 * exception does not invalidate it. Dispatch receives that same Throwable,
 * including any application handler. If dispatch returns or throws, the main
 * event-loop boundary cannot safely resume the failed callback: report and
 * exit(1). The pinned core's default handler instead prints and exits(10).
 *
 * The fallback bounds cause traversal to 16 nodes. It is diagnostic only:
 * no stack frames/suppressed exceptions, and overridden Throwable accessors
 * may throw (or block, like arbitrary application handlers). It does not
 * promise to reconstruct a Throwable whose own accessors are unusable.
 */
#ifndef ATL_JNI_EXCEPTIONS_H
#define ATL_JNI_EXCEPTIONS_H
#include <jni.h>

/* No-op without a pending exception; otherwise never returns. */
void atl_jni_handle_uncaught_exception(JNIEnv *env, const char *where);
#endif
