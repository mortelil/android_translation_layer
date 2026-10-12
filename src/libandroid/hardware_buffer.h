// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <stdint.h>
#include <jni.h>
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct {
	uint32_t width, height, layers, format;
	uint64_t usage;
	uint32_t stride, rfu0;
	uint64_t rfu1;
} AHardwareBuffer_Desc;
typedef struct { int32_t left, top, right, bottom; } ATLBufferRect;
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *, AHardwareBuffer **);
int AHardwareBuffer_isSupported(const AHardwareBuffer_Desc *);
void AHardwareBuffer_acquire(AHardwareBuffer *);
void AHardwareBuffer_release(AHardwareBuffer *);
void AHardwareBuffer_describe(const AHardwareBuffer *, AHardwareBuffer_Desc *);
int AHardwareBuffer_lock(AHardwareBuffer *, uint64_t, int32_t, const ATLBufferRect *, void **);
int AHardwareBuffer_unlock(AHardwareBuffer *, int32_t *);
/* One plane of a locked buffer; no padding, so AHardwareBuffer_Plane is 16 bytes. */
typedef struct {
	void *data;
	uint32_t pixelStride;
	uint32_t rowStride;
} AHardwareBuffer_Plane;
typedef struct {
	uint32_t planeCount;
	AHardwareBuffer_Plane planes[4];
} AHardwareBuffer_Planes;
int AHardwareBuffer_lockPlanes(AHardwareBuffer *, uint64_t, int32_t, const ATLBufferRect *, AHardwareBuffer_Planes *);
AHardwareBuffer *AHardwareBuffer_fromHardwareBuffer(JNIEnv *, jobject);
jobject AHardwareBuffer_toHardwareBuffer(JNIEnv *, AHardwareBuffer *);

int atl_hardware_buffer_copy_rgba(AHardwareBuffer *, unsigned char **, int *, int *);
