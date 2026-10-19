// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
/* Android x86_64 ABI declarations: link against Android, not host glibc. */
extern int ASharedMemory_create(const char *, unsigned long);
extern int ASharedMemory_setProt(int,int);
extern void *mmap(void *, unsigned long,int,int,int,long);
extern int munmap(void *,unsigned long);
extern int close(int);
extern int fcntl(int,int,...);
extern int mprotect(void *,unsigned long,int);
extern int *__errno(void);
static int memfdBackend;
JNIEXPORT jboolean JNICALL Java_org_atl_sharedmemoryprobe_Probe_isMemfd(JNIEnv *env, jclass cls) { return memfdBackend; }
#define CHECK(x,n) do { if (!(x)) return n; } while(0)
JNIEXPORT jint JNICALL Java_org_atl_sharedmemoryprobe_Probe_check(JNIEnv *env, jclass cls) {
	int fd=ASharedMemory_create("atl-reference",4096);
	CHECK(fd>=0,1);
	memfdBackend=fcntl(fd,1034)>=0; /* Linux F_GET_SEALS */
	void *execBefore=mmap(0,4096,5,1,fd,0);
	int canExec=execBefore!=(void *)-1;
	if(canExec) munmap(execBefore,4096);
	char *live=mmap(0,4096,3,1,fd,0);
	CHECK(live!=(void *)-1,2);
	CHECK(ASharedMemory_setProt(fd,1)==0,3);
	CHECK(ASharedMemory_setProt(fd,3)==-1 && *__errno()==22,4);
	CHECK(mmap(0,4096,3,1,fd,0)==(void *)-1,5);
	char *ro=mmap(0,4096,1,1,fd,0);
	CHECK(ro!=(void *)-1,6);
	live[0]='x'; CHECK(ro[0]=='x',7);
	CHECK(mprotect(ro,4096,3)==-1,8);
	void *execAfter=mmap(0,4096,5,1,fd,0);
	CHECK((execAfter!=(void *)-1)==(memfdBackend && canExec),9);
	if(execAfter!=(void *)-1) munmap(execAfter,4096);
	CHECK(ASharedMemory_setProt(fd,0)==0,10);
	void *afterNone=mmap(0,4096,1,1,fd,0);
	CHECK((afterNone!=(void *)-1)==memfdBackend,11);
	if(afterNone!=(void *)-1) munmap(afterNone,4096);
	munmap(ro,4096);
	munmap(live,4096);
	close(fd);
	return 0;
}
