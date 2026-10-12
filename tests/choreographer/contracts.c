/* SPDX-License-Identifier: GPL-3.0-only */
#include "choreographer.h"
#include "looper.h"
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <unistd.h>
#include <dirent.h>
#include <limits.h>

static AChoreographer *owner;
static pthread_t owner_thread;
static int count;
static int64_t first_frame, second_frame, delayed_start;
static atomic_int worker_ready, worker_done;
static AChoreographer *worker_instance;
static int64_t now_ns(void) { return g_get_monotonic_time() * INT64_C(1000); }
static void frame(int64_t time, void *data) {
    assert(pthread_equal(pthread_self(), owner_thread));
    assert(time > 0 && time <= now_ns());
    assert(data == &count);
    if (!count) first_frame=time; else assert(time==first_frame);
    count++;
}
static void delayed(int64_t time, void *data) {
    assert(time >= delayed_start + INT64_C(50000000));
    assert(pthread_equal(pthread_self(),owner_thread));
    *(int*)data=1;
}
static void repost(int64_t time, void *data) {
    int *calls=data;
    if (!(*calls)++) {
        second_frame=time;
        AChoreographer_postFrameCallback64(owner,repost,data);
    } else assert(time>second_frame);
}
static void legacy(long time, void *data) { assert(time>0); *(int*)data=1; }
static void run_until(int *value, int target) {
    int64_t limit=now_ns()+INT64_C(2000000000);
    while (*value<target && now_ns()<limit) ALooper_pollOnce(50,NULL,NULL,NULL);
    assert(*value==target);
}
static void *poster(void *unused) {
    (void)unused;
    assert(AChoreographer_getInstance()==NULL);
    AChoreographer_postFrameCallback64(owner,frame,&count);
    AChoreographer_postFrameCallback64(owner,frame,&count);
    return NULL;
}
static void worker_callback(int64_t time, void *data) {
    (void)time; (void)data;
    assert(!pthread_equal(pthread_self(),owner_thread));
    atomic_store(&worker_done,1);
}
static void *worker(void *unused) {
    (void)unused;
    ALooper_prepare(0);
    worker_instance=AChoreographer_getInstance();
    assert(worker_instance && worker_instance!=owner);
    atomic_store(&worker_ready,1);
    int64_t limit=now_ns()+INT64_C(2000000000);
    while (!atomic_load(&worker_done) && now_ns()<limit) ALooper_pollOnce(50,NULL,NULL,NULL);
    assert(atomic_load(&worker_done));
    return NULL; // TLS destructor releases timerfd and pending callbacks
}
static void never(int64_t time, void *data) { (void)time; (void)data; abort(); }
static void stress_callback(int64_t time, void *data) {
    assert(pthread_equal(pthread_self(),owner_thread));
    assert(time>0 && time<=now_ns()); ++*(int*)data;
}
static int stress_count;
static void *stress_poster(void *unused) {
    (void)unused;
    for (int i=0;i<100;i++) AChoreographer_postFrameCallback64(owner,stress_callback,&stress_count);
    return NULL;
}
static void *short_lived(void *unused) {
    (void)unused; ALooper_prepare(0);
    AChoreographer_postFrameCallbackDelayed64(AChoreographer_getInstance(),never,NULL,10000);
    return NULL;
}
static int fd_count(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    int n=0; while (readdir(d)) n++; closedir(d); return n;
}
static int64_t observed_gdk_time;
static int64_t previous_gdk_time;
static gboolean observe(GtkWidget *w,GdkFrameClock *clock,void *data) {
    (void)w;(void)data;observed_gdk_time=gdk_frame_clock_get_frame_time(clock)*INT64_C(1000);return TRUE;
}
static void check_gdk(int64_t time, void *data) {
    assert(pthread_equal(owner_thread,pthread_self()));
    assert(time==observed_gdk_time && time>previous_gdk_time);
    previous_gdk_time=time;
    if (++*(int*)data<8) AChoreographer_postFrameCallback64(owner,check_gdk,data);
}
int main(int argc,char **argv) {
    (void)argv; owner_thread=pthread_self();
    assert(!AChoreographer_getInstance());
    ALooper_prepare(0); owner=AChoreographer_getInstance();
    assert(owner && owner==AChoreographer_getInstance());
    if (argc>1) {
        gtk_init(); GtkWidget *w=gtk_window_new();
        gtk_window_set_default_size(GTK_WINDOW(w),200,100);
        ATL_choreographer_set_widget(w);
        guint observer=gtk_widget_add_tick_callback(w,observe,NULL,NULL);
        gtk_window_present(GTK_WINDOW(w));
        while (!gtk_widget_get_mapped(w)) g_main_context_iteration(NULL,TRUE);
        int got=0; AChoreographer_postFrameCallback64(owner,check_gdk,&got);
        int64_t limit=now_ns()+INT64_C(2000000000);
        while (got<8 && now_ns()<limit) {
            while (g_main_context_iteration(NULL,FALSE)) {}
            ALooper_pollOnce(1,NULL,NULL,NULL);
        }
        assert(got==8); gtk_widget_remove_tick_callback(w,observer);
        gtk_window_destroy(GTK_WINDOW(w));
        int again=0; AChoreographer_postFrameCallback64(owner,repost,&again);run_until(&again,2);
        puts("PASS: GDK frame-clock timestamp, owner delivery, destroyed-window fallback");
        return 0;
    }
    pthread_t t; pthread_create(&t,NULL,poster,NULL); pthread_join(t,NULL);
    usleep(25000); assert(count==0); // posting / elapsed time cannot execute callbacks
    run_until(&count,2); // both callbacks in one frame share the timestamp
    int got=0; delayed_start=now_ns();
    AChoreographer_postFrameCallbackDelayed64(owner,delayed,&got,50);run_until(&got,1);
    got=0; AChoreographer_postFrameCallback64(owner,repost,&got);run_until(&got,2);
    got=0; AChoreographer_postFrameCallbackDelayed(owner,legacy,&got,-1);run_until(&got,1);
    pthread_create(&t,NULL,worker,NULL);
    while (!atomic_load(&worker_ready)) usleep(1000);
    AChoreographer_postFrameCallback64(worker_instance,worker_callback,NULL);pthread_join(t,NULL);
    pthread_t posters[4];
    for (int i=0;i<4;i++) pthread_create(&posters[i],NULL,stress_poster,NULL);
    run_until(&stress_count,400);
    for (int i=0;i<4;i++) pthread_join(posters[i],NULL);
    /* Prime Looper's thread-local machinery before counting persistent descriptors. */
    pthread_create(&t,NULL,short_lived,NULL);pthread_join(t,NULL);
    int fds=fd_count();
    for (int i=0;i<30;i++) {pthread_create(&t,NULL,short_lived,NULL);pthread_join(t,NULL);}
    assert(fd_count()==fds);
    // Large delays must neither overflow into an immediate callback nor block
    // another ready callback behind them in the queue.
    AChoreographer_postFrameCallbackDelayed(owner,never,NULL,LONG_MAX);
    AChoreographer_postFrameCallbackDelayed64(owner,never,NULL,UINT32_MAX);
    got=0; AChoreographer_postFrameCallback(owner,legacy,&got);run_until(&got,1);
    int64_t start=now_ns(); ALooper_pollOnce(25,NULL,NULL,NULL);
    // Drain possible registration wake once, then an idle poll really blocks.
    start=now_ns(); ALooper_pollOnce(25,NULL,NULL,NULL);assert(now_ns()-start>=INT64_C(15000000));
    puts("PASS: Looper ownership, cross-thread stress, batch timestamps, delay/overflow, repost, legacy ABI, idle and TLS cleanup");
}
