/*
 * android.media.AudioTrack playback backend (ALSA / PulseAudio).
 *
 * Owned PCM state model
 * ---------------------
 * Every AudioTrack owns exactly one `struct atl_audio_track`, addressed through
 * the Java `long pcm_handle` field. `lock` serializes every use of `pcm` (ALSA
 * handles are not thread safe) and protects the state fields; `cond` lets a
 * blocking write wait for room in the device buffer without holding the ALSA
 * call open, so pause()/stop()/flush()/release() can interrupt it.
 *
 * Lifetime: the Java handle owns one reference. Acquiring an in-flight
 * reference and detaching that handle are serialized by handles_lock. Release
 * closes the PCM under its own lock; the final caller frees the state, mutex
 * and condition variable. No tombstone allocation survives a released track.
 *
 * Error reporting: native writes return the CALLER's unit (bytes for byte[] and
 * ByteBuffer, shorts for short[]) and map ALSA failures onto the Android error
 * codes, never reporting success for PCM that was not handed to the device.
 *
 * Reference: AOSP android-15.0.0_r1 media/java/android/media/AudioTrack.java for
 * the public error/blocking/lifecycle semantics; the ALSA state machine is
 * modelled directly.
 */

#include <gtk/gtk.h>

#include <alsa/asoundlib.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../defines.h"
#include "../util.h"
#include "audio.h"

#include "../generated_headers/android_media_AudioTrack.h"

struct atl_audio_track {
	pthread_mutex_t lock;
	pthread_cond_t cond;
	snd_pcm_t *pcm;
	unsigned int channels;
	unsigned int rate;
	snd_pcm_uframes_t period;
	snd_pcm_uframes_t buffer_frames;
	float volume;
	int released;
	int playing;
	int draining;
	unsigned int generation;
	gint references;
	int underruns;
	/* Frames handed to the device, and frames the device has finished
	 * rendering, both since the last drop. The playback head reported to Java
	 * is `rendered`, which keeps AOSP's guarantee that the position is
	 * monotonic during a playback run and is only reset by stop()/flush(). */
	snd_pcm_uframes_t accepted;
	snd_pcm_uframes_t rendered;
};

static pthread_mutex_t handles_lock = PTHREAD_MUTEX_INITIALIZER;

static struct atl_audio_track *track_acquire(JNIEnv *env, jobject this)
{
	pthread_mutex_lock(&handles_lock);
	struct atl_audio_track *t = _PTR(_GET_LONG_FIELD(this, "pcm_handle"));
	if (t) g_atomic_int_inc(&t->references);
	pthread_mutex_unlock(&handles_lock);
	return t;
}

/* Used as a scoped cleanup so error returns cannot leak acquired references. */
static void track_unref(struct atl_audio_track **reference)
{
	struct atl_audio_track *t = *reference;
	if (t && g_atomic_int_dec_and_test(&t->references)) {
		pthread_cond_destroy(&t->cond);
		pthread_mutex_destroy(&t->lock);
		free(t);
	}
}

/* Forget the queued audio (stop()/flush()/release()): the device buffer is
 * dropped and the playback position starts again from zero. */
static void reset_counters(struct atl_audio_track *t)
{
	t->accepted = 0;
	t->rendered = 0;
}

/* How many frames are currently queued (not yet rendered) in the device buffer.
 * snd_pcm_delay() is negative or meaningless outside RUNNING/PAUSED. */
static int queued_frames(struct atl_audio_track *t)
{
	snd_pcm_sframes_t delay = 0;

	if (snd_pcm_delay(t->pcm, &delay) < 0 || delay < 0)
		return 0;
	if ((snd_pcm_uframes_t)delay > t->buffer_frames + t->period)
		return (int)(t->buffer_frames + t->period);
	return (int)delay;
}

/* Map a negative ALSA result to an Android error code. */
static int audio_error(int err)
{
	switch (err) {
	case -EAGAIN:
		return 0; /* caller's short count; no data lost */
	case -EINVAL:
		return -2; /* AudioTrack.ERROR_BAD_VALUE */
	default:
		return -1; /* AudioTrack.ERROR */
	}
}

/* Write `frames` interleaved s16 frames from `src`.
 *
 * Nonblocking mode: hand the device as much as fits right now and return the
 * frame count (>=0, possibly 0). Blocking mode: keep going until everything is
 * queued, waiting on `cond` while the device is full, and return a SHORT count
 * if pause()/stop()/flush()/release() interrupts us (AOSP allows a short count
 * in exactly those cases). Never returns success for frames the device did not
 * accept.
 *
 * `lock` is held on entry and on every return; it is dropped only around the
 * condvar wait. */
static int write_frames_locked(struct atl_audio_track *t, const void *src, int frames, int blocking)
{
	int done = 0;
	int rc = 0;
	int chunk = (int)t->period;
	int16_t *scaled = NULL;
	float volume = t->volume;
	unsigned int channels = t->channels;

	if (t->draining || frames <= 0)
		return 0;
	if (chunk <= 0 || chunk > 4096)
		chunk = 4096;

	if (volume != 1.0f) {
		scaled = malloc((size_t)chunk * channels * sizeof(int16_t));
		if (!scaled)
			return -1;
	}

	if (t->released) { free(scaled); return 0; }

	/* An interruption belongs to existing writers; a new write must not
	 * clear it before a sleeping writer gets to observe it. */
	unsigned int generation = t->generation;

	while (done < frames) {
		int n = frames - done;
		int wrote;
		snd_pcm_sframes_t ret;
		const uint8_t *in;

		if (t->released)
			break;
		if (blocking && (t->generation != generation))
			break;

		if (n > chunk)
			n = chunk;

		in = (const uint8_t *)src + (size_t)done * channels * sizeof(int16_t);
		if (scaled) {
			int i, samples = n * (int)channels;
			for (i = 0; i < samples; i++) {
				int16_t sample;
				memcpy(&sample, in + i * sizeof(sample), sizeof(sample));
				int v = (int)(sample * volume);
				if (v > 32767)
					v = 32767;
				else if (v < -32768)
					v = -32768;
				scaled[i] = (int16_t)v;
			}
			in = (const uint8_t *)scaled;
		}

		ret = snd_pcm_writei(t->pcm, in, (snd_pcm_uframes_t)n);

		if (ret == -EPIPE || ret == -ESTRPIPE) {
			/* underrun/suspend: restart the device and retry the same frames. */
			t->underruns++;
			if (snd_pcm_recover(t->pcm, (int)ret, 0) < 0) {
				rc = (int)ret;
				break;
			}
			continue;
		}
		if (ret == -EBADFD) {
			/* the device was paused/dropped under us */
			if ((t->generation != generation) || t->released)
				break;
			rc = (int)ret;
			break;
		}
		if (ret == -EAGAIN) {
			struct timespec ts;

			if (!blocking || (t->generation != generation) || t->released)
				break; /* short count: nothing more fits right now */

			clock_gettime(CLOCK_REALTIME, &ts);
			ts.tv_nsec += 20 * 1000 * 1000;
			if (ts.tv_nsec >= 1000000000) {
				ts.tv_sec += 1;
				ts.tv_nsec -= 1000000000;
			}
			pthread_cond_timedwait(&t->cond, &t->lock, &ts);
			continue;
		}
		if (ret < 0) {
			rc = (int)ret;
			break;
		}

		wrote = (int)ret;
		done += wrote;
		t->accepted += (snd_pcm_uframes_t)wrote;
		if (wrote > 0 && t->playing && snd_pcm_state(t->pcm) == SND_PCM_STATE_PREPARED) {
			int start = snd_pcm_start(t->pcm);
			if (start < 0) { rc = start; break; }
		}
		if (!blocking || wrote == 0)
			break;
	}

	free(scaled);

	if (done > 0)
		return done; /* partial transfer: caller sees a short count */
	return rc == 0 ? 0 : audio_error(rc);
}

/* Publish counters while holding the PCM lock. */
static void sync_underruns(JNIEnv *env, jobject this, struct atl_audio_track *t, int before)
{
	if (t->underruns != before)
		_SET_INT_FIELD(this, "underrunCount", (jint)t->underruns);
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1constructor(JNIEnv *env, jobject this, jint streamType, jint rate, jint channel_config, jint audioFormat, jint buffer_size, jint mode)
{
	struct atl_audio_track *t;
	snd_pcm_t *pcm = NULL;
	snd_pcm_hw_params_t *params;
	snd_pcm_sw_params_t *sw_params;
	unsigned int channels = 0;
	int ret;

	(void)streamType;
	/* This backend implements streaming PCM16 only. Do not initialize a
	 * static/compressed track that we cannot play according to its contract. */
	if (audioFormat != 2 || mode != 1) return;

	t = calloc(1, sizeof(*t));
	if (!t)
		return; /* pcm_handle stays 0 => STATE_UNINITIALIZED */

	pthread_mutex_init(&t->lock, NULL);
	pthread_cond_init(&t->cond, NULL);
	t->volume = 1.0f;
	t->references = 1;

	ret = snd_pcm_open(&pcm, helper_pcm_device(), SND_PCM_STREAM_PLAYBACK, 0);
	if (ret < 0) {
		printf("AudioTrack: cannot open \"%s\" PCM device: %s\n", helper_pcm_device(), snd_strerror(ret));
		goto fail;
	}

	snd_pcm_hw_params_alloca(&params);
	ret = helper_hw_params_init(pcm, params, (unsigned int)rate, channel_config, SND_PCM_FORMAT_S16_LE, &channels);
	if (ret < 0) {
		printf("AudioTrack: rejected configuration (rate=%d, channel_config=0x%x)\n", rate, channel_config);
		goto fail_close;
	}

	if (buffer_size > 0) {
		/* caller's wish, in frames; the device may pick a different size */
		snd_pcm_uframes_t want = (snd_pcm_uframes_t)buffer_size / channels / 2; /* 2 = s16 */
		snd_pcm_hw_params_set_buffer_size_near(pcm, params, &want);
	}
	ret = snd_pcm_hw_params(pcm, params);
	if (ret < 0) {
		printf("AudioTrack: cannot set hardware parameters: %s\n", snd_strerror(ret));
		goto fail_close;
	}

	snd_pcm_hw_params_get_channels(params, &channels);
	snd_pcm_hw_params_get_period_size(params, &t->period, NULL);
	snd_pcm_hw_params_get_buffer_size(params, &t->buffer_frames);
	if (t->period == 0)
		t->period = 1024;

	snd_pcm_sw_params_alloca(&sw_params);
	ret = snd_pcm_sw_params_current(pcm, sw_params);
	if (ret == 0) {
		/* Disable ALSA's automatic start, including when a stopped track's
		 * buffer fills. Only an Android play() request may start rendering. */
		snd_pcm_uframes_t boundary;
		snd_pcm_sw_params_get_boundary(sw_params, &boundary);
		snd_pcm_sw_params_set_start_threshold(pcm, sw_params, boundary);
		snd_pcm_sw_params_set_avail_min(pcm, sw_params, t->period);
		snd_pcm_sw_params(pcm, sw_params);
	}

	/* Nonblocking at the ALSA level always; blocking writes emulate blocking
	 * behaviour in write_frames_locked() so they stay interruptible. */
	snd_pcm_nonblock(pcm, 1);

	t->pcm = pcm;
	t->channels = channels;
	t->rate = (unsigned int)rate;

	pthread_mutex_lock(&handles_lock);
	_SET_LONG_FIELD(this, "pcm_handle", _INTPTR(t));
	pthread_mutex_unlock(&handles_lock);
	_SET_INT_FIELD(this, "channels", (jint)channels);
	printf("AudioTrack: opened \"%s\": rate=%u channels=%u period=%lu buffer=%lu frames\n",
	       helper_pcm_device(), t->rate, t->channels,
	       (unsigned long)t->period, (unsigned long)t->buffer_frames);
	return;

fail_close:
	snd_pcm_close(pcm);
fail:
	pthread_mutex_destroy(&t->lock);
	pthread_cond_destroy(&t->cond);
	free(t);
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1getOutputSampleRate(JNIEnv *env, jclass this_class, jint streamType)
{
	unsigned int rate;

	(void)env;
	(void)this_class;
	(void)streamType;

	rate = helper_native_output_sample_rate();
	return rate ? (jint)rate : (jint)ATL_DEFAULT_OUTPUT_SAMPLE_RATE;
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_getMinBufferSize(JNIEnv *env, jclass this_class, jint sampleRateInHz, jint channelConfig, jint audioFormat)
{
	snd_pcm_t *pcm = NULL;
	snd_pcm_hw_params_t *params;
	snd_pcm_uframes_t frames;
	unsigned int channels = 0;
	int ret;

	if (audioFormat != 2) return -2; /* unsupported PCM format */


	/* AOSP checks the parameters first: an invalid channel mask or rate is
	 * ERROR_BAD_VALUE no matter whether an output device exists. */
	if (helper_track_params_valid((unsigned int)sampleRateInHz, channelConfig, &channels) < 0)
		return -2; /* AudioTrack.ERROR_BAD_VALUE */

	ret = snd_pcm_open(&pcm, helper_pcm_device(), SND_PCM_STREAM_PLAYBACK, 0);
	if (ret < 0)
		return -1; /* AudioTrack.ERROR */

	snd_pcm_hw_params_alloca(&params);
	ret = helper_hw_params_init(pcm, params, (unsigned int)sampleRateInHz, channelConfig, SND_PCM_FORMAT_S16_LE, &channels);
	if (ret < 0) {
		snd_pcm_close(pcm);
		return -2; /* AudioTrack.ERROR_BAD_VALUE */
	}

	ret = snd_pcm_hw_params(pcm, params);
	if (ret == 0)
		ret = snd_pcm_hw_params_get_period_size(params, &frames, NULL);
	snd_pcm_close(pcm);
	if (ret < 0)
		return -1;

	_SET_STATIC_INT_FIELD(this_class, "frames", (jint)frames);
	return (jint)(frames * channels * 2); /* 2 = s16 */
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1write_1bytes(JNIEnv *env, jobject this, jbyteArray audio_data, jint offset_in_bytes, jint size_in_bytes, jboolean blocking)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	jbyte *buffer;
	int frames, rc, underruns;

	if (!t)
		return -3; /* ERROR_INVALID_OPERATION */
	if (offset_in_bytes < 0 || size_in_bytes < 0)
		return -2;

	buffer = (*env)->GetByteArrayElements(env, audio_data, NULL);
	if (!buffer)
		return -1;

	pthread_mutex_lock(&t->lock);
	if (t->released) {
		pthread_mutex_unlock(&t->lock);
		(*env)->ReleaseByteArrayElements(env, audio_data, buffer, JNI_ABORT);
		return -3;
	}
	underruns = t->underruns;
	frames = size_in_bytes / (int)(t->channels * 2);
	rc = write_frames_locked(t, (const uint8_t *)buffer + offset_in_bytes, frames, blocking ? 1 : 0);
	sync_underruns(env, this, t, underruns);
	pthread_mutex_unlock(&t->lock);

	(*env)->ReleaseByteArrayElements(env, audio_data, buffer, JNI_ABORT);

	if (rc > 0)
		return rc * (int)(t->channels * 2);
	return rc;
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1write_1shorts(JNIEnv *env, jobject this, jshortArray audio_data, jint offset_in_shorts, jint size_in_shorts, jboolean blocking)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	jshort *buffer;
	int frames, rc, underruns;

	if (!t)
		return -3;
	if (offset_in_shorts < 0 || size_in_shorts < 0)
		return -2;

	buffer = (*env)->GetShortArrayElements(env, audio_data, NULL);
	if (!buffer)
		return -1;

	pthread_mutex_lock(&t->lock);
	if (t->released) {
		pthread_mutex_unlock(&t->lock);
		(*env)->ReleaseShortArrayElements(env, audio_data, buffer, JNI_ABORT);
		return -3;
	}
	underruns = t->underruns;
	frames = size_in_shorts / (int)t->channels;
	rc = write_frames_locked(t, (const int16_t *)buffer + offset_in_shorts, frames, blocking ? 1 : 0);
	sync_underruns(env, this, t, underruns);
	pthread_mutex_unlock(&t->lock);

	(*env)->ReleaseShortArrayElements(env, audio_data, buffer, JNI_ABORT);

	if (rc > 0)
		return rc * (int)t->channels;
	return rc;
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1write_1nio(JNIEnv *env, jobject this, jobject buffer, jint size_in_bytes, jboolean blocking)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	jarray array_ref = NULL;
	jbyte *array = NULL;
	int16_t *data;
	int frames, rc, underruns;

	if (!t)
		return -3;
	if (size_in_bytes < 0)
		return -2;

	data = get_nio_buffer(env, buffer, &array_ref, &array);
	if (!data) {
		release_nio_buffer(env, array_ref, array);
		return -2;
	}

	pthread_mutex_lock(&t->lock);
	if (t->released) {
		pthread_mutex_unlock(&t->lock);
		release_nio_buffer(env, array_ref, array);
		return -3;
	}
	underruns = t->underruns;
	frames = size_in_bytes / (int)(t->channels * 2);
	rc = write_frames_locked(t, data, frames, blocking ? 1 : 0);
	sync_underruns(env, this, t, underruns);
	pthread_mutex_unlock(&t->lock);

	release_nio_buffer(env, array_ref, array);

	if (rc > 0)
		return rc * (int)(t->channels * 2);
	return rc;
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1setVolume(JNIEnv *env, jobject this, jfloat volume)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);

	(void)env;

	if (!t)
		return;
	pthread_mutex_lock(&t->lock);
	if (!t->released)
		t->volume = volume;
	pthread_mutex_unlock(&t->lock);
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1play(JNIEnv *env, jobject this)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	int state;

	(void)env;

	if (!t)
		return;
	pthread_mutex_lock(&t->lock);
	if (t->released) {
		pthread_mutex_unlock(&t->lock);
		return;
	}
	t->playing = 1;
	/* Do not reset the interruption generation on play. */
	state = snd_pcm_state(t->pcm);
	if (state == SND_PCM_STATE_PAUSED) {
		snd_pcm_pause(t->pcm, 0);
	} else {
		if (state == SND_PCM_STATE_SETUP)
			snd_pcm_prepare(t->pcm);
		if (snd_pcm_start(t->pcm) == -EPIPE) {
			/* nothing was queued: back to PREPARED so later writes restart it */
			snd_pcm_prepare(t->pcm);
		}
	}
	pthread_cond_broadcast(&t->cond);
	pthread_mutex_unlock(&t->lock);
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1pause(JNIEnv *env, jobject this)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);

	(void)env;

	if (!t)
		return;
	pthread_mutex_lock(&t->lock);
	if (!t->released) {
		t->generation++; /* break an in-flight blocking write */
		t->playing = 0;
		if (snd_pcm_state(t->pcm) == SND_PCM_STATE_RUNNING)
			snd_pcm_pause(t->pcm, 1);
	}
	pthread_cond_broadcast(&t->cond);
	pthread_mutex_unlock(&t->lock);
}

/* Drain queued streaming PCM without holding the lock across a blocking
 * ALSA call. Release/pause/flush can interrupt this wait; a competing write
 * receives a short count while the drain is active. */
JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1stop(JNIEnv *env, jobject this)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	if (!t) return;
	pthread_mutex_lock(&t->lock);
	if (!t->released) {
		unsigned int generation = ++t->generation;
		t->playing = 0;
		t->draining = 1;
		pthread_cond_broadcast(&t->cond);
		int state = snd_pcm_state(t->pcm);
		if (state == SND_PCM_STATE_PAUSED) snd_pcm_pause(t->pcm, 0);
		int rc;
		do {
			rc = (state == SND_PCM_STATE_PREPARED || state == SND_PCM_STATE_SETUP)
			    ? snd_pcm_drop(t->pcm) : snd_pcm_drain(t->pcm); /* always nonblocking */
			if (rc != -EAGAIN) break;
			struct timespec ts;
			clock_gettime(CLOCK_REALTIME, &ts);
			ts.tv_nsec += 20 * 1000 * 1000;
			if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
			pthread_cond_timedwait(&t->cond, &t->lock, &ts);
		} while (!t->released && generation == t->generation);
		t->draining = 0;
		if (!t->released && generation == t->generation) {
			snd_pcm_drop(t->pcm);
			snd_pcm_prepare(t->pcm);
			reset_counters(t);
		}
	}
	pthread_cond_broadcast(&t->cond);
	pthread_mutex_unlock(&t->lock);
}

/* AOSP flush(): discard queued data, but it is a no-op while the track is
 * running (the app must stop/pause first). */
JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1flush(JNIEnv *env, jobject this)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	int state;

	(void)env;

	if (!t)
		return;
	pthread_mutex_lock(&t->lock);
	if (!t->released) {
		state = snd_pcm_state(t->pcm);
		if (state != SND_PCM_STATE_RUNNING) {
			t->generation++;
			snd_pcm_drop(t->pcm);
			snd_pcm_prepare(t->pcm);
			reset_counters(t);
		}
	}
	pthread_cond_broadcast(&t->cond);
	pthread_mutex_unlock(&t->lock);
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1release(JNIEnv *env, jobject this)
{
	pthread_mutex_lock(&handles_lock);
	/* Take ownership of the handle's reference, rather than acquiring another. */
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = _PTR(_GET_LONG_FIELD(this, "pcm_handle"));
	_SET_LONG_FIELD(this, "pcm_handle", 0);
	pthread_mutex_unlock(&handles_lock);

	if (!t)
		return;

	pthread_mutex_lock(&t->lock);
	t->released = 1;
	t->generation++;
	pthread_cond_broadcast(&t->cond);
	if (t->pcm) {
		snd_pcm_drop(t->pcm);
		snd_pcm_close(t->pcm);
		t->pcm = NULL;
	}
	reset_counters(t);
	pthread_mutex_unlock(&t->lock);

}

/* Frames the device has finished rendering since the last stop()/flush(),
 * returned UNTRUNCATED so the Java side can apply AOSP's 32-bit wrap itself
 * (android.media.AudioTrack#headPositionFromFrames); the public
 * getPlaybackHeadPosition() reinterprets the low 32 bits as unsigned. */
JNIEXPORT jlong JNICALL Java_android_media_AudioTrack_native_1getPlaybackHeadPosition(JNIEnv *env, jobject this)
{
	struct atl_audio_track *t __attribute__((cleanup(track_unref))) = track_acquire(env, this);
	jlong rendered_frames;

	(void)env;

	if (!t)
		return 0;
	pthread_mutex_lock(&t->lock);
	if (t->released || !t->pcm) {
		rendered_frames = (jlong)t->rendered;
	} else {
		int state = snd_pcm_state(t->pcm);
		switch (state) {
		case SND_PCM_STATE_RUNNING:
		case SND_PCM_STATE_PAUSED: {
			/* frames the device has finished: accepted minus still queued */
			long rendered = (long)t->accepted - queued_frames(t);
			if (rendered < 0)
				rendered = 0;
			else if (rendered > (long)t->accepted)
				rendered = (long)t->accepted;
			if ((snd_pcm_uframes_t)rendered > t->rendered)
				t->rendered = (snd_pcm_uframes_t)rendered;
			break;
		}
		case SND_PCM_STATE_XRUN:
			/* the buffer ran dry: everything accepted has been rendered */
			t->rendered = t->accepted;
			break;
		default:
			/* PREPARED/SETUP/OPEN: nothing more has been rendered; keep the
			 * last rendered value so a play() after an underrun cannot make
			 * the position jump backwards. */
			break;
		}
		rendered_frames = (jlong)t->rendered;
	}
	pthread_mutex_unlock(&t->lock);
	return rendered_frames;
}
