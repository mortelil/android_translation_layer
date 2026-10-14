#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

#include "audio.h"

const char *helper_pcm_device(void)
{
	const char *device = getenv("ATL_AUDIO_PCM_DEVICE");
	return (device && *device) ? device : PCM_DEVICE;
}

/* Decode an Android channel mask/config into an ALSA channel count.
 *
 * Both directions are accepted (AudioTrack out masks and AudioRecord in masks,
 * plus the deprecated CHANNEL_CONFIGURATION_* values some apps still pass):
 *   CHANNEL_CONFIGURATION_MONO (2), CHANNEL_OUT_MONO / CHANNEL_IN_LEFT (4),
 *   CHANNEL_IN_MONO == CHANNEL_OUT_FRONT_CENTER (16)            -> 1
 *   CHANNEL_CONFIGURATION_STEREO (3), CHANNEL_OUT_STEREO ==
 *   CHANNEL_IN_STEREO (12)                                       -> 2
 * Anything else is unknown and must not be guessed at: return -1. */
static int channel_config_to_channels(int channel_config)
{
	switch (channel_config) {
		case 2:  /* AudioFormat.CHANNEL_CONFIGURATION_MONO (deprecated) */
		case 4:  /* AudioFormat.CHANNEL_OUT_MONO / CHANNEL_IN_LEFT */
		case 16: /* AudioFormat.CHANNEL_IN_MONO / CHANNEL_OUT_FRONT_CENTER */
			return 1;
		case 3:  /* AudioFormat.CHANNEL_CONFIGURATION_STEREO (deprecated) */
		case 12: /* AudioFormat.CHANNEL_OUT_STEREO / CHANNEL_IN_STEREO */
			return 2;
		default:
			return -1;
	}
}

int helper_track_params_valid(unsigned int rate, int channel_config, unsigned int *channels)
{
	int decoded = channel_config_to_channels(channel_config);

	if (decoded < 0) {
		printf("ERROR: channel config 0x%x is not a supported mono/stereo layout\n", channel_config);
		return -EINVAL;
	}
	if (rate < ATL_SAMPLE_RATE_HZ_MIN || rate > ATL_SAMPLE_RATE_HZ_MAX) {
		printf("ERROR: sample rate %u Hz outside the supported %d..%d Hz range\n",
		       rate, ATL_SAMPLE_RATE_HZ_MIN, ATL_SAMPLE_RATE_HZ_MAX);
		return -EINVAL;
	}
	if (channels)
		*channels = (unsigned int)decoded;
	return 0;
}

int helper_hw_params_init(snd_pcm_t *pcm_handle, snd_pcm_hw_params_t *params, unsigned int rate, int channel_config, snd_pcm_format_t format, unsigned int *channels)
{
	int ret;
	int decoded;
	unsigned int rate_min = 0, rate_max = 0, got = 0;

	decoded = channel_config_to_channels(channel_config);
	if (decoded < 0) {
		printf("ERROR: channel config 0x%x is not a supported mono/stereo layout\n", channel_config);
		return -EINVAL;
	}
	*channels = (unsigned int)decoded;

	snd_pcm_hw_params_any(pcm_handle, params);

	ret = snd_pcm_hw_params_set_access(pcm_handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
	if (ret < 0) {
		printf("ERROR: Can't set interleaved mode. %s\n", snd_strerror(ret));
		return ret;
	}

	ret = snd_pcm_hw_params_set_format(pcm_handle, params, format);
	if (ret < 0) {
		printf("ERROR: Can't set format. %s\n", snd_strerror(ret));
		return ret;
	}

	ret = snd_pcm_hw_params_set_channels(pcm_handle, params, *channels);
	if (ret < 0) {
		printf("ERROR: Can't set channels number. %s\n", snd_strerror(ret));
		return ret;
	}

	/* A sample rate the backend cannot open has to be rejected here rather
	 * than left to fail inside the plugin with a bare I/O error: an
	 * out-of-range request reaches PulseAudio, the stream cannot be created
	 * and snd_pcm_hw_params() then returns EIO with no hint about the rate. */
	snd_pcm_hw_params_get_rate_min(params, &rate_min, 0);
	snd_pcm_hw_params_get_rate_max(params, &rate_max, 0);
	if (rate < ATL_SAMPLE_RATE_HZ_MIN || rate > ATL_SAMPLE_RATE_HZ_MAX ||
	    rate < rate_min || rate > rate_max) {
		printf("ERROR: sample rate %u Hz is not supported by \"%s\" (accepted range %u..%u Hz)\n",
		       rate, helper_pcm_device(), rate_min, rate_max);
		return -EINVAL;
	}

	/* Set the EXACT requested rate (not the nearest match) and confirm the
	 * backend accepted it. The previous code used set_rate_near() and never
	 * read the result back, so a track could silently run at a different rate
	 * than getSampleRate() reported. */
	ret = snd_pcm_hw_params_set_rate(pcm_handle, params, rate, 0);
	if (ret < 0) {
		printf("ERROR: Can't set exact rate %u Hz. %s\n", rate, snd_strerror(ret));
		return ret;
	}
	if (snd_pcm_hw_params_get_rate(params, &got, 0) < 0 || got != rate) {
		printf("ERROR: output device did not accept the exact rate %u Hz (got %u Hz)\n", rate, got);
		return -EINVAL;
	}

	return 0;
}

static pthread_mutex_t native_rate_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned int native_rate_cache;

/*
 * ATL's output is the container ALSA device (pulse -> the host server). Ask it
 * which rate it really runs at: a fixed-rate device answers with a rate it has,
 * a resampling device (pulse) with the rate the server itself runs at - i.e.
 * the one that needs no resampling. The answer is cached, as AudioSystem caches
 * the output sampling rate; a failed query is not cached.
 */
unsigned int helper_native_output_sample_rate(void)
{
	snd_pcm_t *pcm_handle = NULL;
	snd_pcm_hw_params_t *params;
	unsigned int rate = ATL_DEFAULT_OUTPUT_SAMPLE_RATE;
	unsigned int got = 0;
	int ret;

	pthread_mutex_lock(&native_rate_lock);
	if (native_rate_cache) {
		rate = native_rate_cache;
		pthread_mutex_unlock(&native_rate_lock);
		return rate;
	}
	pthread_mutex_unlock(&native_rate_lock);

	ret = snd_pcm_open(&pcm_handle, helper_pcm_device(), SND_PCM_STREAM_PLAYBACK, 0);
	if (ret < 0) {
		printf("ERROR: Can't open \"%s\" PCM device to query the output sample rate. %s\n",
		       helper_pcm_device(), snd_strerror(ret));
		return 0;
	}

	snd_pcm_hw_params_alloca(&params);
	snd_pcm_hw_params_any(pcm_handle, params);
	snd_pcm_hw_params_set_access(pcm_handle, params, SND_PCM_ACCESS_RW_INTERLEAVED);
	snd_pcm_hw_params_set_format(pcm_handle, params, SND_PCM_FORMAT_S16_LE);
	snd_pcm_hw_params_set_channels(pcm_handle, params, 2);

	ret = snd_pcm_hw_params_set_rate_near(pcm_handle, params, &rate, 0);
	if (ret >= 0)
		ret = snd_pcm_hw_params(pcm_handle, params);
	if (ret >= 0 && snd_pcm_hw_params_get_rate(params, &got, 0) >= 0 &&
	    got >= ATL_SAMPLE_RATE_HZ_MIN && got <= ATL_SAMPLE_RATE_HZ_MAX) {
		rate = got;
	} else {
		printf("ERROR: \"%s\" could not report an output sample rate%s%s\n", helper_pcm_device(),
		       ret < 0 ? ": " : "", ret < 0 ? snd_strerror(ret) : "");
		rate = 0;
	}

	snd_pcm_close(pcm_handle);

	if (rate) {
		pthread_mutex_lock(&native_rate_lock);
		native_rate_cache = rate;
		pthread_mutex_unlock(&native_rate_lock);
	}

	return rate;
}