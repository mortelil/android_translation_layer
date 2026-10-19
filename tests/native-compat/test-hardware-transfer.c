// SPDX-License-Identifier: GPL-3.0-only
// Includes the implementation to exercise malformed private wire headers.
#include "../../src/libandroid/hardware_buffer.c"
#include <assert.h>
#include <dirent.h>
static int fd_count(void) {
 DIR *d=opendir("/proc/self/fd"); assert(d); int n=0;
 while(readdir(d)) n++; closedir(d); return n;
}
static void send_header(int socket, struct atl_hardware_buffer_transfer *t, int *fds, size_t count) {
 char control[CMSG_SPACE(32*sizeof(int))]={0};
 struct iovec iov={t,sizeof(*t)};
 struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=CMSG_SPACE(count*sizeof(int))};
 struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
 c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(count*sizeof(int));
 memcpy(CMSG_DATA(c),fds,count*sizeof(int));
 assert(sendmsg(socket,&msg,MSG_NOSIGNAL)==sizeof(*t));
}
int main(void) {
 AHardwareBuffer_Desc d={.width=4,.height=2,.layers=1,.format=1,.usage=0x33};
 AHardwareBuffer *b=NULL,*r=NULL; assert(!AHardwareBuffer_allocate(&d,&b));
 int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
 assert(ftruncate(b->fd,1)==-1 && errno==EPERM);
 assert(!AHardwareBuffer_sendHandleToUnixSocket(b,pair[0]));
 assert(!AHardwareBuffer_recvHandleFromUnixSocket(pair[1],&r));
 assert(fcntl(r->fd,F_GETFD)&FD_CLOEXEC); AHardwareBuffer_release(r);
 struct atl_hardware_buffer_transfer t={.magic=ATL_HARDWARE_BUFFER_TRANSFER_MAGIC,.desc=b->desc};
 int fds[32]; for(int i=0;i<32;i++) fds[i]=b->fd;
 for(int mode=0;mode<4;mode++) {
  t.magic=ATL_HARDWARE_BUFFER_TRANSFER_MAGIC; t.desc=b->desc;
  if(mode==0) t.magic=0;
  if(mode==1) t.desc.stride++;
  int unsealed=-1;
  if(mode==2) { unsealed=memfd_create("bad",MFD_CLOEXEC);assert(unsealed>=0);assert(!ftruncate(unsealed,32));fds[0]=unsealed; }
  int before=fd_count();
  send_header(pair[0],&t,fds,mode==3?32:1);
  r=(void*)1;assert(AHardwareBuffer_recvHandleFromUnixSocket(pair[1],&r)==-1 && r==NULL);
  assert(fd_count()==before);
  if(unsealed>=0) close(unsealed); fds[0]=b->fd;
 }
 AHardwareBuffer_release(b);close(pair[0]);close(pair[1]);
 puts("PASS: resize seals, CLOEXEC, bad geometry/magic, unsealed storage and ancillary truncation without FD leaks");
}
