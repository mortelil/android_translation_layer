/* SPDX-License-Identifier: GPL-3.0-only */
#include "egl_snapshot.h"
#include "egl_readback.h"
#include <EGL/eglext.h>
#include <assert.h>
#include <dirent.h>
#include <stdio.h>

static EGLDisplay display;
static EGLConfig config;
/* Link-time fault injection exercises real cleanup without production test hooks. */
static int import_mode;
static gint imported, released;
struct release_probe {GDestroyNotify destroy; gpointer data;};
static void release_probe(gpointer data) {
    struct release_probe *p=data;p->destroy(p->data);g_free(p);g_atomic_int_inc(&released);
}
GdkTexture *__real_gdk_dmabuf_texture_builder_build(GdkDmabufTextureBuilder *, GDestroyNotify, gpointer, GError **);
GdkTexture *__wrap_gdk_dmabuf_texture_builder_build(GdkDmabufTextureBuilder *b, GDestroyNotify destroy, gpointer data, GError **error) {
    if (import_mode==1) return NULL;
    if (import_mode==2) g_usleep(30000);
    struct release_probe *p=g_new(struct release_probe,1);p->destroy=destroy;p->data=data;
    GdkTexture *t=__real_gdk_dmabuf_texture_builder_build(b,release_probe,p,error);
    if(t)g_atomic_int_inc(&imported);else g_free(p);
    return t;
}
static int fd_count(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d); int n=0;
    while(readdir(d)) n++;
    closedir(d); return n;
}
static void pixels(GdkTexture *t, int w, int h) {
    assert(gdk_texture_get_width(t)==w && gdk_texture_get_height(t)==h);
    unsigned char *p=g_malloc(w*h*4); gdk_texture_download(t,p,w*4);
    for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
        int i=y*w+x;bool corner=x<w/2&&y<h/2;
        assert(p[i*4]==0 && p[i*4+1]==(corner?32:0) && p[i*4+2]==(corner?64:255) && p[i*4+3]==(corner?128:255));
    }
    g_free(p);
}
static GdkTexture *produce(int version, int w, int h, bool timeout) {
    EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,version,EGL_NONE};
    EGLContext ctx=eglCreateContext(display,config,EGL_NO_CONTEXT,ca); assert(ctx);
    assert(eglMakeCurrent(display,0,0,ctx));
    GLuint tex,fbo,unpack=0;glGenTextures(1,&tex);glBindTexture(GL_TEXTURE_2D,tex);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,tex,0);
    assert(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE);
    glClearColor(1,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);glScissor(0,0,w/2,h/2);
    glClearColor(64.f/255,32.f/255,0,128.f/255);glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
    if(version>=3) {
        glGenBuffers(1,&unpack);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);
        glBufferData(GL_PIXEL_UNPACK_BUFFER,16,NULL,GL_STATIC_DRAW);glReadBuffer(GL_NONE);
    }
    glEnable(0xffffffff);
    GdkTexture *t=atl_egl_snapshot(fbo,w,h);
    assert(eglGetCurrentContext()==ctx);assert(eglQueryAPI()==EGL_OPENGL_ES_API);
    assert(bionic_glGetError()==GL_INVALID_ENUM);assert(bionic_glGetError()==GL_NO_ERROR);
    GLint v;glGetIntegerv(GL_TEXTURE_BINDING_2D,&v);assert((GLuint)v==tex);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING,&v);assert((GLuint)v==fbo);
    if(version>=3) {
        glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING,&v);assert((GLuint)v==unpack);
        glGetIntegerv(GL_READ_BUFFER,&v);assert(v==GL_NONE);
    }
    if(timeout) {
        assert(!t);GBytes *b=atl_gl_read_rgba(fbo,w,h);assert(b);
        const unsigned char *p=g_bytes_get_data(b,NULL);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int i=y*w+x;bool corner=x<w/2&&y<h/2;
            assert(p[i*4]==(corner?64:255)&&p[i*4+1]==(corner?32:0)&&p[i*4+2]==0&&p[i*4+3]==(corner?128:255));
        }
        g_bytes_unref(b);
    } else assert(t);
    /* Overwrite, resize and destroy the source before the consumer reads it. */
    glClearColor(0,1,0,1);glClear(GL_COLOR_BUFFER_BIT);
    if(version>=3)glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,2,2,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
    glDeleteTextures(1,&tex);glDeleteFramebuffers(1,&fbo);if(unpack)glDeleteBuffers(1,&unpack);
    atl_gl_forget_errors(ctx);eglMakeCurrent(display,0,0,EGL_NO_CONTEXT);eglDestroyContext(display,ctx);
    return t;
}
static gint worker_done;
static gpointer worker(gpointer unused) {
    (void)unused;
    GdkTexture *t=produce(3,31,17,import_mode!=0);
    g_atomic_int_set(&worker_done,1);g_main_context_wakeup(NULL);return t;
}
int main(void) {
    gtk_init();
    PFNEGLGETPLATFORMDISPLAYEXTPROC get=(void*)eglGetProcAddress("eglGetPlatformDisplayEXT");
    assert(get);display=get(EGL_PLATFORM_SURFACELESS_MESA,0,0);assert(eglInitialize(display,0,0));
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLint count;assert(eglChooseConfig(display,attrs,&config,1,&count)&&count);
    assert(g_main_context_acquire(NULL));
    for(int version=2;version<=3;version++)for(int i=0;i<10;i++) {
        GdkTexture *t=produce(version,64+i,32+i,false);pixels(t,64+i,32+i);g_object_unref(t);
    }
    int before=fd_count();
    for(int i=0;i<50;i++) {GdkTexture*t=produce(3,20,10,false);pixels(t,20,10);g_object_unref(t);}
    assert(fd_count()==before);
    g_main_context_release(NULL);
    /* No dispatched GTK context: bounded failure followed by a correct CPU frame. */
    gint64 start=g_get_monotonic_time();
    for(int i=0;i<10;i++)produce(3,20,10,true);
    assert(g_get_monotonic_time()-start<2000000);assert(fd_count()==before);
    for(import_mode=0;import_mode<=2;import_mode++) {
        g_atomic_int_set(&worker_done,0);
        GThread *thread=g_thread_new("producer",worker,NULL);
        while(!g_atomic_int_get(&worker_done))g_main_context_iteration(NULL,TRUE);
        GdkTexture *t=g_thread_join(thread);
        if(t){pixels(t,31,17);g_object_unref(t);}
        printf("worker mode=%d fds=%d baseline=%d\n",import_mode,fd_count(),before);fflush(stdout);
    }
    assert(fd_count()<=before); /* Wayland may close a setup fd during dispatch. */
    assert(g_atomic_int_get(&imported)==g_atomic_int_get(&released));
    puts("PASS: GLES2/3 immutable pixels after resize/context teardown; GL state/errors; fd lifetime; bounded timeout/CPU fallback; worker import; rejected/late import cleanup");
    return 0;
}
