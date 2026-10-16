// SPDX-License-Identifier: GPL-3.0-only
/*
 * android.media.MediaExtractor demuxing subset for Android Translation Layer.
 *
 * Backend: libavformat, which is already a build dependency of the ATL media
 * JNI library (see src/api-impl-jni/media/android_media_MediaCodec.c). No new
 * external dependency is introduced and no decoder/MediaCodec pipeline is
 * implemented: this only opens a container, enumerates streams and reports
 * their header metadata.
 *
 * Supported entry points:
 *   - open by path                          (libavformat native file access)
 *   - open by FileDescriptor + offset/length (custom AVIOContext over pread(),
 *     the caller's descriptor is duplicated and never repositioned/closed)
 *
 * Reference for the Java-side surface: AOSP android-17.0.0_r1
 * frameworks/base/media/java/android/media/MediaExtractor.java
 * (commit 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d).
 */

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/stat.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>

#include "../defines.h"
#include "../util.h"
#include "../generated_headers/android_media_MediaExtractor.h"

struct atl_extractor {
	AVFormatContext *fmt;
	AVIOContext *avio;
	unsigned char *avio_buffer;
	int fd;              /* owned duplicate, -1 for path-based opens */
	int64_t base_offset; /* first byte of the exposed window */
	int64_t length;      /* exposed byte count, -1 == until EOF */
	int64_t file_size;   /* full size of the underlying file */
	int64_t pos;         /* current position inside the window */
};

/* ---- AVIOContext over pread() ---------------------------------------- */

static int64_t atl_window_end(struct atl_extractor *e)
{
	return e->length >= 0 ? e->length : (e->file_size - e->base_offset);
}

static int atl_avio_read(void *opaque, uint8_t *buf, int size)
{
	struct atl_extractor *e = opaque;
	int64_t avail;

	if (size <= 0)
		return 0;
	avail = atl_window_end(e) - e->pos;
	if (avail <= 0)
		return AVERROR_EOF;
	if ((int64_t)size > avail)
		size = (int)avail;

	/* pread() keeps the caller's file offset untouched. */
	ssize_t n = pread(e->fd, buf, size, e->base_offset + e->pos);
	if (n < 0)
		return AVERROR(errno > 0 ? errno : EIO);
	if (n == 0)
		return AVERROR_EOF;
	e->pos += n;
	return (int)n;
}

static int64_t atl_avio_seek(void *opaque, int64_t offset, int whence)
{
	struct atl_extractor *e = opaque;
	int64_t end = atl_window_end(e);
	int64_t target;

	if (whence & AVSEEK_SIZE)
		return end;
	whence &= ~AVSEEK_FORCE;

	switch (whence) {
	case SEEK_SET: target = offset; break;
	case SEEK_CUR: target = e->pos + offset; break;
	case SEEK_END: target = end + offset; break;
	default: return AVERROR(EINVAL);
	}

	if (target < 0)
		return AVERROR(EINVAL);
	if (target > end)
		target = end;
	e->pos = target;
	return target;
}

/* ---- open/close ------------------------------------------------------ */

static void atl_extractor_destroy(struct atl_extractor *e)
{
	if (!e)
		return;
	if (e->fmt) {
		if (!(e->fmt->iformat && e->fmt->pb == NULL))
			avformat_close_input(&e->fmt);
		else
			avformat_free_context(e->fmt);
	}
	if (e->avio) {
		if (e->avio->buffer)
			av_free(e->avio->buffer);
		avio_context_free(&e->avio);
	}
	if (e->fd >= 0)
		close(e->fd);
	free(e);
}

static struct atl_extractor *atl_extractor_after_open(AVFormatContext *fmt)
{
	struct atl_extractor *e;

	if (avformat_find_stream_info(fmt, NULL) < 0) {
		avformat_close_input(&fmt);
		return NULL;
	}
	if (fmt->nb_streams <= 0) {
		avformat_close_input(&fmt);
		return NULL;
	}

	e = calloc(1, sizeof(*e));
	if (!e) {
		avformat_close_input(&fmt);
		return NULL;
	}
	e->fmt = fmt;
	e->fd = -1;
	e->length = -1;
	return e;
}

static struct atl_extractor *atl_extractor_open_path(const char *path)
{
	AVFormatContext *fmt = NULL;

	if (avformat_open_input(&fmt, path, NULL, NULL) < 0)
		return NULL;
	return atl_extractor_after_open(fmt);
}

static struct atl_extractor *atl_extractor_open_fd(int fd, int64_t offset, int64_t length)
{
	struct atl_extractor *e;
	struct stat st;
	AVFormatContext *fmt;
	int dupfd;
	const int bufsize = 32768;

	if (fd < 0 || offset < 0 || length < -1)
		return NULL;

	dupfd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
	if (dupfd < 0)
		return NULL;
	if (fstat(dupfd, &st) < 0) {
		close(dupfd);
		return NULL;
	}
	if (offset > st.st_size || offset > INT64_MAX - 1) {
		close(dupfd);
		return NULL;
	}

	e = calloc(1, sizeof(*e));
	if (!e) {
		close(dupfd);
		return NULL;
	}
	e->fd = dupfd;
	e->base_offset = offset;
	/* clamp the requested window to the real file size */
	e->length = (length < 0 || offset + length > st.st_size) ? (st.st_size - offset) : length;
	e->file_size = st.st_size;

	e->avio_buffer = av_malloc(bufsize);
	fmt = avformat_alloc_context();
	if (!e->avio_buffer || !fmt) {
		atl_extractor_destroy(e);
		if (fmt)
			avformat_free_context(fmt);
		return NULL;
	}
	e->avio = avio_alloc_context(e->avio_buffer, bufsize, 0, e, atl_avio_read, NULL, atl_avio_seek);
	if (!e->avio) {
		atl_extractor_destroy(e);
		avformat_free_context(fmt);
		return NULL;
	}
	fmt->pb = e->avio;
	fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

	if (avformat_open_input(&fmt, NULL, NULL, NULL) < 0) {
		e->fmt = NULL;
		atl_extractor_destroy(e);
		return NULL;
	}
	e->fmt = fmt;
	if (avformat_find_stream_info(e->fmt, NULL) < 0 || e->fmt->nb_streams <= 0) {
		atl_extractor_destroy(e);
		return NULL;
	}
	return e;
}

/* ---- track introspection -------------------------------------------- */

/*
 * Android canonical MIME for a demuxed track. Mapping follows
 * frameworks/av/media/libmediaplayerservice / MediaFormat conventions:
 * PCM (WAV/AIFF/raw) is "audio/raw", MP3 is "audio/mpeg", etc.
 */
static const char *atl_mime_for_codec(enum AVCodecID id)
{
	switch (id) {
	case AV_CODEC_ID_PCM_S16LE:
	case AV_CODEC_ID_PCM_S16BE:
	case AV_CODEC_ID_PCM_U8:
	case AV_CODEC_ID_PCM_S8:
	case AV_CODEC_ID_PCM_S24LE:
	case AV_CODEC_ID_PCM_S24BE:
	case AV_CODEC_ID_PCM_S32LE:
	case AV_CODEC_ID_PCM_S32BE:
	case AV_CODEC_ID_PCM_F32LE:
	case AV_CODEC_ID_PCM_F32BE:
	case AV_CODEC_ID_PCM_F64LE:
	case AV_CODEC_ID_PCM_F64BE:
	case AV_CODEC_ID_PCM_ALAW:
	case AV_CODEC_ID_PCM_MULAW:
		return "audio/raw";
	case AV_CODEC_ID_MP3: return "audio/mpeg";
	case AV_CODEC_ID_MP2: return "audio/mpeg-L2";
	case AV_CODEC_ID_AAC:
	case AV_CODEC_ID_AAC_LATM: return "audio/mp4a-latm";
	case AV_CODEC_ID_FLAC: return "audio/flac";
	case AV_CODEC_ID_ALAC: return "audio/alac";
	case AV_CODEC_ID_VORBIS: return "audio/vorbis";
	case AV_CODEC_ID_OPUS: return "audio/opus";
	case AV_CODEC_ID_AC3: return "audio/ac3";
	case AV_CODEC_ID_EAC3: return "audio/eac3";
	case AV_CODEC_ID_TRUEHD:
	case AV_CODEC_ID_MLP: return "audio/true-hd";
	case AV_CODEC_ID_DTS: return "audio/vnd.dts";
	case AV_CODEC_ID_APE: return "audio/x-ape";
	case AV_CODEC_ID_AMR_NB: return "audio/3gpp";
	case AV_CODEC_ID_AMR_WB: return "audio/amr-wb";
	case AV_CODEC_ID_GSM:
	case AV_CODEC_ID_GSM_MS: return "audio/gsm";
	default: return NULL;
	}
}

static AVStream *atl_stream(struct atl_extractor *e, int index)
{
	if (index < 0 || index >= (int)e->fmt->nb_streams)
		return NULL;
	return e->fmt->streams[index];
}

static int atl_bits_per_sample(const AVCodecParameters *par)
{
	if (par->bits_per_coded_sample > 0)
		return par->bits_per_coded_sample;
	if (par->bits_per_raw_sample > 0)
		return par->bits_per_raw_sample;
	return av_get_bits_per_sample(par->codec_id);
}

/* ---- JNI entry points ------------------------------------------------ */

JNIEXPORT jlong JNICALL Java_android_media_MediaExtractor_native_1open_1path(JNIEnv *env, jclass class, jstring path_jstr)
{
	const char *path = _CSTRING(path_jstr);
	struct atl_extractor *e;

	if (!path)
		return 0;
	e = atl_extractor_open_path(path);
	(*env)->ReleaseStringUTFChars(env, path_jstr, path);
	return _INTPTR(e);
}

/* java.io.FileDescriptor#getInt$() (libcore/ojluni, Android-added accessor). */
static int atl_fd_of(JNIEnv *env, jobject fd_obj)
{
	jclass fd_class = (*env)->GetObjectClass(env, fd_obj);
	jmethodID get_int;

	if (!fd_class)
		return -1;
	get_int = _METHOD(fd_class, "getInt$", "()I");
	if (!get_int)
		return -1;
	return (*env)->CallIntMethod(env, fd_obj, get_int);
}

JNIEXPORT jlong JNICALL Java_android_media_MediaExtractor_native_1open_1fd(JNIEnv *env, jclass class, jobject fd_obj, jlong offset, jlong length)
{
	struct atl_extractor *e;
	int fd;

	if (!fd_obj)
		return 0;
	fd = atl_fd_of(env, fd_obj);
	if (fd < 0)
		return 0;
	e = atl_extractor_open_fd(fd, (int64_t)offset, (int64_t)length);
	return _INTPTR(e);
}

JNIEXPORT jint JNICALL Java_android_media_MediaExtractor_native_1getTrackCount(JNIEnv *env, jclass class, jlong handle)
{
	struct atl_extractor *e = _PTR(handle);

	return e ? (jint)e->fmt->nb_streams : 0;
}

JNIEXPORT jstring JNICALL Java_android_media_MediaExtractor_native_1getTrackMime(JNIEnv *env, jclass class, jlong handle, jint index)
{
	struct atl_extractor *e = _PTR(handle);
	AVStream *stream = e ? atl_stream(e, index) : NULL;
	const char *mime;

	if (!stream)
		return NULL;
	mime = atl_mime_for_codec(stream->codecpar->codec_id);
	if (!mime) {
		const char *name = avcodec_get_name(stream->codecpar->codec_id);
		char buf[128];
		if (!name)
			return NULL;
		snprintf(buf, sizeof(buf), "audio/x-ffmpeg-%s", name);
		return (*env)->NewStringUTF(env, buf);
	}
	return (*env)->NewStringUTF(env, mime);
}

JNIEXPORT jintArray JNICALL Java_android_media_MediaExtractor_native_1getTrackInts(JNIEnv *env, jclass class, jlong handle, jint index)
{
	struct atl_extractor *e = _PTR(handle);
	AVStream *stream = e ? atl_stream(e, index) : NULL;
	const AVCodecParameters *par;
	jint values[4];
	jintArray result;

	if (!stream)
		return NULL;
	par = stream->codecpar;

	values[0] = par->sample_rate;
	values[1] = par->ch_layout.nb_channels;
	values[2] = par->bit_rate > 0 ? (jint)par->bit_rate : (jint)e->fmt->bit_rate;
	values[3] = atl_bits_per_sample(par);

	result = (*env)->NewIntArray(env, 4);
	if (!result)
		return NULL;
	(*env)->SetIntArrayRegion(env, result, 0, 4, values);
	return result;
}

JNIEXPORT jlong JNICALL Java_android_media_MediaExtractor_native_1getTrackDurationUs(JNIEnv *env, jclass class, jlong handle, jint index)
{
	struct atl_extractor *e = _PTR(handle);
	AVStream *stream = e ? atl_stream(e, index) : NULL;

	if (!stream)
		return 0;
	if (stream->duration > 0 && stream->time_base.den > 0)
		return (jlong)av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
	if (e->fmt->duration > 0)
		return (jlong)e->fmt->duration;
	return 0;
}

JNIEXPORT void JNICALL Java_android_media_MediaExtractor_native_1release(JNIEnv *env, jclass class, jlong handle)
{
	atl_extractor_destroy(_PTR(handle));
}
