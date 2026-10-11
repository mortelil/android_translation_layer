// SPDX-License-Identifier: GPL-3.0-only
#include <gtk/gtk.h>
#include <jni.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
extern void Java_android_widget_ImageView_native_1setScaleType(JNIEnv*,jobject,jlong,jint);
/* Launcher-only symbols; this test never starts an APK or JVM. */
char *apk_path;
char *get_app_data_dir(void) { abort(); }
void back_button_set_sensitive(gboolean sensitive) { abort(); }
GtkWidget *window;
static GtkWidget *picture;
static int step;
static const int modes[]={7,1,3,6,7};
static const int widths[]={20,100,100,100,20};
static const int heights[]={10,100,50,100,10};
static void select_mode(void) {
 Java_android_widget_ImageView_native_1setScaleType(NULL,NULL,(jlong)(intptr_t)picture,modes[step]);
}
static gboolean check(gpointer data) {
 GtkSnapshot *s=gtk_snapshot_new();
 GdkPaintable *p=gtk_widget_paintable_new(picture);
 gdk_paintable_snapshot(p,GDK_SNAPSHOT(s),100,100);
 GskRenderNode*n=gtk_snapshot_free_to_node(s);assert(n);
 GskRenderer*r=gtk_native_get_renderer(GTK_NATIVE(window));
 graphene_rect_t rect=GRAPHENE_RECT_INIT(0,0,100,100);
 GdkTexture*t=gsk_renderer_render_texture(r,n,&rect);
 unsigned char pixels[100*100*4];gdk_texture_download(t,pixels,400);
 int x0=100,y0=100,x1=-1,y1=-1;
 for(int y=0;y<100;y++)for(int x=0;x<100;x++){
  unsigned char*q=pixels+(y*100+x)*4;
  if(q[2]>200&&q[1]<30&&q[0]<30&&q[3]>200){x0=MIN(x0,x);x1=MAX(x1,x);y0=MIN(y0,y);y1=MAX(y1,y);}
 }
 printf("mode=%d red bounds=%dx%d expected=%dx%d\n",modes[step],x1-x0+1,y1-y0+1,widths[step],heights[step]);
 fflush(stdout);
 assert(x1-x0+1==widths[step]&&y1-y0+1==heights[step]);
 assert(x0==(100-widths[step])/2&&y0==(100-heights[step])/2);
 g_object_unref(t);gsk_render_node_unref(n);g_object_unref(p);
 if(++step==5){puts("PASS: ImageView pixel scaling and mode transitions");exit(0);}
 select_mode();return G_SOURCE_CONTINUE;
}
int main(void){
 gtk_init();unsigned char data[20*10*4];
 for(int i=0;i<200;i++){data[4*i]=255;data[4*i+1]=0;data[4*i+2]=0;data[4*i+3]=255;}
 GBytes*b=g_bytes_new(data,sizeof(data));GdkTexture*t=gdk_memory_texture_new(20,10,GDK_MEMORY_R8G8B8A8,b,80);g_bytes_unref(b);
 picture=gtk_picture_new_for_paintable(GDK_PAINTABLE(t));g_object_unref(t);
 window=gtk_window_new();gtk_window_set_decorated(GTK_WINDOW(window),FALSE);gtk_window_set_default_size(GTK_WINDOW(window),100,100);gtk_window_set_child(GTK_WINDOW(window),picture);
 select_mode();gtk_window_present(GTK_WINDOW(window));g_timeout_add(300,check,NULL);
 while(1)g_main_context_iteration(NULL,TRUE);
}
