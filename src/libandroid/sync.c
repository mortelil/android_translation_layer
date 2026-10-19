// SPDX-License-Identifier: GPL-3.0-only
/*
 * The android/sync.h fence API. libxul.so references sync_merge, which AOSP
 * exports from libsync under the LIBSYNC version tag
 * (system/core/libsync/libsync.map.txt:19-27, tag android-17.0.0_r1).
 *
 * A sync file is a file descriptor which reports POLLIN once every fence it
 * contains has signalled (system/core/libsync/include/ndk/sync.h:29-48).
 * sync_merge() asks the kernel to build a new sync file out of two existing
 * ones; AOSP tries the post-4.7 uapi first and the pre-4.7 one when the kernel
 * answers ENOTTY (system/core/libsync/sync.c:154-208), so that is what the real
 * path here does too.
 *
 * Only kernel sync_file descriptors are supported. An eventfd is not a
 * sync_file: EGL cannot import it, reads consume its readiness, and arbitrary
 * descriptors must not be accepted. Preserve ioctl failure on unsupported
 * descriptors rather than manufacture a successful fence.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/sync_file.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* Structure of the pre-4.7 merge request. It is no longer in the uapi headers,
 * so it is reproduced from AOSP's libsync (system/core/libsync/sync.c:58-75). */
struct atl_sync_legacy_merge_data {
	int32_t fd2;
	char name[32];
	int32_t fence;
};
#define ATL_SYNC_IOC_LEGACY_MERGE _IOWR(SYNC_IOC_MAGIC, 1, struct atl_sync_legacy_merge_data)

/* Which merge uapi this kernel speaks; AOSP caches the first answer the same way
 * (sync.c:107-121). */
enum { ATL_UAPI_UNKNOWN, ATL_UAPI_MODERN, ATL_UAPI_LEGACY };
static atomic_int atl_sync_uapi_version = ATL_UAPI_UNKNOWN;

static int atl_sync_modern_merge(const char *name, int fd1, int fd2) {
	struct sync_merge_data data;
	memset(&data, 0, sizeof(data));
	data.fd2 = fd2;
	strncpy(data.name, name, sizeof(data.name) - 1);
	if (ioctl(fd1, SYNC_IOC_MERGE, &data) < 0) return -1;
	return data.fence;
}

static int atl_sync_legacy_merge(const char *name, int fd1, int fd2) {
	struct atl_sync_legacy_merge_data data;
	memset(&data, 0, sizeof(data));
	data.fd2 = fd2;
	strncpy(data.name, name, sizeof(data.name) - 1);
	if (ioctl(fd1, ATL_SYNC_IOC_LEGACY_MERGE, &data) < 0) return -1;
	return data.fence;
}

int32_t sync_merge(const char *name, int32_t fd1, int32_t fd2) {
	/* A fence without a name is still a valid merge request. */
	if (!name) name = "";
	int uapi = atomic_load_explicit(&atl_sync_uapi_version, memory_order_acquire);
	int ret;
	if (uapi == ATL_UAPI_UNKNOWN || uapi == ATL_UAPI_MODERN) {
		ret = atl_sync_modern_merge(name, fd1, fd2);
		if (ret >= 0 || errno != ENOTTY) {
			if (ret >= 0 && uapi == ATL_UAPI_UNKNOWN)
				atomic_store_explicit(&atl_sync_uapi_version, ATL_UAPI_MODERN, memory_order_release);
			return ret;
		}
	}
	ret = atl_sync_legacy_merge(name, fd1, fd2);
	if (ret >= 0) {
		if (uapi == ATL_UAPI_UNKNOWN)
			atomic_store_explicit(&atl_sync_uapi_version, ATL_UAPI_LEGACY, memory_order_release);
		return ret;
	}
	/* Unsupported descriptors keep the kernel error. */
	if (errno != ENOTTY) return ret;
	return -1;
}
