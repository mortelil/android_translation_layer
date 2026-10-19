// SPDX-License-Identifier: GPL-3.0-only
/*
 * APerformanceHint (ADPF), the device performance hint API. libxul.so references
 * APerformanceHint_getManager, _createSession, _closeSession,
 * _updateTargetWorkDuration and _reportActualWorkDuration as weak symbols, which
 * the bionic linker leaves at zero; without these definitions a call would jump
 * to address 0.
 *
 * Prototypes and return values are the frozen platform header
 * (env/art_standalone/libandroidfw/include/android/performance_hint.h:91-145,
 * tag android-17.0.0_r1 commit 94b4c163): getManager returns "manager instance
 * on success, nullptr on failure", createSession "nullptr on failure",
 * updateTargetWorkDuration/reportActualWorkDuration return 0 on success, EINVAL
 * for a non-positive duration and EPIPE "if communication with the system
 * service has failed".
 *
 * AOSP obtains the manager from the system's performance_hint binder service and
 * returns nullptr when the service is not there or refuses the client
 * (frameworks/base/native/android/performance_hint.cpp:341-349,
 * APerformanceHintManager::create: "PerformanceHint service is not ready").
 * ATL runs no such service and has no equivalent scheduling backend, so no
 * manager and no session can ever exist: getManager and createSession report
 * exactly that failure, and the session calls answer the documented EPIPE for a
 * service that cannot be reached instead of pretending a hint was applied. No
 * session object is fabricated, and the session pointer is never dereferenced
 * because ATL never hands one out.
 */
#include <errno.h>
#include <stdint.h>
#include <stddef.h>

struct APerformanceHintManager;
struct APerformanceHintSession;
typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

APerformanceHintManager *APerformanceHint_getManager(void) {
	return NULL;
}

APerformanceHintSession *APerformanceHint_createSession(APerformanceHintManager *manager,
		const int32_t *threadIds, size_t size, int64_t initialTargetWorkDurationNanos) {
	(void)manager;
	(void)threadIds;
	(void)size;
	(void)initialTargetWorkDurationNanos;
	return NULL;
}

int APerformanceHint_updateTargetWorkDuration(APerformanceHintSession *session,
		int64_t targetDurationNanos) {
	(void)session;
	if (targetDurationNanos <= 0) return EINVAL;
	return EPIPE;
}

int APerformanceHint_reportActualWorkDuration(APerformanceHintSession *session,
		int64_t actualDurationNanos) {
	(void)session;
	if (actualDurationNanos <= 0) return EINVAL;
	return EPIPE;
}

void APerformanceHint_closeSession(APerformanceHintSession *session) {
	/* No session can have been created, so there is nothing to release. AOSP
	 * aborts on a null session here (LOG_ALWAYS_FATAL_IF, performance_hint.cpp:1119);
	 * taking the whole process down for an impossible argument would be worse than
	 * leaving it alone. */
	(void)session;
}
