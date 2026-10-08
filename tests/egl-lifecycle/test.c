// SPDX-License-Identifier: GPL-3.0-only
#include "../../src/libandroid/egl_lifecycle.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <assert.h>
#include <stdio.h>

static EGLContext context(EGLDisplay display)
{
	EGLConfig config;
	EGLint count;
	EGLint attributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
	assert(eglChooseConfig(display, attributes, &config, 1, &count) && count);
	EGLint version[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
	EGLContext result = eglCreateContext(display, config, EGL_NO_CONTEXT, version);
	assert(result != EGL_NO_CONTEXT);
	return result;
}
int main(void)
{
	EGLDisplay display = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
	assert(display != EGL_NO_DISPLAY);
	assert(eglBindAPI(EGL_OPENGL_ES_API));
	EGLint value, major, minor;
	assert(bionic_eglInitialize(display, &major, &minor) && major >= 1);
	assert(bionic_eglInitialize(display, NULL, NULL));
	EGLContext first = context(display), second = context(display);
	assert(eglDestroyContext(display, second));
	assert(bionic_eglTerminate(display));
	assert(eglQueryContext(display, first, EGL_CONTEXT_CLIENT_VERSION, &value));
	assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, first));
	assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT));
	assert(eglDestroyContext(display, first));
	assert(bionic_eglTerminate(display));
	assert(!eglQueryContext(display, first, EGL_CONTEXT_CLIENT_VERSION, &value));
	(void)eglGetError();
	// A host context must also survive the last Android client's teardown.
	assert(eglInitialize(display, NULL, NULL));
	atl_egl_retain_host_display(display);
	EGLContext host = context(display);
	for (int i = 0; i < 3; i++) {
		assert(bionic_eglInitialize(display, NULL, NULL));
		EGLContext app = context(display);
		assert(eglDestroyContext(display, app));
		assert(bionic_eglTerminate(display));
		assert(eglQueryContext(display, host, EGL_CONTEXT_CLIENT_VERSION, &value));
		assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, host));
		assert(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT));
	}
	assert(!bionic_eglInitialize(EGL_NO_DISPLAY, NULL, NULL));
	assert(eglGetError() == EGL_BAD_DISPLAY);
	assert(!bionic_eglTerminate(EGL_NO_DISPLAY));
	assert(eglGetError() == EGL_BAD_DISPLAY);
	assert(eglDestroyContext(display, host));
	assert(eglTerminate(display));
	puts("PASS: multiple EGL clients, repeated background teardown, host context survival and invalid displays");
}
