// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

/* musl headers may predate F_SEAL_FUTURE_WRITE. */
#ifndef F_SEAL_FUTURE_WRITE
#define F_SEAL_FUTURE_WRITE 0x0010
#endif

int ASharedMemory_create(const char *name, size_t size)
{
	if (!size) { errno = EINVAL; return -1; }
	if ((uintmax_t)size > INT64_MAX) { errno = EOVERFLOW; return -1; }
	// memfd names are diagnostic labels, not paths.
	char label[250];
	size_t length = name ? strnlen(name, sizeof(label) - 1) : 0;
	if (length) memcpy(label, name, length);
	label[length] = '\0';
	int fd = memfd_create(label, MFD_CLOEXEC | MFD_ALLOW_SEALING);
	if (fd < 0) return -1;
	if (ftruncate(fd, (off_t)size) ||
	    fcntl(fd, F_ADD_SEALS, F_SEAL_GROW | F_SEAL_SHRINK)) {
		int error = errno;
		close(fd);
		errno = error;
		return -1;
	}
	return fd;
}

size_t ASharedMemory_getSize(int fd)
{
	struct stat st;
	int seals = fcntl(fd, F_GET_SEALS);
	if (seals < 0 || (seals & (F_SEAL_GROW | F_SEAL_SHRINK)) !=
	    (F_SEAL_GROW | F_SEAL_SHRINK) || fstat(fd, &st) || st.st_size < 0)
		return 0;
	return (size_t)st.st_size;
}

/*
 * ASharedMemory_setProt(fd, prot): restrict access to a shared memory region.
 *
 * Behavioural reference: the NDK's frozen header,
 * art_standalone/libandroidfw/include/android/sharedmem.h ("Restrict access of
 * shared memory region"): a region is created with PROT_READ|PROT_WRITE|PROT_EXEC,
 * "access can only be removed", the effect "applies globally to all file
 * descriptors in all processes across the system that refer to this shared
 * memory region", "existing memory mapped regions are not affected", and the
 * return is 0 for success / -1 with errno set. The framework side of the same
 * contract is frameworks-base/core/java/android/os/SharedMemory.java:127-156
 * (android-17.0.0_r1, commit 94b4c163): setProtect() → nSetProt(), with the
 * PROT mask PROT_READ|PROT_WRITE|PROT_EXEC|PROT_NONE (lines 117-122, 143-150).
 *
 * ATL uses memfd seals to restrict future shared writable mappings. Existing
 * mappings are unaffected. F_SEAL_EXEC locks file mode changes; it does NOT
 * prohibit executable mmap. There is no equivalent read/execute revocation
 * here, so reject those requests without changing the region.
 */
int ASharedMemory_setProt(int fd, int prot)
{
	const int known = PROT_READ | PROT_WRITE | PROT_EXEC;
	int current = PROT_READ | PROT_WRITE | PROT_EXEC;
	int seals, added = 0;

	if (prot & ~known) { errno = EINVAL; return -1; }

	seals = fcntl(fd, F_GET_SEALS);
	if (seals < 0) return -1; /* not a shared memory file descriptor */

	if (seals & (F_SEAL_WRITE | F_SEAL_FUTURE_WRITE)) current &= ~PROT_WRITE;

	if ((seals & (F_SEAL_GROW | F_SEAL_SHRINK)) != (F_SEAL_GROW | F_SEAL_SHRINK)) { errno = EINVAL; return -1; }

	/* Access can only be removed, never added back. */
	if (prot & ~current) { errno = EPERM; return -1; }
	/* No Linux seal removes read access; do not claim success for it. */
	if ((prot & (PROT_READ | PROT_EXEC)) != (PROT_READ | PROT_EXEC)) { errno = ENOTSUP; return -1; }

	if (!(prot & PROT_WRITE)) added |= F_SEAL_FUTURE_WRITE;

	if (added && fcntl(fd, F_ADD_SEALS, added) < 0) return -1;
	return 0;
}
