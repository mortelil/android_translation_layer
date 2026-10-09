// SPDX-License-Identifier: GPL-3.0-only
#include "hardware_buffer.h"
#include <assert.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
int main(void) {
	AHardwareBuffer_Desc d={.width=16,.height=1,.layers=1,.format=0x21,.usage=0x33};
	AHardwareBuffer *b=NULL; void *p=NULL, *q=NULL;
	assert(!AHardwareBuffer_allocate(&d,&b));
	AHardwareBuffer_acquire(b); AHardwareBuffer_release(b);
	assert(!AHardwareBuffer_lock(b,0x30,-1,NULL,&p));
	memcpy(p,"roundtrip",10);
	assert(!AHardwareBuffer_lock(b,3,-1,NULL,&q)); assert(p==q && !strcmp(q,"roundtrip"));
	int32_t fence=0; assert(!AHardwareBuffer_unlock(b,&fence) && fence==-1);
	assert(!AHardwareBuffer_unlock(b,NULL));
	assert(AHardwareBuffer_lock(b,0x100,-1,NULL,&p)==-EINVAL && p==NULL);
	assert(AHardwareBuffer_lock(b,3,1,NULL,&p)==-ENOSYS);
	AHardwareBuffer_Desc actual; AHardwareBuffer_describe(b,&actual); assert(actual.stride==16);
	AHardwareBuffer_release(b);
	d.usage |= 0x100; assert(!AHardwareBuffer_isSupported(&d));
	assert(AHardwareBuffer_allocate(&d,&b)==-EINVAL && b==NULL);
	d.usage=3; d.height=2; assert(!AHardwareBuffer_isSupported(&d));
	d.height=1; assert(!AHardwareBuffer_allocate(&d,&b));
	assert(AHardwareBuffer_lock(b,0x30,-1,NULL,&p)==-EINVAL);
	AHardwareBuffer_release(b);
	puts("PASS: CPU BLOB lifetime, concurrent access, data roundtrip, permissions and unsupported GPU/fence rejection");
}
