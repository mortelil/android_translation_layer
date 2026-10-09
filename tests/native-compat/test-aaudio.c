// SPDX-License-Identifier: GPL-3.0-only
#include "../../src/libandroid/aaudio.c"
#include <assert.h>
#include <stdio.h>
#include <unistd.h>
static atomic_uint calls;
static int32_t data(AAudioStream *s,void *ctx,void *buffer,int32_t frames) {
	assert(ctx==&calls && buffer && frames>0);
	assert(AAudioStream_getSampleRate(s)==48000);
	atomic_fetch_add(&calls,1);
	return 1; // Stop after one callback without blocking or closing from the callback.
}
int main(void) {
	AAudioStreamBuilder *b=NULL; AAudioStream *s=(void *)1;
	assert(AAudio_createStreamBuilder(&b)==0 && b);
	assert(AAudioStreamBuilder_openStream(b,&s)==UNIMPLEMENTED && !s);
	AAudioStreamBuilder_setDirection(b,1);
	AAudioStreamBuilder_setDataCallback(b,data,&calls);
	AAudioStreamBuilder_setFormat(b,99);
	assert(AAudioStreamBuilder_openStream(b,&s)==INVALID_FORMAT && !s);
	AAudioStreamBuilder_setFormat(b,1);
	assert(AAudioStreamBuilder_openStream(b,&s)==0 && s);
	assert(AAudioStreamBuilder_delete(b)==0);
	for(int run=0;run<2;run++) {
		atomic_store(&calls,0);
		assert(AAudioStream_requestStart(s)==0);
		for(int i=0;i<100 && !atomic_load(&calls);i++) usleep(10000);
		assert(atomic_load(&calls)==1);
		assert(AAudioStream_requestStop(s)==0);
	}
	assert(AAudioStream_close(s)==0);
	puts("PASS: AAudio validation, builder lifetime, PCM callback, stop and restart with ALSA null device");
}
