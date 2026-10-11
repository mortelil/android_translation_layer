// SPDX-License-Identifier: GPL-3.0-only
#include "surface_control.h"
#include "native_window.h"
#include <assert.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <gdk/x11/gdkx.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <string.h>
extern GThread *main_thread_id;
extern GtkWidget *surface_view_widget_new(void);
GtkWidget *window;
static GtkWidget *view;
/* Launcher-only hooks must not be reached by this native widget test. */
char *apk_path;
char *get_app_data_dir(void) { abort(); }
void back_button_set_sensitive(bool sensitive) { abort(); }
static ASurfaceControl *root,*child,*sibling;
static ASurfaceTransaction *second;
static GMainLoop *loop;
static int count,in_apply;
static unsigned long pixel(void) {
 GdkSurface *s=gtk_native_get_surface(GTK_NATIVE(window));
 Display *d=gdk_x11_display_get_xdisplay(gdk_surface_get_display(s));
 XSync(d,False);
 XImage *im=XGetImage(d,gdk_x11_surface_get_xid(s),16,16,1,1,AllPlanes,ZPixmap);
 assert(im);unsigned long result=XGetPixel(im,0,0)&0xffffff;XDestroyImage(im);return result;
}
static AHardwareBuffer *buffer(int blue) {
 AHardwareBuffer_Desc d={.width=2,.height=2,.layers=1,.format=1,.usage=0x33};
 AHardwareBuffer *b;void *p;assert(!AHardwareBuffer_allocate(&d,&b));
 assert(!AHardwareBuffer_lock(b,0x30,-1,NULL,&p));
 assert(AHardwareBuffer_lock(b,3,-1,NULL,&p)==-EBUSY);
 assert(!AHardwareBuffer_unlock(b,NULL));
 assert(!AHardwareBuffer_lock(b,0x30,-1,NULL,&p));
 for(int i=0;i<4;i++) {unsigned char *q=(unsigned char*)p+4*i;q[0]=0;q[1]=blue?0:255;q[2]=blue?255:0;q[3]=255;}
 assert(!AHardwareBuffer_unlock(b,NULL));return b;
}
static void bad(void *context,ASurfaceTransactionStats *stats) { fputs("FALSE COMPLETION\n",stderr);abort(); }
static gpointer references(gpointer data) {
 for(int i=0;i<100000;i++){ASurfaceControl_acquire(data);ASurfaceControl_release(data);}return NULL;
}
static gboolean finish(gpointer unused) {
 assert(count==3);
 ASurfaceTransaction *t=ASurfaceTransaction_create();
 ASurfaceTransaction_setBuffer(t,child,NULL,-1);ASurfaceTransaction_setOnComplete(t,NULL,bad);
 ASurfaceTransaction_apply(t);ASurfaceTransaction_delete(t);
 gtk_window_destroy(GTK_WINDOW(window));
 ASurfaceControl_release(child);ASurfaceControl_release(sibling);ASurfaceControl_release(root);
 puts("PASS: real X11 pixels, post-presentation callbacks, queued-cycle rejection, consumed transactions, callback deletion, buffer/ref ownership");
 g_main_loop_quit(loop);return G_SOURCE_REMOVE;
}
static void completed(void *context,ASurfaceTransactionStats *stats) {
 assert(!in_apply);assert(ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats,child)==-1);
 count++;
 printf("completion=%d pixel=%06lx\n",count,pixel());fflush(stdout);
 if(count==1) {
  assert(pixel()==0x00ff00);
  GThread *thread=g_thread_new("references",references,child);g_thread_join(thread);
  ASurfaceTransaction *invalid=ASurfaceTransaction_create();
  ASurfaceTransaction_reparent(invalid,child,sibling);ASurfaceTransaction_reparent(invalid,sibling,child);
  ASurfaceTransaction_setOnComplete(invalid,NULL,bad);ASurfaceTransaction_apply(invalid);ASurfaceTransaction_delete(invalid);
  int fds[2];assert(!pipe(fds));
  invalid=ASurfaceTransaction_create();ASurfaceTransaction_setBuffer(invalid,child,NULL,fds[0]);
  assert(fcntl(fds[0],F_GETFD)==-1);close(fds[1]);ASurfaceTransaction_setOnComplete(invalid,NULL,bad);
  ASurfaceTransaction_apply(invalid);ASurfaceTransaction_delete(invalid);
  // An unsupported buffer must reject the entire batch, including geometry.
  AHardwareBuffer_Desc bd={.width=16,.height=1,.layers=1,.format=0x21,.usage=0x33};
  AHardwareBuffer *blob;assert(!AHardwareBuffer_allocate(&bd,&blob));
  ARect source={0,0,2,2},offscreen={80,80,100,100};
  invalid=ASurfaceTransaction_create();ASurfaceTransaction_setGeometry(invalid,child,&source,&offscreen,0);
  ASurfaceTransaction_setBuffer(invalid,sibling,blob,-1);AHardwareBuffer_release(blob);
  ASurfaceTransaction_setOnComplete(invalid,NULL,bad);ASurfaceTransaction_apply(invalid);ASurfaceTransaction_delete(invalid);
  second=ASurfaceTransaction_create();AHardwareBuffer *b=buffer(1);
  ASurfaceTransaction_setBuffer(second,child,b,-1);AHardwareBuffer_release(b);
  ASurfaceTransaction_setOnComplete(second,NULL,completed);in_apply=1;ASurfaceTransaction_apply(second);ASurfaceTransaction_apply(second);in_apply=0;
 } else if(count==2) {
  assert(pixel()==0x0000ff);ASurfaceTransaction_delete(second);
  ASurfaceTransaction *t=ASurfaceTransaction_create();ASurfaceTransaction_reparent(t,child,NULL);
  ASurfaceTransaction_setOnComplete(t,NULL,completed);ASurfaceTransaction_apply(t);ASurfaceTransaction_delete(t);
 } else if(count==3) {assert(pixel()!=0x0000ff);g_timeout_add(100,finish,NULL);} else abort();
}
static gboolean start(gpointer unused) {
 if(!gtk_widget_get_mapped(view))return G_SOURCE_CONTINUE;
 struct ANativeWindow native={.surface_view_widget=view};
 root=ASurfaceControl_createFromWindow(&native,"test-root");assert(root);
 child=ASurfaceControl_create(root,"test-child");sibling=ASurfaceControl_create(root,"test-sibling");assert(child&&sibling);
 ASurfaceTransaction *t=ASurfaceTransaction_create();AHardwareBuffer *b=buffer(0);
 ARect source={0,0,2,2},dest={8,8,40,40};
 ASurfaceTransaction_setGeometry(t,child,&source,&dest,4);ASurfaceTransaction_setBuffer(t,child,b,-1);AHardwareBuffer_release(b);
 ASurfaceTransaction_setOnComplete(t,NULL,completed);in_apply=1;ASurfaceTransaction_apply(t);ASurfaceTransaction_delete(t);in_apply=0;
 return G_SOURCE_REMOVE;
}
static gboolean expired(gpointer unused) {fprintf(stderr,"TIMEOUT: callbacks=%d pixel=%06lx; no fabricated completion permitted\n",count,pixel()); GdkFrameClock *c=gtk_widget_get_frame_clock(view); for(gint64 n=gdk_frame_clock_get_history_start(c);n<=gdk_frame_clock_get_frame_counter(c);n++){GdkFrameTimings *t=gdk_frame_clock_get_timings(c,n);fprintf(stderr,"frame=%ld complete=%d present=%ld\n",(long)n,gdk_frame_timings_get_complete(t),(long)gdk_frame_timings_get_presentation_time(t));} exit(2);}
int main(void) {
 gtk_init();main_thread_id=g_thread_self();loop=g_main_loop_new(NULL,FALSE);
 window=gtk_window_new();gtk_window_set_decorated(GTK_WINDOW(window),FALSE);gtk_window_set_default_size(GTK_WINDOW(window),64,64);
 view=surface_view_widget_new();gtk_window_set_child(GTK_WINDOW(window),view);gtk_window_present(GTK_WINDOW(window));
 g_timeout_add(10,start,NULL);g_timeout_add_seconds(8,expired,NULL);g_main_loop_run(loop);
 // Drain queued work after destruction: must not call the pending completion.
 for(int i=0;i<20;i++){g_main_context_iteration(NULL,FALSE);g_usleep(1000);}
 g_main_loop_unref(loop);return 0;
}
