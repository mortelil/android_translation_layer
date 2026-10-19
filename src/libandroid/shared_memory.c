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
 * Match AOSP's memfd backend rather than the legacy ashmem device's stricter
 * protection mask. memfd_set_prot_region only restricts future writes; READ
 * and EXEC bits do not revoke mmap permissions. Existing mappings survive.
 * Behaviour reference (implementation below is ATL's, not copied):
 * platform/system/core, d3e91011ef767c231ec9c374cfc5c18657566b9c,
 * libcutils/ashmem-dev.cpp, memfd_set_prot_region().
 * F_SEAL_EXEC is deliberately not used: it locks file mode, not executable mmap.
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
	if (prot & ~current) { errno = EINVAL; return -1; }

	if (!(prot & PROT_WRITE)) added |= F_SEAL_FUTURE_WRITE;

	if (added && fcntl(fd, F_ADD_SEALS, added) < 0) return -1;
	return 0;
}
