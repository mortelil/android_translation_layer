// SPDX-License-Identifier: GPL-3.0-only
#include <assert.h>
#include <stdio.h>
#include <gtk/gtk.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
GtkWindow *window;
EGLDisplay bionic_eglGetDisplay(EGLNativeDisplayType);
EGLBoolean bionic_eglChooseConfig(EGLDisplay,EGLint*,EGLConfig*,EGLint,EGLint*);
EGLSurface bionic_eglCreatePbufferSurface(EGLDisplay,EGLConfig,const EGLint*);
int main(int argc,char **argv) {
 if(argc>1) gtk_init();
 else assert(!gdk_display_get_default());
 assert(!window);
 EGLDisplay d=bionic_eglGetDisplay(EGL_DEFAULT_DISPLAY);
 assert(d!=EGL_NO_DISPLAY && eglInitialize(d,NULL,NULL));
 assert(eglBindAPI(EGL_OPENGL_ES_API));
 EGLConfig c; EGLint n;
 assert(bionic_eglChooseConfig(d,NULL,&c,1,&n));
 EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
 assert(bionic_eglChooseConfig(d,attrs,&c,1,&n)&&n);
 EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
 EGLContext ctx=eglCreateContext(d,c,EGL_NO_CONTEXT,ca);assert(ctx!=EGL_NO_CONTEXT);
 EGLint pa[]={EGL_WIDTH,4,EGL_HEIGHT,4,EGL_NONE};
 EGLSurface s=bionic_eglCreatePbufferSurface(d,c,pa);assert(s!=EGL_NO_SURFACE);
 assert(eglMakeCurrent(d,s,s,ctx));
 glClearColor(1,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
 unsigned char pixel[4];glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
 assert(glGetError()==GL_NO_ERROR&&pixel[0]==255&&pixel[1]==0&&pixel[2]==0);
 assert(eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));
 assert(eglDestroySurface(d,s)&&eglDestroyContext(d,ctx)&&eglTerminate(d));
 puts(argc>1?"EGL_PASS GDK without window":"EGL_PASS no GTK initialization");
}
