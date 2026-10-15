/* SPDX-License-Identifier: GPL-3.0-only
 * Private ATL-to-ATL transport. This is not the Linux Binder wire protocol.
 *
 * Adapted from PR #31 feat/remote-services baa9b489005995efed6d18d199d22683483a5258
 * (src/api-impl-jni/android_os_ATLBinderConnection.c) with the Gate-1 fixes of
 * LIFECYCLE-FOUNDATION/architecture.md §2-3:
 *   W1 the peer's identity is read from the channel with SO_PEERCRED
 *      (prepare returns {pid, uid, gid}; peerCredentials re-queries on demand);
 *   W4 the receive buffer lives on the heap and the descriptor accumulation
 *      loop is bounded before memcpy, so a hostile or misbehaving peer can
 *      never overflow the 64-descriptor table (excess descriptors are closed
 *      and the record is rejected).
 * socketPair is the channel factory for this transport's SOCK_SEQPACKET pairs
 * (spawn and test/host channel setup share it). Original GPL-3.0-only ATL code.
 *
 * spawn: the two spawn entry points (the transport spawn declared here and the
 * service-host spawn declared on android.atl.ATLProcessHost) share one non-static
 * C body, atl_binder_spawn() in atl_binder_spawn.h, so their child environment
 * cannot diverge; only the service-host spawn passes service_child = TRUE.
 */
#define _GNU_SOURCE
#include "generated_headers/android_os_ATLBinderConnection.h"
#include "atl_binder_spawn.h"
#include <errno.h>
#include <fcntl.h>
#include <glib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
#define LIMIT   (128 * 1024)
#define MAX_FDS 64
static void fail(JNIEnv *env, const char *text)
{
	(*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), text);
}
/* W1: SO_PEERCRED of a socketpair records the process that created the pair
 * (verified on Linux 6.8), so a pair created by this process reports our own
 * pid on both ends and the Java side falls back to a /proc cross-check. */
static int credentials(jint fd, jint *out)
{
	struct ucred cred;
	socklen_t len = sizeof(cred);
	if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0)
		return -1;
	out[0] = cred.pid;
	out[1] = cred.uid;
	out[2] = cred.gid;
	return 0;
}
static jintArray credential_array(JNIEnv *env, jint fd)
{
	jint values[3];
	if (credentials(fd, values) < 0) {
		fail(env, strerror(errno));
		return NULL;
	}
	jintArray result = (*env)->NewIntArray(env, 3);
	(*env)->SetIntArrayRegion(env, result, 0, 3, values);
	return result;
}
JNIEXPORT jintArray JNICALL Java_android_os_ATLBinderConnection_spawn(JNIEnv *env, jclass cls, jstring apk, jstring process, jint sdk, jstring parentPackage, jstring parentProcess)
{
	(void)cls;
	return atl_binder_spawn(env, apk, process, sdk, parentPackage, parentProcess, FALSE);
}

/* Shared body of the two spawn entry points (atl_binder_spawn.h): the ordinary
 * transport spawn (service_child = FALSE) and the service spawn of the
 * loader-side process host (service_child = TRUE). Only the service spawn adds
 * ATL_SERVICE_HOST to the per-call child environment; this process' own
 * environment is never mutated, so a plain transport spawn from the same
 * process cannot pick up the marker. */
jintArray atl_binder_spawn(JNIEnv *env, jstring apk, jstring process, jint sdk,
                           jstring parentPackage, jstring parentProcess, gboolean service_child)
{
	int pair[2];
	if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair)) {
		fail(env, strerror(errno));
		return NULL;
	}
	const char *a = (*env)->GetStringUTFChars(env, apk, NULL);
	const char *p = (*env)->GetStringUTFChars(env, process, NULL);
	char sdktext[24], parenttext[24];
	snprintf(parenttext, sizeof(parenttext), "%d", getpid());
	snprintf(sdktext, sizeof(sdktext), "%d", sdk);
	const char *parent_package = (*env)->GetStringUTFChars(env, parentPackage, NULL);
	const char *parent_process = (*env)->GetStringUTFChars(env, parentProcess, NULL);
	char **vars = g_get_environ();
	vars = g_environ_setenv(vars, "ATL_SERVICE_PARENT_PACKAGE", parent_package, TRUE);
	vars = g_environ_setenv(vars, "ATL_SERVICE_PARENT_PROCESS", parent_process, TRUE);
	(*env)->ReleaseStringUTFChars(env, parentPackage, parent_package);
	(*env)->ReleaseStringUTFChars(env, parentProcess, parent_process);
	vars = g_environ_setenv(vars, "ATL_SERVICE_FD", "3", TRUE);
	vars = g_environ_setenv(vars, "ATL_SERVICE_PARENT_PID", parenttext, TRUE);
	vars = g_environ_setenv(vars, "ATL_SERVICE_PROCESS", p, TRUE);
	if (service_child)
		vars = g_environ_setenv(vars, "ATL_SERVICE_HOST", "1", TRUE);
	const char *argv[] = {"/proc/self/exe", a, "--sdk-int", sdktext, NULL};
	int target = 3;
	GPid pid;
	GError *error = NULL;
	/* GLib closes all unrelated descriptors before exec, including descriptors
     * opened by application/native code without CLOEXEC. Only IPC and stdout/
     * stderr are inherited. No Java or application code runs after fork. */
	gboolean started = g_spawn_async_with_pipes_and_fds(NULL, argv,
	                                                    (const char *const *)vars, G_SPAWN_DO_NOT_REAP_CHILD, NULL, NULL,
	                                                    -1, -1, -1, &pair[1], &target, 1, &pid, NULL, NULL, NULL, &error);
	g_strfreev(vars);
	close(pair[1]);
	if (!started) {
		close(pair[0]);
		fail(env, error->message);
		g_error_free(error);
		goto release;
	}
	jint values[] = {pair[0], pid};
	jintArray result = (*env)->NewIntArray(env, 2);
	(*env)->SetIntArrayRegion(env, result, 0, 2, values);
	(*env)->ReleaseStringUTFChars(env, apk, a);
	(*env)->ReleaseStringUTFChars(env, process, p);
	return result;
release:
	(*env)->ReleaseStringUTFChars(env, apk, a);
	(*env)->ReleaseStringUTFChars(env, process, p);
	return NULL;
}
JNIEXPORT jintArray JNICALL Java_android_os_ATLBinderConnection_socketPair(JNIEnv *env, jclass cls)
{
	int pair[2];
	if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair)) {
		fail(env, strerror(errno));
		return NULL;
	}
	jint values[] = {pair[0], pair[1]};
	jintArray result = (*env)->NewIntArray(env, 2);
	(*env)->SetIntArrayRegion(env, result, 0, 2, values);
	return result;
}
JNIEXPORT jintArray JNICALL Java_android_os_ATLBinderConnection_peerCredentials(JNIEnv *env, jclass cls, jint fd)
{
	return credential_array(env, fd);
}
JNIEXPORT void JNICALL Java_android_os_ATLBinderConnection_send(JNIEnv *env, jclass cls, jint fd, jbyteArray bytes, jintArray descriptors)
{
	jsize n = (*env)->GetArrayLength(env, bytes), count = (*env)->GetArrayLength(env, descriptors);
	if (n > LIMIT || count > MAX_FDS) {
		fail(env, "ATL IPC message exceeds transport limit");
		return;
	}
	jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
	int fds[MAX_FDS];
	(*env)->GetIntArrayRegion(env, descriptors, 0, count, fds);
	union {
		struct cmsghdr align;
		char bytes[CMSG_SPACE(MAX_FDS * sizeof(int))];
	} control;
	struct iovec iov = {data, n};
	struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
	if (count) {
		memset(&control, 0, sizeof(control));
		msg.msg_control = control.bytes;
		msg.msg_controllen = CMSG_SPACE(count * sizeof(int));
		struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
		c->cmsg_level = SOL_SOCKET;
		c->cmsg_type = SCM_RIGHTS;
		c->cmsg_len = CMSG_LEN(count * sizeof(int));
		memcpy(CMSG_DATA(c), fds, count * sizeof(int));
	}
	ssize_t sent;
	do {
		sent = sendmsg(fd, &msg, MSG_NOSIGNAL);
	} while (sent < 0 && errno == EINTR);
	(*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
	if (sent != n)
		fail(env, sent < 0 ? strerror(errno) : "Short IPC write");
}
JNIEXPORT jbyteArray JNICALL Java_android_os_ATLBinderConnection_receive(JNIEnv *env, jclass cls, jint fd, jintArray descriptors)
{
	/* W4: the record lands in a heap buffer; a 128 KiB stack frame is never
	 * built, and the descriptor table is bounded before anything is copied. */
	char *data = malloc(LIMIT);
	if (!data) {
		fail(env, "Out of memory receiving ATL IPC");
		return NULL;
	}
	union {
		struct cmsghdr align;
		char bytes[CMSG_SPACE(MAX_FDS * sizeof(int))];
	} control;
	struct iovec iov = {data, LIMIT};
	struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
	ssize_t n;
	do {
		n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
	} while (n < 0 && errno == EINTR);
	if (n <= 0) {
		free(data);
		fail(env, "ATL IPC peer disconnected");
		return NULL;
	}
	int fds[MAX_FDS + 1] = {0};
	for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
		if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
			int count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
			if (count < 0 || fds[0] + count > MAX_FDS) {
				/* W4: reject before memcpy and close everything collected so
				 * far, so a hostile peer can neither overflow the table nor
				 * leak the descriptors it managed to smuggle in. */
				for (int i = 1; i <= fds[0]; ++i)
					close(fds[i]);
				free(data);
				fail(env, "Too many received ATL IPC descriptors");
				return NULL;
			}
			memcpy(fds + 1 + fds[0], CMSG_DATA(c), count * sizeof(int));
			fds[0] += count;
		}
	}
	if (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) {
		for (int i = 1; i <= fds[0]; ++i)
			close(fds[i]);
		free(data);
		fail(env, "Truncated ATL IPC message");
		return NULL;
	}
	jbyteArray result = (*env)->NewByteArray(env, n);
	if (result)
		(*env)->SetByteArrayRegion(env, result, 0, n, (jbyte *)data);
	free(data);
	if (result)
		(*env)->SetIntArrayRegion(env, descriptors, 0, fds[0] + 1, fds);
	return result;
}
JNIEXPORT void JNICALL Java_android_os_ATLBinderConnection_shutdown(JNIEnv *env, jclass cls, jint fd) { shutdown(fd, SHUT_RDWR); }
JNIEXPORT void JNICALL Java_android_os_ATLBinderConnection_closeFd(JNIEnv *env, jclass cls, jint fd) { close(fd); }
JNIEXPORT jint JNICALL Java_android_os_ATLBinderConnection_dupFd(JNIEnv *env, jclass cls, jint fd)
{
	int result = fcntl(fd, F_DUPFD_CLOEXEC, 0);
	if (result < 0)
		fail(env, strerror(errno));
	return result;
}
JNIEXPORT void JNICALL Java_android_os_ATLBinderConnection_reap(JNIEnv *env, jclass cls, jint pid)
{
	while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
	}
	g_spawn_close_pid(pid);
}
JNIEXPORT jintArray JNICALL Java_android_os_ATLBinderConnection_prepare(JNIEnv *env, jclass cls, jint fd)
{
	if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) {
		fail(env, strerror(errno));
		return NULL;
	}
	struct timeval limit = {.tv_sec = 30};
	if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit)) < 0) {
		fail(env, strerror(errno));
		return NULL;
	}
	/* W1: the caller gets the channel's own view of the peer. */
	return credential_array(env, fd);
}
