// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include "hardware_buffer.h"
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

/* CPU-only BLOB and RGBA8888 storage. GPU usage is explicitly unsupported: a CPU mapping
	* must never be advertised as importable by EGL or Vulkan. The storage lives in a memfd,
	* which is what makes the buffer transferable to another process (see
	* AHardwareBuffer_sendHandleToUnixSocket). */
struct AHardwareBuffer {
	AHardwareBuffer_Desc desc;
	atomic_uint references;
	unsigned char *data;
	int fd;
	size_t size;
	atomic_bool image_locked;
};
static int valid_cpu_usage(uint64_t usage) {
	unsigned read = usage & 15, write = (usage >> 4) & 15;
	return !(usage & ~UINT64_C(0xff)) &&
		(read == 0 || read == 2 || read == 3) && (write == 0 || write == 2 || write == 3);
}
/* CPU bytes a descriptor needs: RGBA8888 is four bytes per pixel, BLOB one byte. */
static size_t atl_buffer_size(const AHardwareBuffer_Desc *d) {
	return d->format == 1 ? (size_t)d->width * d->height * 4 : d->width;
}
int AHardwareBuffer_isSupported(const AHardwareBuffer_Desc *d) {
	if (!d || !d->width || !d->height || d->layers != 1 || d->rfu0 || d->rfu1 || !valid_cpu_usage(d->usage)) return 0;
	if (d->format == 0x21) return d->height == 1;
	return d->format == 1 && d->width <= INT32_MAX && d->height <= INT32_MAX &&
		(uint64_t)d->width * d->height <= SIZE_MAX / 4;
}
/*
 * Allocate CPU-only storage in an anonymous memfd. AOSP gets a gralloc allocation
 * whose file descriptors can be handed to another process with
 * AHardwareBuffer_sendHandleToUnixSocket; a memfd mapped MAP_SHARED gives ATL the
 * same property (receiver and sender map the same bytes) while the buffer stays
 * ordinary readable/writable memory for the CPU-only paths above.
 */
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *d, AHardwareBuffer **out) {
	if (!out) return -EINVAL;
	*out = NULL;
	if (!AHardwareBuffer_isSupported(d)) return -EINVAL;
	size_t size = atl_buffer_size(d);
	int fd = memfd_create("AHardwareBuffer", MFD_CLOEXEC | MFD_ALLOW_SEALING);
	if (fd < 0) return -errno;
	if (ftruncate(fd, (off_t)size) || fcntl(fd, F_ADD_SEALS, F_SEAL_GROW | F_SEAL_SHRINK)) { int error = errno; close(fd); return -error; }
	void *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (data == MAP_FAILED) { int error = errno; close(fd); return -error; }
	AHardwareBuffer *b = calloc(1, sizeof(*b));
	if (!b) { munmap(data, size); close(fd); return -ENOMEM; }
	b->data = data;
	b->fd = fd;
	b->size = size;
	b->desc = *d;
	b->desc.stride = d->width;
	atomic_init(&b->references, 1);
	atomic_init(&b->image_locked, 0);
	*out = b;
	return 0;
}
void AHardwareBuffer_acquire(AHardwareBuffer *b) {
	if (b) atomic_fetch_add_explicit(&b->references, 1, memory_order_relaxed);
}
void AHardwareBuffer_release(AHardwareBuffer *b) {
	if (b && atomic_fetch_sub_explicit(&b->references, 1, memory_order_acq_rel) == 1) {
		munmap(b->data, b->size);
		close(b->fd);
		free(b);
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
			r->bottom < r->top || (uint32_t)r->right > b->desc.width || (uint32_t)r->bottom > b->desc.height)) return -EINVAL;
	// BLOB permits concurrent CPU access; image buffers require exclusive access.
	if (b->desc.format == 1 && atomic_exchange(&b->image_locked, 1)) return -EBUSY;
	*out = b->data;
	return 0;
}
/*
 * Multi-plane lock (API 29). ATL's AHardwareBuffer is a single-plane CPU
 * allocation, which is exactly one plane, so the plane view of the locked region
 * is reported; the lock itself performs the same validation and exclusivity
 * accounting as AHardwareBuffer_lock, including its error numbers.
 */
int AHardwareBuffer_lockPlanes(AHardwareBuffer *b, uint64_t usage, int32_t fence,
			const ATLBufferRect *r, AHardwareBuffer_Planes *out) {
	if (!out) return -EINVAL;
	out->planeCount = 0;
	void *data = NULL;
	int result = AHardwareBuffer_lock(b, usage, fence, r, &data);
	if (result) return result;
	uint32_t pixelStride = b->desc.format == 1 ? 4 : 1;
	uint32_t rowStride = b->desc.format == 1 ? (uint32_t)b->desc.width * 4 : (uint32_t)b->desc.width;
	unsigned char *plane = data;
	/* Like lock(), plane data addresses the buffer origin, not the dirty rect. */
	out->planeCount = 1;
	out->planes[0].data = plane;
	out->planes[0].pixelStride = pixelStride;
	out->planes[0].rowStride = rowStride;
	return 0;
}
int AHardwareBuffer_unlock(AHardwareBuffer *b, int32_t *fence) {
	if (!b) return -EINVAL;
	if (b->desc.format == 1 && !atomic_exchange(&b->image_locked, 0)) return -EINVAL;
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

/* Snapshot CPU RGBA pixels for the compositor. No GPU import is advertised.
 * The caller must obey buffer/fence ownership and not write after submission. */
int atl_hardware_buffer_copy_rgba(AHardwareBuffer *b, unsigned char **pixels, int *width, int *height) {
	*pixels = NULL;
	if (!b || b->desc.format != 1) return -ENOTSUP;
	if (atomic_exchange(&b->image_locked, 1)) return -EBUSY;
	size_t size = (size_t)b->desc.width * b->desc.height * 4;
	unsigned char *copy = malloc(size);
	if (copy) memcpy(copy, b->data, size);
	atomic_store(&b->image_locked, 0);
	if (!copy) return -ENOMEM;
	*pixels = copy; *width = b->desc.width; *height = b->desc.height;
	return 0;
}

/*
 * Cross-process handle transfer.
 *
 * Reference: NDK <android/hardware_buffer.h> (AHardwareBuffer_sendHandleToUnixSocket,
 * AHardwareBuffer_recvHandleFromUnixSocket, API 26): the sender passes the buffer's file
 * descriptor over an AF_UNIX socket, the receiver gets a buffer for the same allocation, and
 * both return 0 on success or -1 with errno set. AOSP resolves it to the gralloc
 * native_handle's descriptors sent with SCM_RIGHTS; the payload framing is unspecified there
 * because both ends are the platform's own implementation. ATL's transferable object is the
 * memfd backing the buffer (see AHardwareBuffer_allocate), and because a memfd carries no
 * geometry the sender also transmits a small header describing the allocation, which the
 * receiver validates before mapping the received descriptor.
 */
#define ATL_HARDWARE_BUFFER_TRANSFER_MAGIC UINT32_C(0x31424841) /* "AHB1" */

struct atl_hardware_buffer_transfer {
	uint32_t magic;
	uint32_t reserved;
	AHardwareBuffer_Desc desc;
};

/* Take ownership of fd on success; the caller keeps it on failure. */
static AHardwareBuffer *atl_hardware_buffer_from_fd(int fd, const AHardwareBuffer_Desc *d) {
	if (!AHardwareBuffer_isSupported(d) || d->stride != d->width) { errno = EINVAL; return NULL; }
	int seals = fcntl(fd, F_GET_SEALS);
	if (seals < 0 || (seals & (F_SEAL_GROW | F_SEAL_SHRINK)) != (F_SEAL_GROW | F_SEAL_SHRINK)) { errno = EINVAL; return NULL; }
	size_t size = atl_buffer_size(d);
	struct stat st;
	if (fstat(fd, &st) || st.st_size < 0 || (uintmax_t)st.st_size < size) { errno = EINVAL; return NULL; }
	void *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	if (data == MAP_FAILED) return NULL;
	AHardwareBuffer *b = calloc(1, sizeof(*b));
	if (!b) { munmap(data, size); errno = ENOMEM; return NULL; }
	b->data = data;
	b->fd = fd;
	b->size = size;
	b->desc = *d;
	atomic_init(&b->references, 1);
	atomic_init(&b->image_locked, 0);
	return b;
}

int AHardwareBuffer_sendHandleToUnixSocket(const AHardwareBuffer *b, int socketFd) {
	if (!b) { errno = EINVAL; return -1; }
	struct atl_hardware_buffer_transfer transfer = {
		.magic = ATL_HARDWARE_BUFFER_TRANSFER_MAGIC,
		.reserved = 0,
		.desc = b->desc,
	};
	char control[CMSG_SPACE(sizeof(int))];
	struct iovec iov = { .iov_base = &transfer, .iov_len = sizeof(transfer) };
	struct msghdr msg = {
		.msg_iov = &iov,
		.msg_iovlen = 1,
		.msg_control = control,
		.msg_controllen = sizeof(control),
	};
	struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
	cmsg->cmsg_level = SOL_SOCKET;
	cmsg->cmsg_type = SCM_RIGHTS;
	cmsg->cmsg_len = CMSG_LEN(sizeof(int));
	memcpy(CMSG_DATA(cmsg), &b->fd, sizeof(int));

	ssize_t sent;
	do {
		sent = sendmsg(socketFd, &msg, MSG_NOSIGNAL);
	} while (sent < 0 && errno == EINTR);
	if (sent < 0) return -1;
	/* The descriptor travels with the first bytes; a short send is completed by plain writes. */
	while ((size_t)sent < sizeof(transfer)) {
		ssize_t rest = send(socketFd, (const char *)&transfer + sent, sizeof(transfer) - (size_t)sent, MSG_NOSIGNAL);
		if (rest < 0) { if (errno == EINTR) continue; return -1; }
		if (rest == 0) { errno = EPIPE; return -1; }
		sent += rest;
	}
	return 0;
}

int AHardwareBuffer_recvHandleFromUnixSocket(int socketFd, AHardwareBuffer **outBuffer) {
	if (!outBuffer) { errno = EINVAL; return -1; }
	*outBuffer = NULL;
	struct atl_hardware_buffer_transfer transfer;
	char control[CMSG_SPACE(sizeof(int)) * 4];
	size_t received = 0;
	int fd = -1;
	memset(&transfer, 0, sizeof(transfer));

	while (received < sizeof(transfer)) {
		struct iovec iov = { .iov_base = (char *)&transfer + received, .iov_len = sizeof(transfer) - received };
		struct msghdr msg = {
			.msg_iov = &iov,
			.msg_iovlen = 1,
			.msg_control = control,
			.msg_controllen = sizeof(control),
		};
		ssize_t bytes = recvmsg(socketFd, &msg, MSG_CMSG_CLOEXEC);
		if (bytes < 0) {
			if (errno == EINTR) continue;
			goto fail;
		}
		if (bytes == 0) { errno = ECONNRESET; goto fail; } /* peer closed without sending a buffer */
		received += (size_t)bytes;
		for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
			if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
			if (cmsg->cmsg_len < CMSG_LEN(sizeof(int))) continue;
			size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
			int *fds = (int *)CMSG_DATA(cmsg);
			for (size_t i = 0; i < count; i++) {
				/* Take the first descriptor; close anything extra the sender attached. */
				if (fd < 0) fd = fds[i];
				else close(fds[i]);
			}
		}
		if (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) { errno = EPROTO; goto fail; }
	}
	if (fd < 0) { errno = EPROTO; goto fail; } /* nothing was attached to the payload */
	if (transfer.magic != ATL_HARDWARE_BUFFER_TRANSFER_MAGIC || transfer.reserved) { errno = EPROTO; goto fail; }
	*outBuffer = atl_hardware_buffer_from_fd(fd, &transfer.desc);
	if (!*outBuffer) goto fail;
	return 0;

fail:
	{
		int error = errno;
		if (fd >= 0) close(fd);
		errno = error;
	}
	return -1;
}
