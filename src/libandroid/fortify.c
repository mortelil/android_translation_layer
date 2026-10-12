/* Bionic libc fortify wrappers that ATL's libc shim does not define.
 *
 * A library built with _FORTIFY_SOURCE>=2 imports __<func>_chk instead of the
 * plain function, and those wrappers are part of the platform libc ABI. The
 * bionic shim's libc (bionic_translation's libc_bio.so) implements most of the
 * family under the bionic_ prefix, and linkers resolve them through the shim's
 * first lookup step, dlsym(RTLD_DEFAULT, "bionic_" + sym) (linker/linker.c:1876,
 * apkenv_reloc_library). The wrappers below have no definition there, and two of
 * them have no host counterpart either (glibc has __stpcpy_chk but no __pwrite_chk
 * at all), so the relocation cannot be satisfied by any of the shim's three
 * lookups and the whole library load fails on it:
 *
 *   failed to link libgoogle3.so: cannot locate symbol "__pwrite_chk"
 *   (libcronet imports __pread64_chk and __pwrite64_chk the same way)
 *
 * libandroid.so is loaded into the shim's global scope, which is where step one
 * of that lookup looks, so exporting the missing wrappers from it is the same
 * convention that already serves ATL's EGL/GL hooks - no loader change, no
 * dependency-package change, and every app built with fortify gets them, not just
 * this one. The list is a family gap, not an app-specific hole: libgoogle3.so
 * imports 18 _chk symbols, 16 of which libc_bio.so already answers; if a future
 * app imports another member of the family that is missing there, it belongs in
 * this file rather than in a per-app workaround.
 *
 * Semantics follow bionic/libc/bionic/fortify.cpp (android-14.0.0_r1): a failing
 * check means the caller wrote out of bounds, so it aborts, exactly as bionic does.
 * The 64-bit variants (fortify.cpp:175, :187) are the same checks; on LP64, off64_t
 * is off_t and pread64/pwrite64 are pread/pwrite, so they share the bodies below.
 */

#define _GNU_SOURCE

#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

static void fortify_check_count(const char *function, const char *argument, size_t count)
{
	if (__builtin_expect(count > (size_t)SSIZE_MAX, 0)) {
		fprintf(stderr, "%s: %s > SSIZE_MAX: %zu\n", function, argument, count);
		abort();
	}
}

static void fortify_check_buffer_access(const char *function, const char *direction, size_t size,
                                        size_t buffer_size)
{
	if (__builtin_expect(size > buffer_size, 0)) {
		fprintf(stderr, "%s: prevented %s %zu bytes past the end of a %zu byte buffer\n",
			function, direction, size - buffer_size, buffer_size);
		abort();
	}
}

ssize_t bionic___pwrite_chk(int fd, const void *buf, size_t count, off_t offset, size_t buf_size)
{
	fortify_check_count("pwrite", "count", count);
	fortify_check_buffer_access("pwrite", "read from", count, buf_size);
	return pwrite(fd, buf, count, offset);
}

ssize_t bionic___pwrite64_chk(int fd, const void *buf, size_t count, off_t offset, size_t buf_size)
{
	fortify_check_count("pwrite64", "count", count);
	fortify_check_buffer_access("pwrite64", "read from", count, buf_size);
	return pwrite(fd, buf, count, offset);
}

ssize_t bionic___pread64_chk(int fd, void *buf, size_t count, off_t offset, size_t buf_size)
{
	fortify_check_count("pread64", "count", count);
	fortify_check_buffer_access("pread64", "write into", count, buf_size);
	return pread(fd, buf, count, offset);
}

char *bionic___stpcpy_chk(char *dst, const char *src, size_t dst_len)
{
	size_t src_len = strlen(src) + 1; // the terminating NUL is written too
	fortify_check_buffer_access("stpcpy", "write into", src_len, dst_len);
	return stpcpy(dst, src);
}
