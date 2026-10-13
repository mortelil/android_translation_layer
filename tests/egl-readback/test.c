/* SPDX-License-Identifier: GPL-3.0-only */
#include "egl_readback.h"
#include <assert.h>
#include <stdio.h>
int main(void) {
    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display,NULL,NULL));assert(eglBindAPI(EGL_OPENGL_ES_API));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config;EGLint count;assert(eglChooseConfig(display,attrs,&config,1,&count)&&count);
    EGLint context_attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    EGLContext context=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attrs);assert(context);
    EGLint pbuffer_attrs[]={EGL_WIDTH,3,EGL_HEIGHT,2,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(display,config,pbuffer_attrs);assert(surface);
    assert(eglMakeCurrent(display,surface,surface,context));
    glClearColor(1,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
    GLuint pack;glGenBuffers(1,&pack);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glBufferData(GL_PIXEL_PACK_BUFFER,4096,NULL,GL_STATIC_READ);
    glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,17);glPixelStorei(GL_PACK_SKIP_ROWS,2);glPixelStorei(GL_PACK_SKIP_PIXELS,1);
    glReadBuffer(GL_NONE);
    assert(glGetError()==GL_NO_ERROR);
    glEnable(0xffffffff); /* Deliberate application error must survive ATL work. */
    GBytes *bytes=atl_gl_read_rgba(0,3,2);assert(bytes);
    gsize size;const unsigned char *pixels=g_bytes_get_data(bytes,&size);assert(size==24);
    for(unsigned i=0;i<6;i++)assert(pixels[i*4]==255&&pixels[i*4+1]==0&&pixels[i*4+2]==0&&pixels[i*4+3]==255);
    g_bytes_unref(bytes);
    GLint v;glGetIntegerv(GL_PACK_ALIGNMENT,&v);assert(v==8);glGetIntegerv(GL_PACK_ROW_LENGTH,&v);assert(v==17);glGetIntegerv(GL_PACK_SKIP_ROWS,&v);assert(v==2);glGetIntegerv(GL_PACK_SKIP_PIXELS,&v);assert(v==1);glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING,&v);assert((GLuint)v==pack);
    glGetIntegerv(GL_READ_BUFFER,&v);assert(v==GL_NONE);
    EGLContext other=eglCreateContext(display,config,EGL_NO_CONTEXT,context_attrs);assert(other);
    assert(eglMakeCurrent(display,surface,surface,other));assert(bionic_glGetError()==GL_NO_ERROR);
    assert(eglMakeCurrent(display,surface,surface,context));assert(eglDestroyContext(display,other));
    assert(bionic_glGetError()==GL_INVALID_ENUM);assert(bionic_glGetError()==GL_NO_ERROR);
    assert(!atl_gl_read_rgba(0,0,2));assert(!atl_gl_read_rgba(0,3,-1));
    GLuint fbo;glGenFramebuffers(1,&fbo);assert(!atl_gl_read_rgba(fbo,3,2));glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING,&v);assert(v==0);
    assert(bionic_glGetError()==GL_NO_ERROR);
    glDeleteFramebuffers(1,&fbo);glDeleteBuffers(1,&pack);atl_gl_forget_errors(context);
    assert(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT));assert(eglDestroySurface(display,surface));assert(eglDestroyContext(display,context));eglTerminate(display);
    puts("PASS: RGBA pixels, pack/PBO restoration, preserved app error, invalid geometry and incomplete FBO");
}
