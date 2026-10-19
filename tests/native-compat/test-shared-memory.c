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
int ASharedMemory_setProt(int, int);

int main(void)
{
	assert(ASharedMemory_create(NULL, 0) == -1 && errno == EINVAL);
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

	/* Protection mask: removal only, region wide, existing mappings untouched. */
	int region = ASharedMemory_create("atl-test-prot", 4096);
	assert(region >= 0 && ASharedMemory_getSize(region) == 4096);
	char *live = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, region, 0);
	assert(live != MAP_FAILED);
	assert(ASharedMemory_setProt(region, PROT_READ | PROT_WRITE | PROT_EXEC) == 0);
	assert(ASharedMemory_setProt(region, 0x8) == -1 && errno == EINVAL);
	assert(ASharedMemory_setProt(region, PROT_NONE) == -1 && errno == ENOTSUP);
	assert(ASharedMemory_setProt(unrelated, PROT_READ) < 0);
	assert(ASharedMemory_setProt(region, PROT_READ) == -1 && errno == ENOTSUP);
	assert(ASharedMemory_setProt(region, PROT_READ | PROT_EXEC) == 0);
	assert(ASharedMemory_setProt(region, PROT_READ | PROT_WRITE | PROT_EXEC) == -1 && errno == EPERM);
	char *ro = mmap(NULL, 4096, PROT_READ, MAP_SHARED, region, 0);
	assert(ro != MAP_FAILED);
	char *denied = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, region, 0);
	assert(denied == MAP_FAILED && errno == EPERM);
	live[0] = 'x'; /* Existing mapping keeps the protection it was created with. */
	strcpy(live, "still-writable");
	assert(strcmp(ro, "still-writable") == 0);
	assert(ASharedMemory_setProt(region, PROT_READ | PROT_EXEC) == 0);
	char *denied_exec = mmap(NULL, 4096, PROT_READ | PROT_EXEC, MAP_SHARED, region, 0);
	assert(denied_exec != MAP_FAILED);
	munmap(denied_exec, 4096);
	munmap(ro, 4096);
	munmap(live, 4096);
	close(region);
	puts("PASS: shared-memory protection mask narrows only, is region-wide and leaves live mappings untouched");
	puts("PASS: shared-memory size, zero initialization, dup/close lifetime, cross-process contents and resize seals");
}
