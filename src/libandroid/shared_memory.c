// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

int ASharedMemory_create(const char *name, size_t size)
{
	if (!size) { errno = EINVAL; return -EINVAL; }
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
