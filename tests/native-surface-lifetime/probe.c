#include <pthread.h>
#include <stdint.h>
#include "../../src/libandroid/native_window.h"
JNIEXPORT jlong JNICALL Java_org_atl_nativesurface_Probe_take(JNIEnv *e,jclass c,jobject s){return (intptr_t)ANativeWindow_fromSurface(e,s);}
JNIEXPORT jobject JNICALL Java_org_atl_nativesurface_Probe_wrap(JNIEnv *e,jclass c,jlong p){return ANativeWindow_toSurface(e,(void *)(intptr_t)p);}
JNIEXPORT jint JNICALL Java_org_atl_nativesurface_Probe_refs(JNIEnv *e,jclass c,jlong p){return g_atomic_int_get(&((struct ANativeWindow *)(intptr_t)p)->refcount);}
static void *release_worker(void *p){ANativeWindow_release(p);return NULL;}
JNIEXPORT void JNICALL Java_org_atl_nativesurface_Probe_drop(JNIEnv *e,jclass c,jlong p,jboolean worker){if(worker){pthread_t t;pthread_create(&t,NULL,release_worker,(void *)(intptr_t)p);pthread_join(t,NULL);}else ANativeWindow_release((void *)(intptr_t)p);}
static void *refs_worker(void *p){for(int i=0;i<10000;i++){ANativeWindow_acquire(p);ANativeWindow_release(p);}return NULL;}
JNIEXPORT void JNICALL Java_org_atl_nativesurface_Probe_threads(JNIEnv *e,jclass c,jlong p){pthread_t t[4];for(int i=0;i<4;i++)pthread_create(&t[i],NULL,refs_worker,(void *)(intptr_t)p);for(int i=0;i<4;i++)pthread_join(t[i],NULL);}
JNIEXPORT jboolean JNICALL Java_org_atl_nativesurface_Probe_post(JNIEnv *e,jclass c,jlong p){unsigned char pixels[8*8*4]={0};return atl_native_window_post_rgba((void *)(intptr_t)p,pixels,8,8);}
extern int32_t ANativeWindow_getWidth(struct ANativeWindow *);
JNIEXPORT jint JNICALL Java_org_atl_nativesurface_Probe_width(JNIEnv *e,jclass c,jlong p){return ANativeWindow_getWidth((void *)(intptr_t)p);}
