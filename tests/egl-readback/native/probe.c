/* SPDX-License-Identifier: GPL-3.0-only */
#include <jni.h>
#include <EGL/egl.h>
#include <dlfcn.h>
JNIEXPORT jboolean JNICALL Java_org_atl_eglcheck_Probe_check(JNIEnv *env, jclass cls) {
    void *host=dlopen("libEGL.so",RTLD_NOW);
    if (!host) return JNI_FALSE;
    const char *names[]={"eglGetDisplay","eglCreateWindowSurface","eglMakeCurrent","eglSwapBuffers","eglDestroySurface","glBindFramebuffer","glGetError"};
    const char *wrapped[]={"bionic_eglGetDisplay","bionic_eglCreateWindowSurface","bionic_eglMakeCurrent","bionic_eglSwapBuffers","bionic_eglDestroySurface","bionic_glBindFramebuffer","bionic_glGetError"};
    for (unsigned i=0;i<sizeof(names)/sizeof(names[0]);i++) {
        void *expected=dlsym(RTLD_DEFAULT,wrapped[i]);
        if (!expected || (void *)eglGetProcAddress(names[i])!=expected) return JNI_FALSE;
        if (i<5 && dlsym(host,names[i])!=expected) return JNI_FALSE;
    }
    /* Calling through the loaded EGL handle must terminate rather than recurse. */
    EGLint (*get_error)(void)=dlsym(host,"eglGetError");
    if (!get_error) return JNI_FALSE;
    get_error();dlclose(host);return JNI_TRUE;
}
