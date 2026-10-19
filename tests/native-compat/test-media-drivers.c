/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Checks the ImageReader-async, AMediaCrypto and AMediaDrm entry points in
 * src/libandroid/media.c against the published NDK contract.
 *
 * The header block below must come first: the AOSP NDK headers are written for
 * bionic, so the annotations glibc lacks (__INTRODUCED_IN, _Nullable, _Nonnull)
 * are spelled out here. ndk-header-check.sh downloads the real headers of AOSP
 * tag android-17.0.0_r1 (commit 94b4c163, the task's reference revision) and
 * compiles this file with them, so every call below uses the exact published
 * prototype instead of a hand-copied one:
 *   media/NdkImageReader.h  AImageReader_acquireNextImageAsync (API 26)
 *   media/NdkImage.h        AImage_deleteAsync               (API 26)
 *   media/NdkMediaCrypto.h  AMediaCrypto_new/_delete/_isCryptoSchemeSupported
 *   media/NdkMediaDrm.h     the AMediaDrm_* family
 * The assertions are the documented outcomes for a platform without a DRM
 * plugin and without an ImageReader producer, i.e. the codes AOSP itself
 * returns when the underlying object or plugin is absent; nothing here claims a
 * success ATL cannot deliver.
 */
#ifndef __INTRODUCED_IN
#define __INTRODUCED_IN(api_level) /* nothing */
#endif
#ifndef _Nullable
#define _Nullable
#endif
#ifndef _Nonnull
#define _Nonnull
#endif
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/types.h>

#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCrypto.h>
#include <media/NdkMediaDrm.h>
#include <media/NdkMediaError.h>

static int failures;
#define CHECK(cond)                                                            \
	do {                                                                   \
		if (!(cond)) {                                                 \
			printf("FAIL %d: %s\n", __LINE__, #cond);              \
			failures++;                                            \
		}                                                              \
	} while (0)

int main(void)
{
	/* --- AImageReader_acquireNextImageAsync / AImage_deleteAsync --- */
	AImage *image = (AImage *)0x1;
	int fence = 1234;
	CHECK(AImageReader_acquireNextImageAsync(NULL, &image, &fence) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	/* a failed acquire leaves no image and no fence to wait on */
	CHECK(image == NULL && fence == -1);
	image = (AImage *)0x1;
	CHECK(AImageReader_acquireNextImageAsync(NULL, &image, NULL) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AImageReader_acquireNextImageAsync(NULL, NULL, NULL) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	AImage_deleteAsync(NULL, -1); /* no image: no-op, no crash */
	AImage_deleteAsync(NULL, 7);  /* and the caller's fence is not consumed */

	/* --- AMediaCrypto: no plugin, so no scheme is supported --- */
	AMediaUUID uuid = {0x10, 0x77, 0xef, 0xec, 0xc0, 0xb2, 0x4d, 0x02,
			   0xac, 0xe3, 0x3c, 0x1e, 0x52, 0xe2, 0xfb, 0x4b};
	CHECK(AMediaCrypto_isCryptoSchemeSupported(uuid) == false);
	CHECK(AMediaCrypto_requiresSecureDecoderComponent("video/avc") == false);
	CHECK(AMediaCrypto_new(uuid, NULL, 0) == NULL);
	CHECK(AMediaCrypto_new(uuid, "init", 4) == NULL);
	AMediaCrypto_delete(NULL);

	/* --- AMediaDrm: no plugin, so no usable handle --- */
	AMediaDrm *drm = AMediaDrm_createByUUID(uuid);
	CHECK(drm == NULL);
	AMediaDrm_release(drm);

	AMediaDrmSessionId session = {NULL, 0};
	CHECK(AMediaDrm_openSession(NULL, &session) == AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_closeSession(NULL, &session) == AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_setOnEventListener(NULL, NULL) == AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_setOnExpirationUpdateListener(NULL, NULL) ==
	    AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_setOnKeysChangeListener(NULL, NULL) == AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_setPropertyString(NULL, "vendor", "x") ==
	    AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_setPropertyByteArray(NULL, "deviceUniqueId", NULL, 0) ==
	    AMEDIA_ERROR_INVALID_OBJECT);

	AMediaDrmScope scope = {(const uint8_t *)"1234", 4};
	const uint8_t *req = NULL;
	size_t reqSize = 0;
	const char *url = NULL;
	AMediaDrmKeyRequestType reqType = KEY_REQUEST_TYPE_INITIAL;
	CHECK(AMediaDrm_getKeyRequest(NULL, &scope, NULL, 0, "video/mp4",
		  KEY_TYPE_STREAMING, NULL, 0, &req, &reqSize) ==
	    AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_getKeyRequestWithDefaultUrlAndType(NULL, &scope, NULL, 0,
		  "video/mp4", KEY_TYPE_STREAMING, NULL, 0, &req, &reqSize, &url,
		  &reqType) == AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_getProvisionRequest(NULL, &req, &reqSize, &url) ==
	    AMEDIA_ERROR_INVALID_OBJECT);
	AMediaDrmKeySetId keySet = {NULL, 0};
	CHECK(AMediaDrm_provideKeyResponse(NULL, &scope, NULL, 0, &keySet) ==
	    AMEDIA_ERROR_INVALID_OBJECT);
	CHECK(AMediaDrm_provideProvisionResponse(NULL, NULL, 0) ==
	    AMEDIA_ERROR_INVALID_OBJECT);

	/*
	 * The handle ATL never hands out (AMediaDrm_createByUUID returns NULL).
	 * Every call below only tests the handle for null before answering, so a
	 * tagged pointer exercises the remaining documented branches - the ones
	 * AOSP reaches on a handle whose plugin is null - without a DRM plugin.
	 */
	AMediaDrm *foreign = (AMediaDrm *)(uintptr_t)0x1000;
	session.ptr = (const uint8_t *)"session";
	session.length = 7;
	CHECK(AMediaDrm_openSession(foreign, &session) == AMEDIA_ERROR_UNSUPPORTED);
	/* AOSP clears the session id when the plugin cannot open a session */
	CHECK(session.ptr == NULL && session.length == 0);
	CHECK(AMediaDrm_closeSession(foreign, &session) ==
	    AMEDIA_DRM_SESSION_NOT_OPENED);
	CHECK(AMediaDrm_getKeyRequest(foreign, &scope, NULL, 0, "video/mp4",
		  KEY_TYPE_STREAMING, NULL, 0, &req, &reqSize) ==
	    AMEDIA_DRM_SESSION_NOT_OPENED);
	CHECK(AMediaDrm_getKeyRequestWithDefaultUrlAndType(foreign, &scope, NULL, 0,
		  "video/mp4", KEY_TYPE_STREAMING, NULL, 0, &req, &reqSize, &url,
		  &reqType) == AMEDIA_DRM_SESSION_NOT_OPENED);
	CHECK(AMediaDrm_provideKeyResponse(foreign, &scope, (const uint8_t *)"r", 1,
		  &keySet) == AMEDIA_DRM_SESSION_NOT_OPENED);
	reqSize = 4096; /* AOSP requires the caller's buffer size in */
	CHECK(AMediaDrm_getProvisionRequest(foreign, &req, &reqSize, &url) ==
	    AMEDIA_ERROR_UNSUPPORTED);
	CHECK(AMediaDrm_provideProvisionResponse(foreign, (const uint8_t *)"r", 1) ==
	    AMEDIA_ERROR_UNSUPPORTED);
	CHECK(AMediaDrm_setPropertyString(foreign, "vendor", "x") ==
	    AMEDIA_ERROR_UNSUPPORTED);
	CHECK(AMediaDrm_setPropertyByteArray(foreign, "deviceUniqueId",
		  (const uint8_t *)"v", 1) == AMEDIA_ERROR_UNSUPPORTED);

	/* AOSP's own argument validation (NdkMediaDrm.cpp), in its order. */
	reqSize = 0;
	CHECK(AMediaDrm_getProvisionRequest(foreign, &req, &reqSize, &url) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AMediaDrm_getKeyRequest(foreign, &scope, NULL, 0, NULL,
		  KEY_TYPE_STREAMING, NULL, 0, &req, &reqSize) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AMediaDrm_provideKeyResponse(foreign, &scope, NULL, 0, &keySet) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AMediaDrm_provideProvisionResponse(foreign, NULL, 0) ==
	    AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AMediaDrm_openSession(foreign, NULL) == AMEDIA_ERROR_INVALID_PARAMETER);
	CHECK(AMediaDrm_closeSession(foreign, NULL) == AMEDIA_ERROR_INVALID_PARAMETER);

	printf("%s: %d check(s) failed\n", failures ? "FAIL" : "PASS", failures);
	return failures != 0;
}
