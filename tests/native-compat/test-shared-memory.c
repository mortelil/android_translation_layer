// SPDX-License-Identifier: GPL-3.0-only
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

int ASharedMemory_create(const char *, size_t);
size_t ASharedMemory_getSize(int);

int main(void)
{
	assert(ASharedMemory_create(NULL, 0) == -EINVAL && errno == EINVAL);
	assert(ASharedMemory_create(NULL, SIZE_MAX) < 0);
	int fd = ASharedMemory_create("atl-test", 4096);
	assert(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
	assert(ASharedMemory_getSize(fd) == 4096);
	char *data = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	assert(data != MAP_FAILED && data[0] == 0 && data[4095] == 0);
	strcpy(data, "parent");
	int copy = dup(fd);
	assert(copy >= 0 && ASharedMemory_getSize(copy) == 4096);
	close(fd);
	assert(ftruncate(copy, 2048) == -1 && errno == EPERM);
	assert(ftruncate(copy, 8192) == -1 && errno == EPERM);
	pid_t child = fork();
	assert(child >= 0);
	if (!child) {
		char *other = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, copy, 0);
		assert(other != MAP_FAILED && strcmp(other, "parent") == 0);
		strcpy(other, "child");
		munmap(other, 4096);
		_exit(0);
	}
	int status;
	assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
	assert(strcmp(data, "child") == 0);
	close(copy);
	assert(strcmp(data, "child") == 0); // Mapping outlives the final descriptor.
	munmap(data, 4096);
	assert(ASharedMemory_getSize(copy) == 0);
	int unrelated = open("/dev/null", O_RDONLY);
	assert(unrelated >= 0 && ASharedMemory_getSize(unrelated) == 0);
	close(unrelated);
	puts("PASS: shared-memory size, zero initialization, dup/close lifetime, cross-process contents and resize seals");
}
