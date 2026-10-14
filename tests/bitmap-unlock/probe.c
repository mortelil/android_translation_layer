/* SPDX-License-Identifier: GPL-3.0-only */
#include <jni.h>
#include <stdint.h>
struct Info { uint32_t width,height,stride; int32_t format; uint32_t flags; };
extern int AndroidBitmap_getInfo(JNIEnv *, jobject, struct Info *);
extern int AndroidBitmap_lockPixels(JNIEnv *, jobject, void **);
extern int AndroidBitmap_unlockPixels(JNIEnv *, jobject);
JNIEXPORT jboolean JNICALL Java_org_atl_bitmapunlock_Probe_fill(JNIEnv *env, jclass c, jobject b, jint red) {
    struct Info info = {0}; void *pixels = 0;
    if(AndroidBitmap_getInfo(env,b,&info) || info.format!=1 || info.stride<info.width*4) return JNI_FALSE;
    if(AndroidBitmap_lockPixels(env,b,&pixels) || !pixels) return JNI_FALSE;
    for(uint32_t y=0;y<info.height;y++) for(uint32_t x=0;x<info.width;x++) {
        uint8_t *p=(uint8_t *)pixels+y*info.stride+4*x;
        p[0]=red;p[1]=0;p[2]=0;p[3]=255;
    }
    return AndroidBitmap_unlockPixels(env,b)==0;
}
