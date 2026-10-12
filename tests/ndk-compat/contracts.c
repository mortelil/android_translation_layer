// SPDX-License-Identifier: GPL-3.0-only
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
extern int32_t ANativeWindow_setBuffersDataSpace(void *, int32_t);
extern int AImageReader_setImageListener(void *, void *);
extern int android_setsocknetwork(uint64_t, int);
extern void *bionic_eglGetNativeClientBufferANDROID(const void *);
extern int bionic_mallopt(int, int);

int main(void) {
    assert(ANativeWindow_setBuffersDataSpace(NULL, 0) < 0);
    assert(AImageReader_setImageListener(NULL, NULL) == -10004);
    errno = 0;
    assert(android_setsocknetwork(123, -1) == -1 && errno == EBADF);
    assert(android_setsocknetwork(123, 0) == -1 && errno == ENOSYS);
    assert(bionic_eglGetNativeClientBufferANDROID(NULL) == NULL);
    assert(bionic_mallopt(0, 0) == 0);
    puts("PASS: unsupported NDK operations return defined failures");
}
