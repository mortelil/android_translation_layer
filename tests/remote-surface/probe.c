#define _GNU_SOURCE
#include <sys/mman.h>
#include <unistd.h>
#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
extern void (*bionic_eglGetProcAddress(const char *name))(void);
extern EGLint bionic_eglGetError(void);
#include "../../src/libandroid/native_window.h"
#include "../../src/api-impl-jni/widgets/android_view_SurfaceView.h"
JNIEXPORT jboolean JNICALL Java_org_atl_remotesurface_Probe_paint(JNIEnv *e,jclass c,jobject s) {
    struct ANativeWindow *w = ANativeWindow_fromSurface(e,s);
    if (!w) return 0;
    jobject roundtrip = ANativeWindow_toSurface(e,w);
    jclass parcel = (*e)->FindClass(e,"android/os/Parcel");
    jobject data = (*e)->CallStaticObjectMethod(e,parcel,(*e)->GetStaticMethodID(e,parcel,"obtain","()Landroid/os/Parcel;"));
    jclass surface = (*e)->GetObjectClass(e,s);
    (*e)->CallVoidMethod(e,roundtrip,(*e)->GetMethodID(e,surface,"writeToParcel","(Landroid/os/Parcel;I)V"),data,1);
    (*e)->CallVoidMethod(e,data,(*e)->GetMethodID(e,parcel,"setDataPosition","(I)V"),0);
    jobject forwarded = (*e)->NewObject(e,surface,(*e)->GetMethodID(e,surface,"<init>","()V"));
    (*e)->CallVoidMethod(e,forwarded,(*e)->GetMethodID(e,surface,"readFromParcel","(Landroid/os/Parcel;)V"),data);
    ANativeWindow_release(w);
    w = ANativeWindow_fromSurface(e,forwarded);
    (*e)->CallVoidMethod(e,forwarded,(*e)->GetMethodID(e,surface,"release","()V"));
    (*e)->CallVoidMethod(e,data,(*e)->GetMethodID(e,parcel,"recycle","()V"));
    (*e)->DeleteLocalRef(e,forwarded); (*e)->DeleteLocalRef(e,roundtrip);
    (*e)->DeleteLocalRef(e,data); (*e)->DeleteLocalRef(e,parcel); (*e)->DeleteLocalRef(e,surface);
    if (!w || (*e)->ExceptionCheck(e)) return 0;
    EGLDisplay d = bionic_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL_NO_CONTEXT; EGLSurface target = EGL_NO_SURFACE;
    EGLConfig config; EGLint count; int ok=0;
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    if (!eglInitialize(d,NULL,NULL) || !eglBindAPI(EGL_OPENGL_ES_API) || !eglChooseConfig(d,attrs,&config,1,&count) || !count) goto done;
    context=eglCreateContext(d,config,EGL_NO_CONTEXT,ctx);
    target=bionic_eglCreateWindowSurface(d,config,w,NULL);
    if (target == EGL_NO_SURFACE || context == EGL_NO_CONTEXT || !bionic_eglMakeCurrent(d,target,target,context)) goto done;
    glClearColor(0,1,0,1); glClear(GL_COLOR_BUFFER_BIT);
    PFNEGLSWAPBUFFERSWITHDAMAGEKHRPROC khr=(void*)bionic_eglGetProcAddress("eglSwapBuffersWithDamageKHR");
    PFNEGLSWAPBUFFERSWITHDAMAGEEXTPROC ext=(void*)bionic_eglGetProcAddress("eglSwapBuffersWithDamageEXT");
    if (!khr || !ext) goto done;
    if (khr(d,target,NULL,-1) || bionic_eglGetError()!=EGL_BAD_PARAMETER || bionic_eglGetError()!=EGL_SUCCESS) goto done;
    if (ext(d,target,NULL,1) || bionic_eglGetError()!=EGL_BAD_PARAMETER) goto done;
    ok=khr(d,target,NULL,0) && ext(d,target,NULL,0);
done:
    bionic_eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    if(target != EGL_NO_SURFACE) bionic_eglDestroySurface(d,target);
    if(context != EGL_NO_CONTEXT) eglDestroyContext(d,context);
    ANativeWindow_release(w);
    return ok;
}
JNIEXPORT jboolean JNICALL Java_org_atl_remotesurface_Probe_green(JNIEnv *e,jclass c,jobject s) {
    struct ANativeWindow *w=ANativeWindow_fromSurface(e,s);
    if(!w) return 0;
    GdkTexture *t=SURFACE_VIEW_WIDGET(w->surface_view_widget)->texture;
    int ok=0;
    if(t){int width=gdk_texture_get_width(t),height=gdk_texture_get_height(t);guchar *p=g_malloc((size_t)width*height*4);gdk_texture_download(t,p,width*4);ok=p[0]==0 && p[1]==255 && p[2]==0 && p[3]==255;g_free(p);}
    ANativeWindow_release(w);return ok;
}
JNIEXPORT jboolean JNICALL Java_org_atl_remotesurface_Probe_rejectBadFrame(JNIEnv *e,jclass c,jobject s) {
    struct ANativeWindow *w=ANativeWindow_fromSurface(e,s);
    int fd=memfd_create("invalid-test",MFD_CLOEXEC|MFD_ALLOW_SEALING);ftruncate(fd,16);
    int rejected=!atl_native_window_present_fd(w,fd,2,2);
    close(fd);ANativeWindow_release(w);return rejected;
}
