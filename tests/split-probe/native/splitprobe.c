/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Minimal x86_64 / arm64 native library shipped only inside the ABI-matching
 * config split. Built with -nostdlib (no DT_NEEDED), so it loads identically on
 * Bionic (emulator-5554) and under ATL's native extraction.
 *
 * The exported symbol is the exact JNI entry point for
 * org.atl.tests.split.ProbeLoader.splitprobeValue() (a static native method),
 * so no jni.h/NDK sysroot is required to cross-compile it.
 */
typedef int jint;
typedef void *JNIEnv;
typedef void *jclass;

jint Java_org_atl_tests_split_ProbeLoader_splitprobeValue(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return 0x5A17;
}
