/* bionic's malloc tuning entry point, which the host allocator cannot provide.
 *
 * libgoogle3.so imports mallopt@LIBC_O. It is a bionic libc symbol, so the shim's
 * plain dlsym lookup has to find it in the process scope: the host libc here is
 * musl (the ATL runtime container is Alpine), which has no mallopt at all - the
 * only implementations in the image are libscudo.so and libhwasan, neither of
 * which a normal process loads. The shim's first lookup step,
 * dlsym(RTLD_DEFAULT, "bionic_" + sym) (linker/linker.c:1876, apkenv_reloc_library),
 * is therefore the only way to answer it.
 *
 * bionic implements mallopt in libc/bionic/malloc_common.cpp: it handles
 * M_BIONIC_SET_HEAP_TAGGING_LEVEL and M_BIONIC_ZERO_INIT itself and hands every
 * other parameter to the allocator's dispatch table. ATL has neither: its
 * allocator is the host's, exposing no such knobs, and there is no allocator
 * switching layer. So no request can be applied, and each one is answered with
 * mallopt's failure result rather than with a success that would tell the caller
 * a tunable is in effect when it is not (M_PURGE/M_PURGE_ALL would silently not
 * free anything, heap tagging cannot be enabled on this allocator, zero-init
 * cannot be recorded). Callers that check the result, as bionic's own do, can
 * fall back; M_BIONIC_SET_HEAP_TAGGING_LEVEL has no valid value on x86-64 either
 * way.
 */

int bionic_mallopt(int param, int value)
{
	(void)param;
	(void)value;
	return 0; // nothing was applied
}
