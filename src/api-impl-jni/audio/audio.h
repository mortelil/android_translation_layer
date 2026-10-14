#pragma once

#include <alsa/asoundlib.h>

#define PCM_DEVICE "default"

/* AOSP AudioSystem.SAMPLE_RATE_HZ_MIN / SAMPLE_RATE_HZ_MAX, the range the
 * audio HAL advertises and AudioTrack accepts. AudioTrack::getMinFrameCount()
 * rejects a rate outside it with BAD_VALUE instead of handing the HAL an
 * impossible request. */
#define ATL_SAMPLE_RATE_HZ_MIN 4000
#define ATL_SAMPLE_RATE_HZ_MAX 192000

/* AOSP DEFAULT_OUTPUT_SAMPLE_RATE (system/core/include/system/audio.h): what
 * android_media_AudioTrack_get_output_sample_rate() reports when the output
 * sample rate cannot be queried. */
#define ATL_DEFAULT_OUTPUT_SAMPLE_RATE 48000

/* Playback/capture PCM device. Defaults to ALSA's "default" (the pulse plugin
 * in the ATL container, which routes to the host sink/source). Override with
 * ATL_AUDIO_PCM_DEVICE, e.g. to exercise the "unavailable device" path. */
const char *helper_pcm_device(void);

/* Validate the Android parameters (channel mask, rate bounds) without a device:
 * an invalid combination must be ERROR_BAD_VALUE even when no PCM device can be
 * opened. Sets *channels on success. Returns 0 or a negative error. */
int helper_track_params_valid(unsigned int rate, int channel_config, unsigned int *channels);

/* Fill in hw_params for `rate`/`channel_config`/`format` and report the channel
 * count in *channels.
 *
 * The exact requested rate is set (never a nearest-match substitution) and read
 * back; an unknown channel_config is rejected instead of being silently
 * downgraded to mono. Returns 0 on success, or a negative error: -EINVAL for a
 * request the output device cannot represent (unknown channel_config or an
 * unsupported sample rate), otherwise the ALSA error. */
int helper_hw_params_init(snd_pcm_t *pcm_handle, snd_pcm_hw_params_t *params, unsigned int rate, int channel_config, snd_pcm_format_t format, unsigned int *channels);

/* Sample rate the container's output device really runs at, as reported by
 * AudioTrack.getNativeOutputSampleRate(); 0 when it could not be queried. */
unsigned int helper_native_output_sample_rate(void);