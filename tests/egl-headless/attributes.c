// SPDX-License-Identifier: GPL-3.0-only
#include "../../src/libandroid/egl_attributes.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
 EGLint w,h;
 assert(atl_pbuffer_window_attributes(NULL,&w,&h)==NULL&&w==0&&h==0);
 const EGLint empty[]={EGL_NONE};
 EGLint *out=atl_pbuffer_window_attributes(empty,&w,&h);assert(out[0]==EGL_NONE);g_free(out);
 const EGLint values[]={EGL_WIDTH,9,EGL_LARGEST_PBUFFER,EGL_TRUE,EGL_HEIGHT,7,EGL_TEXTURE_FORMAT,EGL_TEXTURE_RGBA,EGL_NONE};
 out=atl_pbuffer_window_attributes(values,&w,&h);
 assert(w==9&&h==7&&out[0]==EGL_LARGEST_PBUFFER&&out[1]==EGL_TRUE&&out[2]==EGL_TEXTURE_FORMAT&&out[3]==EGL_TEXTURE_RGBA&&out[4]==EGL_NONE);g_free(out);
 const EGLint only_values[]={EGL_LARGEST_PBUFFER,EGL_TRUE,EGL_TEXTURE_FORMAT,EGL_TEXTURE_RGBA,EGL_NONE};
 out=atl_pbuffer_window_attributes(only_values,&w,&h);
 assert(w==0&&h==0&&out[4]==EGL_NONE);g_free(out);
 puts("EGL_ATTRIBUTES_PASS null, empty, dimensions and preserved pairs");
}
