/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * jni_exceptions.c - see jni_exceptions.h for the contract and provenance.
 */

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

#include "jni_exceptions.h"

/*
 * Print one Throwable's own description and its cause chain to stderr.
 *
 * Only ever called once nothing is pending: the caller has already retained and
 * cleared the original Throwable, so ordinary JNI calls are legal here. It
 * deliberately does not use printStackTrace()/ExceptionDescribe(), because the
 * formatter itself may throw (JNI-EXC-001 scenario B: a Throwable whose
 * printStackTrace() throws); this fallback must still record the original
 * type, message and cause.
 *
 * Exception state is re-checked after every call and cleared locally, so a
 * hostile Throwable cannot leave a pending exception behind and the next JNI
 * call is always made with a clean thread state.
 */
static void report_throwable(JNIEnv *env, jthrowable throwable, const char *prefix)
{
	if (!throwable)
		return;

	jclass throwable_class = (*env)->FindClass(env, "java/lang/Throwable");
	if (!throwable_class) {
		(*env)->ExceptionClear(env);
		return;
	}

	jmethodID to_string = (*env)->GetMethodID(env, throwable_class, "toString", "()Ljava/lang/String;");
	if (!to_string) {
		(*env)->ExceptionClear(env);
		(*env)->DeleteLocalRef(env, throwable_class);
		return;
	}
	jmethodID get_cause = (*env)->GetMethodID(env, throwable_class, "getCause", "()Ljava/lang/Throwable;");
	(*env)->DeleteLocalRef(env, throwable_class);
	if (!get_cause) {
		(*env)->ExceptionClear(env);
		return;
	}

	jthrowable current = throwable; /* caller-owned reference, not deleted here */
	/* A cause chain is finite; bound it so a self-referential Throwable cannot loop. */
	for (int depth = 0; current && depth < 16; depth++) {
		jstring description = (jstring)(*env)->CallObjectMethod(env, current, to_string);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
			fprintf(stderr, "%s<Throwable accessor threw; diagnostic truncated>\n", prefix);
			break;
		}
		if (description) {
			const char *text = (*env)->GetStringUTFChars(env, description, NULL);
			if (text) {
				fprintf(stderr, "%s%s\n", prefix, text);
				(*env)->ReleaseStringUTFChars(env, description, text);
			}
			(*env)->DeleteLocalRef(env, description);
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionClear(env);
				break;
			}
		}

		prefix = "  caused by: ";

		jthrowable cause = (jthrowable)(*env)->CallObjectMethod(env, current, get_cause);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
			fprintf(stderr, "%s<Throwable accessor threw; diagnostic truncated>\n", prefix);
			break;
		}
		if (current != throwable)
			(*env)->DeleteLocalRef(env, current);
		current = cause;
	}
	if (current != throwable)
		(*env)->DeleteLocalRef(env, current);
}

/*
 * Hand a retained Throwable to the Java uncaught-exception path, mirroring
 * art/runtime/thread.cc Thread::HandleUncaughtExceptions(): the current thread's
 * dispatchUncaughtException(Throwable) runs the configured UncaughtExceptionHandler
 * (the ThreadGroup by default, which prints the Throwable and its cause chain).
 *
 * Returns true when the handler ran without raising. Returns false with the
 * secondary exception left pending when a lookup failed or the handler itself
 * threw, so the caller can still report the original.
 */
static bool dispatch_uncaught_exception(JNIEnv *env, jthrowable throwable)
{
	jclass thread_class = (*env)->FindClass(env, "java/lang/Thread");
	if (!thread_class)
		return false;

	jmethodID current_thread = (*env)->GetStaticMethodID(env, thread_class, "currentThread", "()Ljava/lang/Thread;");
	if (!current_thread) {
		(*env)->DeleteLocalRef(env, thread_class);
		return false;
	}
	jmethodID dispatch = (*env)->GetMethodID(env, thread_class, "dispatchUncaughtException", "(Ljava/lang/Throwable;)V");
	if (!dispatch) {
		(*env)->DeleteLocalRef(env, thread_class);
		return false;
	}

	jobject thread = (*env)->CallStaticObjectMethod(env, thread_class, current_thread);
	(*env)->DeleteLocalRef(env, thread_class);
	if (!thread)
		return false;

	(*env)->CallVoidMethod(env, thread, dispatch, throwable);
	(*env)->DeleteLocalRef(env, thread);
	return !(*env)->ExceptionCheck(env);
}

void atl_jni_handle_uncaught_exception(JNIEnv *env, const char *where)
{
	/* ExceptionOccurred returns a rooted local reference which survives Clear.
	 * No global allocation is needed: this native frame never returns while
	 * reporting the original. In particular, OOM cannot replace it during retain.
	 */
	jthrowable original = (*env)->ExceptionOccurred(env);
	if (!original)
		return;
	(*env)->ExceptionClear(env);

	if (dispatch_uncaught_exception(env, original)) {
		fprintf(stderr, "fatal: uncaught Java exception from %s; terminating\n", where);
	} else {
		/*
		 * The uncaught-exception path itself failed (a lookup failed, or the
		 * Throwable's own formatter threw). The original must remain the primary
		 * failure: report it, and the secondary failure, before terminating.
		 */
		jthrowable secondary = (*env)->ExceptionOccurred(env);
		(*env)->ExceptionClear(env);
		fprintf(stderr, "fatal: unhandled Java exception from %s (uncaught-exception routing failed)\n", where);
		report_throwable(env, original, "  original: ");
		if (secondary) {
			report_throwable(env, secondary, "  while reporting: ");
			(*env)->DeleteLocalRef(env, secondary);
		}
	}

	(*env)->DeleteLocalRef(env, original);
	exit(1);
}
