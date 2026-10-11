/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * jni_exceptions.h - JNI exception preservation at ATL's native-owned boundaries.
 *
 * ATL's native activity-lifecycle and looper code enters Java callbacks from
 * the GTK main loop, so a Throwable raised inside such a callback has no Java
 * frame to propagate to: it stays pending on the thread while native control
 * flow continues. A pending exception does not unwind native C, and every JNI
 * call outside the JNI-spec exception/cleanup set is invalid while one is
 * pending (CheckJNI aborts with
 * "JNI <fn> called with pending exception <throwable>").
 *
 * The previous idiom at these boundaries -
 *
 *     if ((*env)->ExceptionCheck(env))
 *         (*env)->ExceptionDescribe(env);
 *
 * - is not a terminal operation: the pinned ART re-installs the Throwable after
 * printing it (art/runtime/jni/jni_internal.cc ExceptionDescribe, line ~912
 * `soa.Self()->SetException(old_exception.Get())`). Code that followed it
 * therefore kept executing with a pending exception and, in activity_start(),
 * issued GetObjectClass while the Throwable was still pending.
 *
 * atl_jni_handle_uncaught_exception() is the single shared helper for these
 * boundaries. It is modelled on the Android runtime's own uncaught-exception
 * path, art/runtime/thread.cc Thread::HandleUncaughtExceptions(): retain the
 * Throwable, clear it, hand it to Thread.dispatchUncaughtException(Throwable)
 * (which reaches the application's UncaughtExceptionHandler and, by default,
 * prints the Throwable and its cause chain), then terminate the process through
 * the documented fatal path ATL already uses for an unrecoverable startup
 * failure (src/main-executable/main.c: "ExceptionDescribe(env); exit(1);").
 *
 * Provenance: pattern adapted from the pinned ART / libnativehelper sources
 * (art_standalone 327b9c2e; art/runtime/thread.cc Thread::HandleUncaughtExceptions,
 * libnativehelper/JNIHelp.cpp). ATL is GPL-3.0-only; see LICENSE.txt.
 */
#ifndef ATL_JNI_EXCEPTIONS_H
#define ATL_JNI_EXCEPTIONS_H

#include <jni.h>

/*
 * Handle the failure of a JNI call at a native-owned boundary (activity
 * lifecycle callbacks, GLib main-loop / looper dispatch).
 *
 * Call it immediately after any JNI call that can raise - including class,
 * method and field lookups - in place of ExceptionCheck()+ExceptionDescribe():
 *
 *     (*env)->CallVoidMethod(env, activity, handle_cache.activity.onCreate, NULL);
 *     if ((*env)->ExceptionCheck(env))
 *         atl_jni_handle_uncaught_exception(env, "Activity.onCreate");
 *
 * If no exception is pending the call is a no-op: a legitimate null object
 * result without an exception is a separate case and is not a failure.
 *
 * If an exception is pending the call does not return. The original Throwable
 * is retained (never replaced, never silently cleared to continue), routed to
 * the Java uncaught-exception handler, and the process exits(1) - the same
 * documented fatal path ATL uses when startup cannot continue. The pending
 * exception is never stranded for unrelated JNI work.
 */
void atl_jni_handle_uncaught_exception(JNIEnv *env, const char *where);

#endif /* ATL_JNI_EXCEPTIONS_H */
