
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This file defines an NDK API.
 * Do not remove methods.
 * Do not change method signatures.
 * Do not change the value of constants.
 * Do not change the size of any of the classes defined in here.
 * Do not reference types that are not part of the NDK.
 * Do not #include files that aren't part of the NDK.
 */

typedef enum {
	AMEDIA_OK = 0,

	AMEDIA_ERROR_BASE = -10000,
	AMEDIA_ERROR_UNKNOWN = AMEDIA_ERROR_BASE,
	AMEDIA_ERROR_MALFORMED = AMEDIA_ERROR_BASE - 1,
	AMEDIA_ERROR_UNSUPPORTED = AMEDIA_ERROR_BASE - 2,
	AMEDIA_ERROR_INVALID_OBJECT = AMEDIA_ERROR_BASE - 3,
	AMEDIA_ERROR_INVALID_PARAMETER = AMEDIA_ERROR_BASE - 4,
	AMEDIA_ERROR_INVALID_OPERATION = AMEDIA_ERROR_BASE - 5,

	AMEDIA_DRM_ERROR_BASE = -20000,
	AMEDIA_DRM_NOT_PROVISIONED = AMEDIA_DRM_ERROR_BASE - 1,
	AMEDIA_DRM_RESOURCE_BUSY = AMEDIA_DRM_ERROR_BASE - 2,
	AMEDIA_DRM_DEVICE_REVOKED = AMEDIA_DRM_ERROR_BASE - 3,
	AMEDIA_DRM_SHORT_BUFFER = AMEDIA_DRM_ERROR_BASE - 4,
	AMEDIA_DRM_SESSION_NOT_OPENED = AMEDIA_DRM_ERROR_BASE - 5,
	AMEDIA_DRM_TAMPER_DETECTED = AMEDIA_DRM_ERROR_BASE - 6,
	AMEDIA_DRM_VERIFY_FAILED = AMEDIA_DRM_ERROR_BASE - 7,
	AMEDIA_DRM_NEED_KEY = AMEDIA_DRM_ERROR_BASE - 8,
	AMEDIA_DRM_LICENSE_EXPIRED = AMEDIA_DRM_ERROR_BASE - 9,

	AMEDIA_IMGREADER_ERROR_BASE = -30000,
	AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE = AMEDIA_IMGREADER_ERROR_BASE - 1,
	AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED = AMEDIA_IMGREADER_ERROR_BASE - 2,
	AMEDIA_IMGREADER_CANNOT_LOCK_IMAGE = AMEDIA_IMGREADER_ERROR_BASE - 3,
	AMEDIA_IMGREADER_CANNOT_UNLOCK_IMAGE = AMEDIA_IMGREADER_ERROR_BASE - 4,
	AMEDIA_IMGREADER_IMAGE_NOT_LOCKED = AMEDIA_IMGREADER_ERROR_BASE - 5,
} media_status_t;

struct AMediaFormat;
typedef struct AMediaFormat AMediaFormat;

AMediaFormat *AMediaFormat_new()
{
	return NULL;
}

media_status_t AMediaFormat_delete(AMediaFormat *)
{
	return AMEDIA_ERROR_UNKNOWN;
}

/**
 * Human readable representation of the format. The returned string is owned by the format,
 * and remains valid until the next call to toString, or until the format is deleted.
 */
const char *AMediaFormat_toString(AMediaFormat *)
{
	return NULL;
}

bool AMediaFormat_getInt32(AMediaFormat *, const char *name, int32_t *out)
{
	return false;
}

bool AMediaFormat_getInt64(AMediaFormat *, const char *name, int64_t *out)
{
	return false;
}

bool AMediaFormat_getFloat(AMediaFormat *, const char *name, float *out)
{
	return false;
}

/**
 * The returned data is owned by the format and remains valid as long as the named entry
 * is part of the format.
 */
bool AMediaFormat_getBuffer(AMediaFormat *, const char *name, void **data, size_t *size)
{
	return false;
}

/**
 * The returned string is owned by the format, and remains valid until the next call to getString,
 * or until the format is deleted.
 */
bool AMediaFormat_getString(AMediaFormat *, const char *name, const char **out)
{
	return false;
}

void AMediaFormat_setInt32(AMediaFormat *, const char *name, int32_t value) {}
void AMediaFormat_setInt64(AMediaFormat *, const char *name, int64_t value) {}
void AMediaFormat_setFloat(AMediaFormat *, const char *name, float value) {}
/**
 * The provided string is copied into the format.
 */
void AMediaFormat_setString(AMediaFormat *, const char *name, const char *value) {}
/**
 * The provided data is copied into the format.
 */
void AMediaFormat_setBuffer(AMediaFormat *, const char *name, void *data, size_t size) {}

/**
 * XXX should these be ints/enums that we look up in a table as needed?
 */
const char *AMEDIAFORMAT_KEY_AAC_PROFILE = "aac-profile";
const char *AMEDIAFORMAT_KEY_BIT_RATE = "bit-rate";
const char *AMEDIAFORMAT_KEY_CHANNEL_COUNT = "channel-count";
const char *AMEDIAFORMAT_KEY_CHANNEL_MASK = "channel-mask";
const char *AMEDIAFORMAT_KEY_COLOR_FORMAT = "color-format";
const char *AMEDIAFORMAT_KEY_DURATION = "duration";
const char *AMEDIAFORMAT_KEY_FLAC_COMPRESSION_LEVEL = "flac-compression-level";
const char *AMEDIAFORMAT_KEY_FRAME_RATE = "frame-rate";
const char *AMEDIAFORMAT_KEY_HEIGHT = "height";
const char *AMEDIAFORMAT_KEY_IS_ADTS = "is-adts";
const char *AMEDIAFORMAT_KEY_IS_AUTOSELECT = "is-autoselect";
const char *AMEDIAFORMAT_KEY_IS_DEFAULT = "is-default";
const char *AMEDIAFORMAT_KEY_IS_FORCED_SUBTITLE = "is-forced-subtitle";
const char *AMEDIAFORMAT_KEY_I_FRAME_INTERVAL = "i-frame-interval";
const char *AMEDIAFORMAT_KEY_LANGUAGE = "language";
const char *AMEDIAFORMAT_KEY_MAX_HEIGHT = "max-height";
const char *AMEDIAFORMAT_KEY_MAX_INPUT_SIZE = "max-input-size";
const char *AMEDIAFORMAT_KEY_MAX_WIDTH = "max-width";
const char *AMEDIAFORMAT_KEY_MIME = "mime";
const char *AMEDIAFORMAT_KEY_PUSH_BLANK_BUFFERS_ON_STOP = "push-blank-buffers-on-stop";
const char *AMEDIAFORMAT_KEY_SAMPLE_RATE = "sample-rate";
const char *AMEDIAFORMAT_KEY_WIDTH = "width";
const char *AMEDIAFORMAT_KEY_STRIDE = "stride";
const char *AMEDIAFORMAT_KEY_REPEAT_PREVIOUS_FRAME_AFTER = "repeat-previous-frame-after";

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
{
	return -1;
}
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This file defines an NDK API.
 * Do not remove methods.
 * Do not change method signatures.
 * Do not change the value of constants.
 * Do not change the size of any of the classes defined in here.
 * Do not reference types that are not part of the NDK.
 * Do not #include files that aren't part of the NDK.
 */

struct ANativeWindow;

struct AMediaCodec;
typedef struct AMediaCodec AMediaCodec;

struct AMediaCodecBufferInfo {
	int32_t offset;
	int32_t size;
	int64_t presentationTimeUs;
	uint32_t flags;
};
typedef struct AMediaCodecBufferInfo AMediaCodecBufferInfo;
typedef struct AMediaCodecCryptoInfo AMediaCodecCryptoInfo;

enum {
	AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM = 4,
	AMEDIACODEC_CONFIGURE_FLAG_ENCODE = 1,
	AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED = -3,
	AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED = -2,
	AMEDIACODEC_INFO_TRY_AGAIN_LATER = -1
};

/**
 * Create codec by name. Use this if you know the exact codec you want to use.
 * When configuring, you will need to specify whether to use the codec as an
 * encoder or decoder.
 */
AMediaCodec *AMediaCodec_createCodecByName(const char *name)
{
	return 0;
}

/**
 * Create codec by mime type. Most applications will use this, specifying a
 * mime type obtained from media extractor.
 */
AMediaCodec *AMediaCodec_createDecoderByType(const char *mime_type)
{
	return 0;
}

/**
 * Create encoder by name.
 */
AMediaCodec *AMediaCodec_createEncoderByType(const char *mime_type)
{
	return -0;
}

/**
 * delete the codec and free its resources
 */
media_status_t AMediaCodec_delete(AMediaCodec *)
{
	return -1;
}

typedef void AMediaCrypto;
typedef void ANativeWindow;

/**
 * Configure the codec. For decoding you would typically get the format from an extractor.
 */
media_status_t AMediaCodec_configure(
    AMediaCodec *,
    const AMediaFormat *format,
    ANativeWindow *surface,
    AMediaCrypto *crypto,
    uint32_t flags)
{
	return -1;
}

/**
 * Start the codec. A codec must be configured before it can be started, and must be started
 * before buffers can be sent to it.
 */
media_status_t AMediaCodec_start(AMediaCodec *)
{
	return -1;
}

/**
 * Stop the codec.
 */
media_status_t AMediaCodec_stop(AMediaCodec *)
{
	return -1;
}

/*
 * Flush the codec's input and output. All indices previously returned from calls to
 * AMediaCodec_dequeueInputBuffer and AMediaCodec_dequeueOutputBuffer become invalid.
 */
media_status_t AMediaCodec_flush(AMediaCodec *)
{
	return -1;
}

/**
 * Get an input buffer. The specified buffer index must have been previously obtained from
 * dequeueInputBuffer, and not yet queued.
 */
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *x, size_t idx, size_t *out_size)
{
	return -0;
}

/**
 * Get an output buffer. The specified buffer index must have been previously obtained from
 * dequeueOutputBuffer, and not yet queued.
 */
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *, size_t idx, size_t *out_size)
{
	return -0;
}

/**
 * Get the index of the next available input buffer. An app will typically use this with
 * getInputBuffer() to get a pointer to the buffer, then copy the data to be encoded or decoded
 * into the buffer before passing it to the codec.
 */
ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec *x, int64_t timeoutUs)
{
	return -1;
}

/**
 * Send the specified buffer to the codec for processing.
 */
media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *,
                                            size_t idx, off_t offset, size_t size, uint64_t time, uint32_t flags)
{
	return -1;
}

/**
 * Send the specified buffer to the codec for processing.
 */
media_status_t AMediaCodec_queueSecureInputBuffer(AMediaCodec *,
                                                  size_t idx, off_t offset, AMediaCodecCryptoInfo *, uint64_t time, uint32_t flags)
{
	return -1;
}

/**
 * Get the index of the next available buffer of processed data.
 */
ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec *, AMediaCodecBufferInfo *info,
                                        int64_t timeoutUs)
{
	return -1;
}
AMediaFormat *AMediaCodec_getOutputFormat(AMediaCodec *)
{
	return -0;
}

/**
 * If you are done with a buffer, use this call to return the buffer to
 * the codec. If you previously specified a surface when configuring this
 * video decoder you can optionally render the buffer.
 */
media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *, size_t idx, bool render)
{
	return -1;
}

/**
 * Dynamically sets the output surface of a codec.
 *
 *  This can only be used if the codec was configured with an output surface.  The
 *  new output surface should have a compatible usage type to the original output surface.
 *  E.g. codecs may not support switching from a SurfaceTexture (GPU readable) output
 *  to ImageReader (software readable) output.
 *
 * For more details, see the Java documentation for MediaCodec.setOutputSurface.
 */
media_status_t AMediaCodec_setOutputSurface(AMediaCodec *, ANativeWindow *surface)
{
	return -1;
}

/**
 * If you are done with a buffer, use this call to update its surface timestamp
 * and return it to the codec to render it on the output surface. If you
 * have not specified an output surface when configuring this video codec,
 * this call will simply return the buffer to the codec.
 *
 * For more details, see the Java documentation for MediaCodec.releaseOutputBuffer.
 */
media_status_t AMediaCodec_releaseOutputBufferAtTime(
    AMediaCodec *mData, size_t idx, int64_t timestampNs)
{
	return -1;
}

/**
 * Creates a Surface that can be used as the input to encoder, in place of input buffers
 *
 * This can only be called after the codec has been configured via
 * AMediaCodec_configure(..)
{
	return -1;
} and before AMediaCodec_start() has been called.
 *
 * The application is responsible for releasing the surface by calling
 * ANativeWindow_release() when done.
 *
 * For more details, see the Java documentation for MediaCodec.createInputSurface.
 */
media_status_t AMediaCodec_createInputSurface(
    AMediaCodec *mData, ANativeWindow **surface)
{
	return -1;
}

/**
 * Creates a persistent Surface that can be used as the input to encoder
 *
 * Persistent surface can be reused by MediaCodec instances and can be set
 * on a new instance via AMediaCodec_setInputSurface().
 * A persistent surface can be connected to at most one instance of MediaCodec
 * at any point in time.
 *
 * The application is responsible for releasing the surface by calling
 * ANativeWindow_release() when done.
 *
 * For more details, see the Java documentation for MediaCodec.createPersistentInputSurface.
 */
media_status_t AMediaCodec_createPersistentInputSurface(
    ANativeWindow **surface)
{
	return -1;
}

/**
 * Set a persistent-surface that can be used as the input to encoder, in place of input buffers
 *
 * The surface provided *must* be a persistent surface created via
 * AMediaCodec_createPersistentInputSurface()
 * This can only be called after the codec has been configured by calling
 * AMediaCodec_configure(..)
{
	return -1;
} and before AMediaCodec_start() has been called.
 *
 * For more details, see the Java documentation for MediaCodec.setInputSurface.
 */
media_status_t AMediaCodec_setInputSurface(
    AMediaCodec *mData, ANativeWindow *surface)
{
	return -1;
}

/**
 * Signal additional parameters to the codec instance.
 *
 * Parameters can be communicated only when the codec is running, i.e
 * after AMediaCodec_start() has been called.
 *
 * NOTE: Some of these parameter changes may silently fail to apply.
 */
media_status_t AMediaCodec_setParameters(
    AMediaCodec *mData, const AMediaFormat *params)
{
	return -1;
}

/**
 * Signals end-of-stream on input. Equivalent to submitting an empty buffer with
 * AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM set.
 *
 * Returns AMEDIA_ERROR_INVALID_OPERATION when used with an encoder not in executing state
 * or not receiving input from a Surface created by AMediaCodec_createInputSurface or
 * AMediaCodec_createPersistentInputSurface.
 *
 * Returns the previous codec error if one exists.
 *
 * Returns AMEDIA_OK when completed succesfully.
 *
 * For more details, see the Java documentation for MediaCodec.signalEndOfInputStream.
 */
media_status_t AMediaCodec_signalEndOfInputStream(AMediaCodec *mData)
{
	return -1;
}

typedef enum {
	AMEDIACODECRYPTOINFO_MODE_CLEAR = 0,
	AMEDIACODECRYPTOINFO_MODE_AES_CTR = 1,
	AMEDIACODECRYPTOINFO_MODE_AES_WV = 2,
	AMEDIACODECRYPTOINFO_MODE_AES_CBC = 3
} cryptoinfo_mode_t;

typedef struct {
	int32_t encryptBlocks;
	int32_t skipBlocks;
} cryptoinfo_pattern_t;

/**
 * Create an AMediaCodecCryptoInfo from scratch. Use this if you need to use custom
 * crypto info, rather than one obtained from AMediaExtractor.
 *
 * AMediaCodecCryptoInfo describes the structure of an (at least
 * partially) encrypted input sample.
 * A buffer's data is considered to be partitioned into "subsamples",
 * each subsample starts with a (potentially empty) run of plain,
 * unencrypted bytes followed by a (also potentially empty) run of
 * encrypted bytes.
 * numBytesOfClearData can be null to indicate that all data is encrypted.
 * This information encapsulates per-sample metadata as outlined in
 * ISO/IEC FDIS 23001-7:2011 "Common encryption in ISO base media file format files".
 */
AMediaCodecCryptoInfo *AMediaCodecCryptoInfo_new(
    int numsubsamples,
    uint8_t key[16],
    uint8_t iv[16],
    cryptoinfo_mode_t mode,
    size_t *clearbytes,
    size_t *encryptedbytes)
{
	return -0;
}

/**
 * delete an AMediaCodecCryptoInfo created previously with AMediaCodecCryptoInfo_new, or
 * obtained from AMediaExtractor
 */
media_status_t AMediaCodecCryptoInfo_delete(AMediaCodecCryptoInfo *)
{
	return -1;
}

/**
 * Set the crypto pattern on an AMediaCryptoInfo object
 */
void AMediaCodecCryptoInfo_setPattern(
    AMediaCodecCryptoInfo *info,
    cryptoinfo_pattern_t *pattern)
{
	return;
}

/**
 * The number of subsamples that make up the buffer's contents.
 */
size_t AMediaCodecCryptoInfo_getNumSubSamples(AMediaCodecCryptoInfo *)
{
	return -1;
}

/**
 * A 16-byte opaque key
 */
media_status_t AMediaCodecCryptoInfo_getKey(AMediaCodecCryptoInfo *, uint8_t *dst)
{
	return -1;
}

/**
 * A 16-byte initialization vector
 */
media_status_t AMediaCodecCryptoInfo_getIV(AMediaCodecCryptoInfo *, uint8_t *dst)
{
	return -1;
}

/**
 * The type of encryption that has been applied,
 * one of AMEDIACODECRYPTOINFO_MODE_CLEAR or AMEDIACODECRYPTOINFO_MODE_AES_CTR.
 */
cryptoinfo_mode_t AMediaCodecCryptoInfo_getMode(AMediaCodecCryptoInfo *)
{
	return -1;
}

/**
 * The number of leading unencrypted bytes in each subsample.
 */
media_status_t AMediaCodecCryptoInfo_getClearBytes(AMediaCodecCryptoInfo *, size_t *dst)
{
	return -1;
}

/**
 * The number of trailing encrypted bytes in each subsample.
 */
media_status_t AMediaCodecCryptoInfo_getEncryptedBytes(AMediaCodecCryptoInfo *, size_t *dst)
{
	return -1;
}

void *AMediaExtractor_new(void)
{
	return NULL;
}

/*
 * Format keys added after the set above. The string values are the platform
 * values from AOSP frameworks/av/media/ndk/NdkMediaFormat.cpp
 * (android-14.0.0_r1): they are the keys the platform looks format entries up
 * by, so a wrong string silently changes behaviour.
 * Do not change the value of constants.
 */
const char *AMEDIAFORMAT_KEY_BITRATE_MODE = "bitrate-mode";
const char *AMEDIAFORMAT_KEY_COLOR_RANGE = "color-range";
const char *AMEDIAFORMAT_KEY_COLOR_STANDARD = "color-standard";
const char *AMEDIAFORMAT_KEY_COLOR_TRANSFER = "color-transfer";
const char *AMEDIAFORMAT_KEY_LATENCY = "latency";
const char *AMEDIAFORMAT_KEY_PCM_ENCODING = "pcm-encoding";
const char *AMEDIAFORMAT_KEY_ROTATION = "rotation-degrees";

/**
 * Copy the contents of one format into another.
 *
 * AOSP rejects a null source or destination with AMEDIA_ERROR_INVALID_PARAMETER
 * and otherwise clears the destination and extends it with the source entries.
 * ATL has no format object (AMediaFormat_new() above returns NULL), so the
 * non-null case is not reachable; it must not be reported as success.
 */
media_status_t AMediaFormat_copy(AMediaFormat *to, AMediaFormat *from)
{
	if (!to || !from)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/*
 * Async notification callbacks. The struct layout (four function pointers) is
 * part of the NDK ABI and is passed by value.
 */
typedef void (*AMediaCodecOnAsyncInputAvailable)(
    AMediaCodec *, void *, int32_t);
typedef void (*AMediaCodecOnAsyncOutputAvailable)(
    AMediaCodec *, void *, int32_t, AMediaCodecBufferInfo *);
typedef void (*AMediaCodecOnAsyncFormatChanged)(AMediaCodec *, void *, AMediaFormat *);
typedef void (*AMediaCodecOnAsyncError)(
    AMediaCodec *, void *, media_status_t, int32_t, const char *);
typedef struct AMediaCodecOnAsyncNotifyCallback {
	AMediaCodecOnAsyncInputAvailable onAsyncInputAvailable;
	AMediaCodecOnAsyncOutputAvailable onAsyncOutputAvailable;
	AMediaCodecOnAsyncFormatChanged onAsyncFormatChanged;
	AMediaCodecOnAsyncError onAsyncError;
} AMediaCodecOnAsyncNotifyCallback;

/**
 * Get the component name. AOSP returns a heap-allocated name through out_name
 * that the caller must free with AMediaCodec_releaseName.
 *
 * ATL's AMediaCodec is never created (AMediaCodec_createCodecByName and friends
 * above return NULL), so there is no component name to report: fabricating one
 * would make the caller act on a codec that does not exist.
 */
media_status_t AMediaCodec_getName(AMediaCodec *codec, char **out_name)
{
	if (!out_name)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*out_name = NULL;
	if (!codec)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Free the memory pointed to by name, which was returned by AMediaCodec_getName.
 */
void AMediaCodec_releaseName(AMediaCodec *codec, char *name)
{
	free(name);
}

/**
 * Set an asynchronous callback for actionable AMediaCodec events.
 * ATL's codec is a stub without an event source, so async mode cannot be
 * enabled; claiming AMEDIA_OK would leave the caller waiting for callbacks that
 * can never arrive.
 */
media_status_t AMediaCodec_setAsyncNotifyCallback(
    AMediaCodec *codec, AMediaCodecOnAsyncNotifyCallback callback, void *userdata)
{
	if (!codec)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/*
 * Image reader / image (media/NdkImageReader.h, media/NdkImage.h; API 24, with
 * AImageReader_newWithUsage and AImage_getHardwareBuffer added in API 26).
 */
struct AImageReader;
typedef struct AImageReader AImageReader;
struct AImage;
typedef struct AImage AImage;
typedef struct AImageCropRect {
	int32_t left;
	int32_t top;
	int32_t right;
	int32_t bottom;
} AImageCropRect;
struct AHardwareBuffer;
typedef struct AHardwareBuffer AHardwareBuffer;

typedef void (*AImageReader_ImageCallback)(void *context, AImageReader *reader);
typedef struct AImageReader_ImageListener {
	void *context;
	AImageReader_ImageCallback onImageAvailable;
} AImageReader_ImageListener;

/**
 * Create a new image reader. AOSP validates the arguments and then allocates a
 * producer/consumer queue backed by the graphics stack, which ATL does not have;
 * it reports the documented invalid-argument error and a null reader otherwise.
 */
media_status_t AImageReader_newWithUsage(
    int32_t width, int32_t height, int32_t format, uint64_t usage, int32_t maxImages,
    AImageReader **reader)
{
	if (!reader)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*reader = NULL;
	if (width <= 0 || height <= 0 || format <= 0 || !usage || maxImages <= 0)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Delete an image reader. No reader can be created (see above), so there is
 * nothing to release; AOSP's delete is also a no-op for a null reader.
 */
void AImageReader_delete(AImageReader *reader) {}

/**
 * Get the ANativeWindow used to produce images for this reader.
 * No reader can be created (see above), so no window exists.
 */
media_status_t AImageReader_getWindow(AImageReader *reader, ANativeWindow **window)
{
	if (!window)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*window = NULL;
	if (!reader)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Acquire the next image from the reader's queue. No reader can be created (see
 * above), so no image can ever be dequeued.
 */
media_status_t AImageReader_acquireNextImage(AImageReader *reader, AImage **image)
{
	if (!image)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*image = NULL;
	if (!reader)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Set the onImageAvailable listener of this image reader. No reader can be
 * created and no images are ever produced (see above), so the listener can
 * never be invoked; return an explicit media error.
 */
media_status_t AImageReader_setImageListener(AImageReader *reader, AImageReader_ImageListener *listener) {
	return !reader ? AMEDIA_ERROR_INVALID_PARAMETER : AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Delete an image. No image can be acquired (see above), so there is nothing to
 * release; AOSP's delete is also a no-op for a null image.
 */
void AImage_delete(AImage *image) {}

/**
 * Get the crop rectangle of an image. AOSP reports a null image or out-parameter
 * as AMEDIA_ERROR_INVALID_PARAMETER.
 */
media_status_t AImage_getCropRect(const AImage *image, AImageCropRect *rect)
{
	if (!image || !rect)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Get the AHardwareBuffer backing an image. No image can be acquired (see
 * above), so there is no buffer to hand out.
 */
media_status_t AImage_getHardwareBuffer(const AImage *image, AHardwareBuffer **buffer)
{
	if (!buffer)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*buffer = NULL;
	if (!image)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Get the capture timestamp of an image in nanoseconds. AOSP reports a null
 * image or out-parameter as AMEDIA_ERROR_INVALID_PARAMETER.
 */
media_status_t AImage_getTimestamp(const AImage *image, int64_t *timestampNs)
{
	if (!image || !timestampNs)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/*
 * Async acquisition. AOSP's NdkImageReader.cpp implements the synchronous
 * AImageReader_acquireNextImage as the async one with a null fence, so the same
 * argument checks apply: a null reader or image is AMEDIA_ERROR_INVALID_PARAMETER
 * (AOSP NdkImageReader.cpp:968-977) and a reader that exists returns the status
 * of its own queue. No reader can be created here (see AImageReader_newWithUsage
 * above), so the only non-null "reader" is a foreign pointer: there is no queue
 * and therefore no image and no fence, which the documented status
 * AMEDIA_ERROR_UNSUPPORTED reports. The fence out-parameter is set to -1, the
 * NDK's "synchronization fence not needed" value (NdkImageReader.h:475-497), so
 * a caller that ignores the status cannot close a stale descriptor.
 */
media_status_t AImageReader_acquireNextImageAsync(
    AImageReader *reader, AImage **image, int *acquireFenceFd)
{
	if (acquireFenceFd)
		*acquireFenceFd = -1;
	if (!image)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	*image = NULL;
	if (!reader)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/*
 * Delete an image after signalling that the consumer is done with it through a
 * release fence. AOSP (NdkImage.cpp:722-734) closes and frees a non-null image
 * and does nothing for a null one; the fence is consumed by the buffer queue as
 * part of that close. No image can be acquired here, so releasing through the
 * synchronous path is the whole behaviour, and the fence must not be closed
 * here: AOSP does not close it either when there is no image to close.
 */
void AImage_deleteAsync(AImage *image, int releaseFenceFd)
{
	(void)releaseFenceFd;
	AImage_delete(image);
}

/*
 * AMediaCrypto (media/NdkMediaCrypto.h, API 21). AOSP's implementation
 * (NdkMediaCrypto.cpp:39-44,62-90) builds an ICrypto through DrmUtils::MakeCrypto()
 * and reports failure whenever that returns null: the scheme query answers false
 * and AMediaCrypto_new() returns a null object. ATL ships no DRM/crypto plugin
 * and no ICrypto backend, which is exactly that case, so these are the answers
 * rather than a fabricated crypto object that would fail on first use.
 */
typedef uint8_t AMediaUUID[16];

bool AMediaCrypto_isCryptoSchemeSupported(const AMediaUUID uuid)
{
	return false;
}

/**
 * Whether the plugin needs a secure decoder for this mime type. With no plugin
 * there is no component to ask, which AOSP reports as false.
 */
bool AMediaCrypto_requiresSecureDecoderComponent(const char *mime)
{
	return false;
}

/**
 * Create a crypto plugin instance for the scheme in uuid. No plugin exists, so
 * no instance can be created (AOSP returns NULL when crypto->createPlugin
 * fails).
 */
AMediaCrypto *AMediaCrypto_new(const AMediaUUID uuid, const void *initData, size_t initDataSize)
{
	return NULL;
}

/**
 * Delete a crypto object. No object can be created (see above), and deleting a
 * null object is a no-op in AOSP as well.
 */
void AMediaCrypto_delete(AMediaCrypto *crypto) {}

/*
 * AMediaDrm (media/NdkMediaDrm.h, API 21; the async key-request variant is API
 * 33 under __ANDROID_API_T__). AOSP's implementation (NdkMediaDrm.cpp) builds an
 * IDrmPlugin from the UUID and every entry point first checks that both the
 * handle and its plugin exist, answering AMEDIA_ERROR_INVALID_OBJECT otherwise
 * (NdkMediaDrm.cpp:320-332,335-345,348-365,376-412,428-441,510-518,617-624,
 * 639-646,748-756,759-770).
 *
 * ATL has no DRM plugin and no IDrmPlugin backend, so AMediaDrm_createByUUID()
 * cannot produce a usable handle and returns NULL, which is the AOSP outcome for
 * "no plugin supports this scheme" (NdkMediaDrm.cpp:301-307 leaves such a handle
 * with a null mDrm, and every call on it then fails with
 * AMEDIA_ERROR_INVALID_OBJECT). The remaining entry points therefore validate
 * exactly what AOSP validates and then report the root cause: no plugin, meaning
 * AMEDIA_ERROR_UNSUPPORTED for plugin-level operations and
 * AMEDIA_DRM_SESSION_NOT_OPENED for session-scoped ones, since no session can
 * ever have been opened. No session id, key request, provision request or
 * response is ever fabricated.
 */
struct AMediaDrm;
typedef struct AMediaDrm AMediaDrm;

typedef struct {
	const uint8_t *ptr;
	size_t length;
} AMediaDrmByteArray;

typedef AMediaDrmByteArray AMediaDrmSessionId;
typedef AMediaDrmByteArray AMediaDrmScope;
typedef AMediaDrmByteArray AMediaDrmKeySetId;
typedef AMediaDrmByteArray AMediaDrmKeyId;

typedef enum AMediaDrmEventType {
	EVENT_PROVISION_REQUIRED = 1,
	EVENT_KEY_REQUIRED = 2,
	EVENT_KEY_EXPIRED = 3,
	EVENT_VENDOR_DEFINED = 4,
	EVENT_SESSION_RECLAIMED = 5
} AMediaDrmEventType;

typedef enum AMediaDrmKeyType {
	KEY_TYPE_STREAMING = 1,
	KEY_TYPE_OFFLINE = 2,
	KEY_TYPE_RELEASE = 3
} AMediaDrmKeyType;

typedef enum AMediaDrmKeyRequestType {
	KEY_REQUEST_TYPE_INITIAL,
	KEY_REQUEST_TYPE_RENEWAL,
	KEY_REQUEST_TYPE_RELEASE,
	KEY_REQUEST_TYPE_NONE,
	KEY_REQUEST_TYPE_UPDATE
} AMediaDrmKeyRequestType;

typedef struct AMediaDrmKeyValuePair {
	const char *mKey;
	const char *mValue;
} AMediaDrmKeyValue;

typedef enum AMediaKeyStatusType {
	KEY_STATUS_TYPE_USABLE,
	KEY_STATUS_TYPE_EXPIRED,
	KEY_STATUS_TYPE_OUTPUTNOTALLOWED,
	KEY_STATUS_TYPE_STATUSPENDING,
	KEY_STATUS_TYPE_INTERNALERROR
} AMediaDrmKeyStatusType;

typedef struct AMediaDrmKeyStatus {
	AMediaDrmKeyId keyId;
	AMediaDrmKeyStatusType keyType;
} AMediaDrmKeyStatus;

typedef void (*AMediaDrmEventListener)(
    AMediaDrm *, const AMediaDrmSessionId *sessionId, AMediaDrmEventType eventType, int extra,
    const uint8_t *data, size_t dataSize);

typedef void (*AMediaDrmExpirationUpdateListener)(
    AMediaDrm *, const AMediaDrmSessionId *sessionId, int64_t expiryTimeInMS);

typedef void (*AMediaDrmKeysChangeListener)(
    AMediaDrm *, const AMediaDrmSessionId *sessionId, const AMediaDrmKeyStatus *keyStatus,
    size_t numKeys, bool hasNewUsableKey);

/**
 * Create a DRM instance for the scheme in uuid. There is no plugin to
 * instantiate, so AOSP's "no plugin for this scheme" outcome (a handle whose
 * plugin is null, unusable for every operation) is reported as a null handle
 * instead of one that pretends to exist.
 */
AMediaDrm *AMediaDrm_createByUUID(const uint8_t *uuid)
{
	return NULL;
}

/**
 * Release a DRM object created by AMediaDrm_createByUUID. No object is ever
 * created (see above), so only the null case is reachable and releasing it is a
 * no-op.
 */
void AMediaDrm_release(AMediaDrm *drm)
{
	free(drm);
}

/**
 * Register the event listener. AOSP validates the handle first
 * (NdkMediaDrm.cpp:320-323), which is the only failure a handle that ATL can
 * return (null) produces; a non-null handle cannot carry a plugin, so the
 * listener cannot be registered.
 */
media_status_t AMediaDrm_setOnEventListener(AMediaDrm *drm, AMediaDrmEventListener listener)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Register the expiration-update listener. Same contract as
 * setOnEventListener (AOSP NdkMediaDrm.cpp:335-338).
 */
media_status_t AMediaDrm_setOnExpirationUpdateListener(
    AMediaDrm *drm, AMediaDrmExpirationUpdateListener listener)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Register the keys-change listener. Same contract as setOnEventListener
 * (AOSP NdkMediaDrm.cpp:348-351).
 */
media_status_t AMediaDrm_setOnKeysChangeListener(
    AMediaDrm *drm, AMediaDrmKeysChangeListener listener)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Open a session. AOSP validates the handle and then the out-parameter
 * (NdkMediaDrm.cpp:376-382) and clears the returned session id when the plugin
 * cannot open the session (NdkMediaDrm.cpp:385-389); with no plugin there is no
 * session to open, so the id is cleared and the documented
 * AMEDIA_ERROR_UNSUPPORTED is returned rather than a bogus id.
 */
media_status_t AMediaDrm_openSession(AMediaDrm *drm, AMediaDrmSessionId *sessionId)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!sessionId)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	sessionId->ptr = NULL;
	sessionId->length = 0;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Close a session previously opened with AMediaDrm_openSession. AOSP validates
 * the handle and the session id and then looks the session up, reporting
 * AMEDIA_DRM_SESSION_NOT_OPENED for an id that is not open
 * (NdkMediaDrm.cpp:398-409). No session can ever be open here, so that is the
 * answer; the caller's id is not modified.
 */
media_status_t AMediaDrm_closeSession(AMediaDrm *drm, const AMediaDrmSessionId *sessionId)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!sessionId)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_DRM_SESSION_NOT_OPENED;
}

/**
 * Obtain an opaque key request for the license server. AOSP validates the handle
 * and the required pointers (NdkMediaDrm.cpp:436-441) and looks the scope up
 * before touching the plugin (NdkMediaDrm.cpp:443-446), so an unopened scope is
 * AMEDIA_DRM_SESSION_NOT_OPENED; no scope can ever be open here. No key request
 * data is produced: fabricating one would send the caller to a license server
 * with a request the (absent) plugin never made.
 */
media_status_t AMediaDrm_getKeyRequestWithDefaultUrlAndType(
    AMediaDrm *drm, const AMediaDrmScope *scope, const uint8_t *init, size_t initSize,
    const char *mimeType, AMediaDrmKeyType keyType, const AMediaDrmKeyValue *optionalParameters,
    size_t numOptionalParameters, const uint8_t **keyRequest, size_t *keyRequestSize,
    const char **defaultUrl, AMediaDrmKeyRequestType *keyRequestType)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!mimeType || !scope || !keyRequest || !keyRequestSize)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_DRM_SESSION_NOT_OPENED;
}

/**
 * AOSP implements this as the API-33 variant with no default URL and no request
 * type (NdkMediaDrm.cpp:416-425); the same argument checks and the same result
 * apply.
 */
media_status_t AMediaDrm_getKeyRequest(
    AMediaDrm *drm, const AMediaDrmScope *scope, const uint8_t *init, size_t initSize,
    const char *mimeType, AMediaDrmKeyType keyType, const AMediaDrmKeyValue *optionalParameters,
    size_t numOptionalParameters, const uint8_t **keyRequest, size_t *keyRequestSize)
{
	return AMediaDrm_getKeyRequestWithDefaultUrlAndType(
	    drm, scope, init, initSize, mimeType, keyType, optionalParameters, numOptionalParameters,
	    keyRequest, keyRequestSize, NULL, NULL);
}

/**
 * Obtain a provisioning request for the device certificate. AOSP validates the
 * handle and requires the caller to pass a non-zero buffer size in
 * (NdkMediaDrm.cpp:617-624); with no plugin there is no request to fetch, so
 * AMEDIA_ERROR_UNSUPPORTED is reported instead of an empty request the caller
 * would post to a provisioning server.
 */
media_status_t AMediaDrm_getProvisionRequest(
    AMediaDrm *drm, const uint8_t **provisionRequest, size_t *provisionRequestSize,
    const char **serverUrl)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!provisionRequest || !provisionRequestSize || !*provisionRequestSize || !serverUrl)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Deliver a key response to the plugin. AOSP validates the handle and the
 * pointers (NdkMediaDrm.cpp:513-518) and looks the scope up before the plugin
 * (NdkMediaDrm.cpp:521-523); no scope can be open here, so
 * AMEDIA_DRM_SESSION_NOT_OPENED applies and no key set id is produced.
 */
media_status_t AMediaDrm_provideKeyResponse(
    AMediaDrm *drm, const AMediaDrmScope *scope, const uint8_t *response, size_t responseSize,
    AMediaDrmKeySetId *keySetId)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!scope || !response || !responseSize || !keySetId)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_DRM_SESSION_NOT_OPENED;
}

/**
 * Deliver a provisioning response to the plugin. AOSP validates the handle and
 * the response (NdkMediaDrm.cpp:641-646) before calling the plugin; there is no
 * plugin, so no device certificate can be installed and
 * AMEDIA_ERROR_UNSUPPORTED is reported.
 */
media_status_t AMediaDrm_provideProvisionResponse(
    AMediaDrm *drm, const uint8_t *response, size_t responseSize)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	if (!response || !responseSize)
		return AMEDIA_ERROR_INVALID_PARAMETER;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Set a string property on the plugin. AOSP validates the handle and passes the
 * property through (NdkMediaDrm.cpp:748-756); no plugin exists to store it, so
 * AMEDIA_ERROR_UNSUPPORTED is reported instead of pretending the property was
 * applied.
 */
media_status_t AMediaDrm_setPropertyString(
    AMediaDrm *drm, const char *propertyName, const char *value)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}

/**
 * Set a byte-array property on the plugin. AOSP validates the handle and passes
 * the array through (NdkMediaDrm.cpp:759-770); no plugin exists to store it, so
 * AMEDIA_ERROR_UNSUPPORTED is reported.
 */
media_status_t AMediaDrm_setPropertyByteArray(
    AMediaDrm *drm, const char *propertyName, const uint8_t *value, size_t valueSize)
{
	if (!drm)
		return AMEDIA_ERROR_INVALID_OBJECT;
	return AMEDIA_ERROR_UNSUPPORTED;
}
