// SPDX-License-Identifier: GPL-3.0-only
#include "hardware_buffer.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

/* CPU-only BLOB storage. GPU usage is explicitly unsupported: malloc memory
	* must never be advertised as importable by EGL or Vulkan. */
struct AHardwareBuffer {
	AHardwareBuffer_Desc desc;
	atomic_uint references;
	unsigned char *data;
};
static int valid_cpu_usage(uint64_t usage) {
	unsigned read = usage & 15, write = (usage >> 4) & 15;
	return !(usage & ~UINT64_C(0xff)) &&
		(read == 0 || read == 2 || read == 3) && (write == 0 || write == 2 || write == 3);
}
int AHardwareBuffer_isSupported(const AHardwareBuffer_Desc *d) {
	return d && d->width && d->height == 1 && d->layers == 1 &&
		d->format == 0x21 && !d->rfu0 && !d->rfu1 && valid_cpu_usage(d->usage);
}
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *d, AHardwareBuffer **out) {
	if (!out) return -EINVAL;
	*out = NULL;
	if (!AHardwareBuffer_isSupported(d)) return -EINVAL;
	AHardwareBuffer *b = calloc(1, sizeof(*b));
	if (!b) return -ENOMEM;
	b->data = calloc(1, d->width);
	if (!b->data) { free(b); return -ENOMEM; }
	b->desc = *d;
	b->desc.stride = d->width;
	atomic_init(&b->references, 1);
	*out = b;
	return 0;
}
void AHardwareBuffer_acquire(AHardwareBuffer *b) {
	if (b) atomic_fetch_add_explicit(&b->references, 1, memory_order_relaxed);
}
void AHardwareBuffer_release(AHardwareBuffer *b) {
	if (b && atomic_fetch_sub_explicit(&b->references, 1, memory_order_acq_rel) == 1) {
		free(b->data); free(b);
	}
}
void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d) {
	if (b && d) *d = b->desc;
}
int AHardwareBuffer_lock(AHardwareBuffer *b, uint64_t usage, int32_t fence,
			const ATLBufferRect *r, void **out) {
	if (!out) return -EINVAL;
	*out = NULL;
	if (!b || !usage || !valid_cpu_usage(usage)) return -EINVAL;
	if (((usage & 15) && !(b->desc.usage & 15)) ||
			((usage & 0xf0) && !(b->desc.usage & 0xf0))) return -EINVAL;
	if (fence != -1) return -ENOSYS; // No device fences for CPU-only allocations.
	if (r && (r->left < 0 || r->top < 0 || r->right < r->left ||
			r->bottom < r->top || (uint32_t)r->right > b->desc.width || r->bottom > 1)) return -EINVAL;
	// BLOB buffers permit concurrent CPU access, as specified by the NDK.
	*out = b->data;
	return 0;
}
int AHardwareBuffer_unlock(AHardwareBuffer *b, int32_t *fence) {
	if (!b) return -EINVAL;
	if (fence) *fence = -1; // CPU stores require no device synchronization.
	return 0;
}
AHardwareBuffer *AHardwareBuffer_fromHardwareBuffer(JNIEnv *env, jobject obj) {
	if (!obj) return NULL;
	jclass cls = (*env)->FindClass(env, "android/hardware/HardwareBuffer");
	if (!cls) return NULL;
	if (!(*env)->IsInstanceOf(env, obj, cls)) { (*env)->DeleteLocalRef(env, cls); return NULL; }
	jfieldID field = (*env)->GetFieldID(env, cls, "nativeBuffer", "J");
	AHardwareBuffer *b = field ? (void *)(uintptr_t)(*env)->GetLongField(env, obj, field) : NULL;
	(*env)->DeleteLocalRef(env, cls);
	return b;
}
jobject AHardwareBuffer_toHardwareBuffer(JNIEnv *env, AHardwareBuffer *b) {
	if (!b) return NULL;
	jclass cls = (*env)->FindClass(env, "android/hardware/HardwareBuffer");
	if (!cls) return NULL;
	jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(JIIIIJ)V");
	if (!ctor) { (*env)->DeleteLocalRef(env, cls); return NULL; }
	AHardwareBuffer_acquire(b);
	jobject obj = (*env)->NewObject(env, cls, ctor, (jlong)(uintptr_t)b,
		(jint)b->desc.width, (jint)b->desc.height, (jint)b->desc.format, (jint)b->desc.layers, (jlong)b->desc.usage);
	if (!obj) AHardwareBuffer_release(b);
	(*env)->DeleteLocalRef(env, cls);
	return obj;
}
JNIEXPORT void JNICALL Java_android_hardware_HardwareBuffer_nativeRelease(JNIEnv *env, jclass cls, jlong ptr) {
	AHardwareBuffer_release((void *)(uintptr_t)ptr);
}
JNIEXPORT jobject JNICALL Java_android_hardware_HardwareBuffer_create(JNIEnv *env, jclass cls,
	jint width, jint height, jint format, jint layers, jlong usage) {
	AHardwareBuffer_Desc d = {.width=width, .height=height, .format=format, .layers=layers, .usage=usage};
	AHardwareBuffer *b;
	int result = width <= 0 || height <= 0 || layers <= 0 ? -EINVAL : AHardwareBuffer_allocate(&d, &b);
	if (result) {
		jclass error = (*env)->FindClass(env, result == -ENOMEM ? "java/lang/OutOfMemoryError" : "java/lang/IllegalArgumentException");
		if (error) (*env)->ThrowNew(env, error, "Unsupported or invalid CPU HardwareBuffer allocation");
		return NULL;
	}
	jobject obj = AHardwareBuffer_toHardwareBuffer(env, b);
	AHardwareBuffer_release(b);
	return obj;
}
