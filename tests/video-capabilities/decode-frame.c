/* SPDX-License-Identifier: GPL-3.0-only */
#include <libavcodec/avcodec.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
/* One raw Annex-B frame, or one IVF frame. This checks the decoder dependency,
 * not ATL's Surface presentation or real-time throughput. */
int main(int argc, char **argv) {
 if (argc != 3) return 2;
 FILE *f=fopen(argv[2],"rb"); if(!f)return 2;
 fseek(f,0,SEEK_END);long size=ftell(f);rewind(f);
 int offset=strcmp(argv[1],"h264")?44:0;
 if(size<=offset || size>16777216)return 2;
 AVPacket *packet=av_packet_alloc();
 if(av_new_packet(packet,size-offset)<0)return 2;
 fseek(f,offset,SEEK_SET);
 if(fread(packet->data,1,size-offset,f)!=(size_t)(size-offset))return 2;
 fclose(f);
 const AVCodec *codec=avcodec_find_decoder_by_name(argv[1]);
 if(!codec)return 2;
 AVCodecContext *ctx=avcodec_alloc_context3(codec);
 AVFrame *frame=av_frame_alloc();
 int result=1;
 if(avcodec_open2(ctx,codec,NULL)<0 || avcodec_send_packet(ctx,packet)<0)goto done;
 avcodec_send_packet(ctx,NULL);
 if(avcodec_receive_frame(ctx,frame)<0)goto done;
 if(frame->width!=4096 || frame->height!=2160)goto done;
 printf("DECODE_FRAME_PASS %s %dx%d\n",argv[1],frame->width,frame->height);result=0;
 done: av_frame_free(&frame);av_packet_free(&packet);avcodec_free_context(&ctx);return result;
}
