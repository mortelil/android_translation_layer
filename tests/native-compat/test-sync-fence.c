// SPDX-License-Identifier: GPL-3.0-only
#include <assert.h>
#include <stdint.h>
#include <sys/eventfd.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
int32_t sync_merge(const char *, int32_t, int32_t);
int main(void) {
 assert(sync_merge("bad",-1,-1)==-1 && errno==EBADF);
 int event=eventfd(1,EFD_CLOEXEC), file=open("/dev/null",O_RDWR);
 assert(event>=0 && file>=0);
 assert(sync_merge("not-fences",event,event)==-1);
 assert(sync_merge(NULL,file,event)==-1);
 assert(fcntl(event,F_GETFD)>=0 && fcntl(file,F_GETFD)>=0);
 close(event);close(file);
 puts("PASS: invalid/non-fence descriptors rejected without closing caller descriptors");
 puts("NOT RUN: genuine sync_file merge requires a GPU/sw_sync fixture");
}
