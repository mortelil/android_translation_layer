/* SPDX-License-Identifier: GPL-3.0-only
 * Private ATL-to-ATL transport. This is not the Linux Binder wire protocol.
 */
#define _GNU_SOURCE
#include "generated_headers/android_os_ATLBinderConnection.h"
#include <errno.h>
#include <fcntl.h>
#include <glib.h>
#include <stdio.h>
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
JNIEXPORT jintArray JNICALL Java_android_os_ATLBinderConnection_spawn(JNIEnv *env, jclass cls, jstring apk, jstring process, jint sdk, jstring parentPackage, jstring parentProcess)
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
	char data[LIMIT];
	union {
		struct cmsghdr align;
		char bytes[CMSG_SPACE(MAX_FDS * sizeof(int))];
	} control;
	struct iovec iov = {data, sizeof(data)};
	struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
	ssize_t n;
	do {
		n = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC);
	} while (n < 0 && errno == EINTR);
	if (n <= 0) {
		fail(env, "ATL IPC peer disconnected");
		return NULL;
	}
	int fds[MAX_FDS + 1] = {0};
	for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
		if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
			int count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
			memcpy(fds + 1 + fds[0], CMSG_DATA(c), count * sizeof(int));
			fds[0] += count;
		}
	}
	if (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) {
		for (int i = 1; i <= fds[0]; ++i)
			close(fds[i]);
		fail(env, "Truncated ATL IPC message");
		return NULL;
	}
	(*env)->SetIntArrayRegion(env, descriptors, 0, fds[0] + 1, fds);
	jbyteArray result = (*env)->NewByteArray(env, n);
	(*env)->SetByteArrayRegion(env, result, 0, n, (jbyte *)data);
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

JNIEXPORT void JNICALL Java_android_os_ATLBinderConnection_prepare(JNIEnv *env, jclass cls, jint fd)
{
	if (fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) {
		fail(env, strerror(errno));
		return;
	}
	struct timeval limit = {.tv_sec = 30};
	if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit)))
		fail(env, strerror(errno));
}
