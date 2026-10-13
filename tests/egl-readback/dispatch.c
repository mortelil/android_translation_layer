/* SPDX-License-Identifier: GPL-3.0-only */
#include <EGL/egl.h>
#include <assert.h>
#include <string.h>
#include <stdio.h>
extern __eglMustCastToProperFunctionPointerType bionic_eglGetProcAddress(const char *);
/* Test-only adapter: tests routing, not presentation. */
EGLBoolean bionic_eglSwapBuffers(EGLDisplay display, EGLSurface surface) {return EGL_FALSE;}
int main(void) {
    assert(bionic_eglGetProcAddress("eglSwapBuffers")==(__eglMustCastToProperFunctionPointerType)bionic_eglSwapBuffers);
    assert(bionic_eglGetProcAddress("eglQueryString")==eglGetProcAddress("eglQueryString"));
    assert(!bionic_eglGetProcAddress(NULL));
    char long_name[300];memset(long_name,'z',sizeof(long_name)-1);long_name[sizeof(long_name)-1]=0;
    assert(bionic_eglGetProcAddress(long_name)==eglGetProcAddress(long_name));
    puts("PASS: dynamic EGL lookup prefers ATL adapter and otherwise forwards to host without a shim");
}
