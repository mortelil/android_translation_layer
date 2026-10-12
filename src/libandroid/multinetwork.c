// SPDX-License-Identifier: GPL-3.0-only
/*
 * NDK <android/multinetwork.h> (libandroid).
 *
 * net_handle_t values are resolved by netd, which ATL does not run, so a socket
 * cannot be bound to the requested network. The NDK contract is a 0/-1
 * return with errno set, so the failure is reported as ENOSYS rather than pretending the
 * socket was bound to a network that does not exist.
 */
#include <errno.h>
#include <stdint.h>

typedef uint64_t net_handle_t;

int android_setsocknetwork(net_handle_t network, int fd)
{
	(void)network;
	errno = fd < 0 ? EBADF : ENOSYS;
	return -1;
}
