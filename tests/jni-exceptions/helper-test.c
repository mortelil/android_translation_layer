/* SPDX-License-Identifier: GPL-3.0-only */
/* Fault-injected JNI table: forbid ordinary calls while pending and verify
 * exact references even when diagnostic allocation/lookup/accessors fail. */
#include <assert.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <jni.h>
static jmp_buf done;
static jobject pending;
static int mode, strings, causes, dispatches;
#define ORIGINAL ((jobject)(uintptr_t)1)
#define SECONDARY ((jobject)(uintptr_t)2)
#define OBJECT ((jobject)(uintptr_t)3)
#define METHOD(n) ((jmethodID)(uintptr_t)(n))
static void test_exit(int status) { assert(status == 1); longjmp(done, 1); }
#define exit test_exit
#include "../../src/api-impl-jni/jni_exceptions.c"
#undef exit
static void clean(void) { assert(!pending); }
static jthrowable occurred(JNIEnv *e) { return pending; }
static void clear(JNIEnv *e) { pending = NULL; }
static jboolean check(JNIEnv *e) { return pending != NULL; }
static void delete_ref(JNIEnv *e, jobject o) { /* allowed while pending */ }
static jclass find(JNIEnv *e, const char *name) { clean(); return OBJECT; }
static jmethodID static_method(JNIEnv *e, jclass c, const char *n, const char *s) {
    clean();
    if (mode == 1) { pending = SECONDARY; return NULL; }
    return METHOD(1);
}
static jmethodID method(JNIEnv *e, jclass c, const char *n, const char *s) {
    clean();
    if (!strcmp(n, "dispatchUncaughtException")) return METHOD(2);
    if (!strcmp(n, "toString")) {
        if (mode == 2) { pending = SECONDARY; return NULL; }
        return METHOD(3);
    }
    return METHOD(4);
}
static jobject static_call(JNIEnv *e, jclass c, jmethodID m, ...) { clean(); return OBJECT; }
static void void_call(JNIEnv *e, jobject o, jmethodID m, ...) {
    clean(); va_list args; va_start(args, m);
    assert(va_arg(args, jobject) == ORIGINAL); va_end(args);
    dispatches++;
    pending = SECONDARY; /* handler itself throws */
}
static jobject object_call(JNIEnv *e, jobject o, jmethodID m, ...) {
    clean();
    if (m == METHOD(3)) {
        if (mode == 3) { pending = SECONDARY; return NULL; }
        return OBJECT;
    }
    causes++;
    if (mode == 4) return o; /* cyclic cause, bounded to 16 per Throwable */
    return NULL;
}
static const char *utf(JNIEnv *e, jstring s, jboolean *copy) {
    clean(); strings++;
    if (mode == 5) { pending = SECONDARY; return NULL; } /* OOM */
    return "diagnostic";
}
static void release(JNIEnv *e, jstring s, const char *v) { clean(); }
int main(void) {
    const struct JNINativeInterface_ table = {
        .ExceptionOccurred=occurred, .ExceptionClear=clear, .ExceptionCheck=check,
        .DeleteLocalRef=delete_ref, .FindClass=find, .GetStaticMethodID=static_method,
        .GetMethodID=method, .CallStaticObjectMethod=static_call,
        .CallVoidMethod=void_call, .CallObjectMethod=object_call,
        .GetStringUTFChars=utf, .ReleaseStringUTFChars=release,
    };
    JNIEnv env = &table;
    atl_jni_handle_uncaught_exception(&env, "no exception");
    for (mode=0; mode<6; mode++) {
        pending=ORIGINAL; causes=strings=dispatches=0;
        if (!setjmp(done)) {
            atl_jni_handle_uncaught_exception(&env, "fault injection");
            assert(!"must terminate");
        }
        assert(!pending);
        assert(dispatches == (mode == 1 ? 0 : 1));
        if (mode == 4) assert(causes == 32);
        if (mode == 5) assert(causes == 0 && strings == 2);
    }
    puts("JNI helper fault-injection: 7 checks passed");
}
