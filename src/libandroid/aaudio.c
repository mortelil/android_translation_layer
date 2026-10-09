// SPDX-License-Identifier: GPL-3.0-only
/* Initial AAudio callback capture backend. PCM16 and float, shared input only.
	* Unsupported requests fail explicitly; no fabricated samples are delivered. */
#include <alsa/asoundlib.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>

typedef struct AAudioStream AAudioStream;
typedef int32_t (*DataCallback)(AAudioStream *, void *, void *, int32_t);
typedef void (*ErrorCallback)(AAudioStream *, void *, int32_t);
typedef struct {
	int32_t direction, format, channels, rate, performance, sharing, preset;
	DataCallback data; void *data_context;
	ErrorCallback error; void *error_context;
} AAudioStreamBuilder;
struct AAudioStream {
	AAudioStreamBuilder config;
	snd_pcm_t *pcm;
	pthread_t thread;
	bool joinable;
	atomic_bool stop;
	void *buffer;
};
enum { INVALID_HANDLE=-892, INVALID_STATE=-895, INVALID_RATE=-880,
	INVALID_FORMAT=-883, ILLEGAL_ARGUMENT=-898, UNAVAILABLE=-889, NO_MEMORY=-887,
	UNIMPLEMENTED=-890, INTERNAL=-896 };
int32_t AAudio_createStreamBuilder(AAudioStreamBuilder **out) {
	if (!out) return ILLEGAL_ARGUMENT;
	*out = calloc(1,sizeof(**out));
	if (!*out) return NO_MEMORY;
	(*out)->sharing=1; (*out)->preset=6; (*out)->performance=10;
	return 0;
}
#define SETTER(name, field) void AAudioStreamBuilder_set##name(AAudioStreamBuilder *b, int32_t value) { if (b) b->field=value; }
SETTER(Direction,direction)
SETTER(Format,format)
SETTER(ChannelCount,channels)
SETTER(SampleRate,rate)
SETTER(PerformanceMode,performance)
SETTER(SharingMode,sharing)
SETTER(InputPreset,preset)
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, DataCallback f, void *ctx) { if(b) {b->data=f;b->data_context=ctx;} }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, ErrorCallback f, void *ctx) { if(b) {b->error=f;b->error_context=ctx;} }
int32_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { free(b); return 0; }
int32_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **out) {
	if (!out) return ILLEGAL_ARGUMENT;
	*out=NULL;
	if (!b) return INVALID_HANDLE;
	if (b->direction != 1 || !b->data) return UNIMPLEMENTED;
	if (b->channels < 0 || b->channels > 8) return ILLEGAL_ARGUMENT;
	if (b->rate < 0) return INVALID_RATE;
	if (b->format != 0 && b->format != 1 && b->format != 2) return INVALID_FORMAT;
	if (b->sharing != 0 && b->sharing != 1) return ILLEGAL_ARGUMENT;
	if (b->performance != 10 && b->performance != 11 && b->performance != 12) return ILLEGAL_ARGUMENT;
	// Android voice processing is not available through this ALSA backend.
	if (b->preset != 1 && b->preset != 5 && b->preset != 6 && b->preset != 9) return UNIMPLEMENTED;
	// Exclusive and low-latency modes are hints: ALSA default negotiates shared access.
	AAudioStream *s=calloc(1,sizeof(*s));
	if (!s) return NO_MEMORY;
	s->config=*b;
	if (!s->config.channels) s->config.channels=1;
	if (!s->config.rate) s->config.rate=48000;
	if (!s->config.format) s->config.format=2;
	atomic_init(&s->stop,true);
	int result=snd_pcm_open(&s->pcm,"default",SND_PCM_STREAM_CAPTURE,SND_PCM_NONBLOCK);
	if (result < 0) { free(s); return UNAVAILABLE; }
	result=snd_pcm_set_params(s->pcm,s->config.format==1 ? SND_PCM_FORMAT_S16_LE : SND_PCM_FORMAT_FLOAT_LE,
		SND_PCM_ACCESS_RW_INTERLEAVED,s->config.channels,s->config.rate,1,40000);
	if (result < 0) { snd_pcm_close(s->pcm); free(s); return UNAVAILABLE; }
	s->buffer=malloc(256 * s->config.channels * (s->config.format==1 ? 2 : 4));
	if (!s->buffer) { snd_pcm_close(s->pcm); free(s); return NO_MEMORY; }
	*out=s; return 0;
}
static void *capture(void *ptr) {
	AAudioStream *s=ptr;
	while (!atomic_load(&s->stop)) {
		snd_pcm_sframes_t frames=snd_pcm_readi(s->pcm,s->buffer,256);
		if (frames == -EAGAIN || frames == 0) { snd_pcm_wait(s->pcm,20); continue; }
		if (frames < 0) {
			if (snd_pcm_recover(s->pcm,frames,1)>=0) continue;
			atomic_store(&s->stop,true);
			if(s->config.error) s->config.error(s,s->config.error_context,INTERNAL);
			break;
		}
		if (s->config.data(s,s->config.data_context,s->buffer,frames) != 0) break;
	}
	atomic_store(&s->stop,true);
	return NULL;
}
int32_t AAudioStream_requestStart(AAudioStream *s) {
	if (!s) return INVALID_HANDLE;
	if (s->joinable) return INVALID_STATE;
	if (snd_pcm_prepare(s->pcm)<0) return UNAVAILABLE;
	atomic_store(&s->stop,false);
	if (pthread_create(&s->thread,NULL,capture,s)) { atomic_store(&s->stop,true); return INTERNAL; }
	s->joinable=true; return 0;
}
int32_t AAudioStream_requestStop(AAudioStream *s) {
	if (!s) return INVALID_HANDLE;
	if (s->joinable && pthread_equal(pthread_self(),s->thread)) return INVALID_STATE;
	atomic_store(&s->stop,true);
	if(s->joinable) { pthread_join(s->thread,NULL); s->joinable=false; }
	snd_pcm_drop(s->pcm); return 0;
}
int32_t AAudioStream_close(AAudioStream *s) {
	if (!s) return INVALID_HANDLE;
	int32_t r=AAudioStream_requestStop(s); if(r) return r;
	snd_pcm_close(s->pcm); free(s->buffer); free(s); return 0;
}
int32_t AAudioStream_getSampleRate(AAudioStream *s) { return s ? s->config.rate : 0; }
const char *AAudio_convertResultToText(int32_t result) {
	switch(result) {
	case 0: return "AAUDIO_OK";
	case INVALID_HANDLE: return "AAUDIO_ERROR_INVALID_HANDLE";
	case INVALID_STATE: return "AAUDIO_ERROR_INVALID_STATE";
	case INVALID_RATE: return "AAUDIO_ERROR_INVALID_RATE";
	case INVALID_FORMAT: return "AAUDIO_ERROR_INVALID_FORMAT";
	case ILLEGAL_ARGUMENT: return "AAUDIO_ERROR_ILLEGAL_ARGUMENT";
	case UNAVAILABLE: return "AAUDIO_ERROR_UNAVAILABLE";
	case NO_MEMORY: return "AAUDIO_ERROR_NO_MEMORY";
	case UNIMPLEMENTED: return "AAUDIO_ERROR_UNIMPLEMENTED";
	default: return "AAUDIO_ERROR_INTERNAL";
	}
}
