// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <EGL/egl.h>
void atl_egl_retain_host_display(EGLDisplay display);
EGLBoolean bionic_eglInitialize(EGLDisplay display, EGLint *major, EGLint *minor);
EGLBoolean bionic_eglTerminate(EGLDisplay display);
