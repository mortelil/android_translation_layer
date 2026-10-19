// SPDX-License-Identifier: GPL-3.0-only
#include "hardware_buffer.h"
#include <assert.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
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
	AHardwareBuffer_Planes planes;
	assert(!AHardwareBuffer_lockPlanes(b,3,-1,NULL,&planes));
	assert(planes.planeCount==1 && planes.planes[0].data==q && planes.planes[0].rowStride==16);
	assert(!AHardwareBuffer_unlock(b,NULL));
	AHardwareBuffer_release(b);
	d.usage |= 0x100; assert(!AHardwareBuffer_isSupported(&d));
	assert(AHardwareBuffer_allocate(&d,&b)==-EINVAL && b==NULL);
	d.usage=3; d.height=2; assert(!AHardwareBuffer_isSupported(&d));
	d.height=1; assert(!AHardwareBuffer_allocate(&d,&b));
	assert(AHardwareBuffer_lock(b,0x30,-1,NULL,&p)==-EINVAL);
	AHardwareBuffer_release(b);

	/* Cross-process handle transfer: the sender hands the buffer's descriptor to a forked
	   receiver over an AF_UNIX socketpair, and both map the same pixels. */
	int fds[2];
	assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, fds));
	AHardwareBuffer_Desc xd={.width=4,.height=2,.layers=1,.format=1,.usage=0x33};
	AHardwareBuffer *x=NULL; void *xp=NULL;
	assert(!AHardwareBuffer_allocate(&xd,&x));
	assert(!AHardwareBuffer_lock(x,0x30,-1,NULL,&xp));
	memcpy(xp,"abcd",4);
	pid_t child=fork();
	assert(child>=0);
	if (!child) {
		close(fds[0]);
		AHardwareBuffer *r=NULL;
		assert(!AHardwareBuffer_recvHandleFromUnixSocket(fds[1],&r));
		AHardwareBuffer_Desc got; AHardwareBuffer_describe(r,&got);
		assert(got.width==4 && got.height==2 && got.format==1 && got.usage==0x33 && got.stride==4);
		void *rp=NULL;
		assert(!AHardwareBuffer_lock(r,0x30,-1,NULL,&rp));
		assert(!memcmp(rp,"abcd",4)); /* the same allocation, not a copy */
		memcpy((char *)rp+4,"wxyz",4);
		assert(!AHardwareBuffer_unlock(r,NULL));
		AHardwareBuffer_release(r);
		close(fds[1]);
		_exit(0);
	}
	close(fds[1]);
	assert(!AHardwareBuffer_sendHandleToUnixSocket(x,fds[0]));
	int status;
	assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
	assert(!memcmp((char *)xp+4,"wxyz",4)); /* written by the receiver, seen by the sender */
	assert(!AHardwareBuffer_unlock(x,NULL));
	AHardwareBuffer_release(x);
	close(fds[0]);

	/* Failures: a closed peer, a missing descriptor and invalid arguments. */
	int pair[2];
	assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, pair));
	assert(AHardwareBuffer_sendHandleToUnixSocket(NULL,pair[0])==-1 && errno==EINVAL);
	assert(AHardwareBuffer_recvHandleFromUnixSocket(pair[0],NULL)==-1 && errno==EINVAL);
	AHardwareBuffer *nope=(AHardwareBuffer *)1;
	close(pair[1]);
	assert(AHardwareBuffer_recvHandleFromUnixSocket(pair[0],&nope)==-1 && nope==NULL && errno==ECONNRESET);
	close(pair[0]);
	puts("PASS: CPU BLOB lifetime, concurrent access, data roundtrip, permissions, unsupported GPU/fence "
	     "rejection and cross-process descriptor transfer");
}
