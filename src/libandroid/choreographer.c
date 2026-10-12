/* SPDX-License-Identifier: GPL-3.0-only */
/* Android 24/29 frame callbacks: per-ALooper ownership, GDK frame timestamps,
 * and timerfd delivery on the owner. No callback ever runs on the posting
 * thread merely because it owns the GLib context. See native-choreographer.md. */
#include "choreographer.h"
#include "looper.h"
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/timerfd.h>
#include <unistd.h>

#define FALLBACK_PERIOD_NS INT64_C(16666667)
#define STALLED_CLOCK_NS INT64_C(250000000)
struct callback {
	struct callback *next;
	int64_t due, frame;
	AChoreographer_frameCallback legacy;
	AChoreographer_frameCallback64 modern;
	void *data;
};
struct AChoreographer {
	struct AChoreographer *next;
	ALooper *looper;
	int fd;
	int64_t last_frame;
	struct callback *pending, *ready;
};
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_key_t instance_key;
static AChoreographer *instances;
static GSource *broker;
static GWeakRef frame_widget;
static guint tick_id;
static gboolean mapped;
static int64_t now_ns(void) { return g_get_monotonic_time() * INT64_C(1000); }

static void free_callbacks(struct callback **list)
{
	while (*list) {
		struct callback *item = *list;
		*list = item->next;
		g_free(item);
	}
}

/* All queue and timer operations are serialized, including cross-thread posts. */
static void arm_locked(AChoreographer *c)
{
	int64_t deadline = 0;
	if (c->ready) deadline = 1;
	else if (c->pending) {
		deadline = c->pending->due;
		deadline = MAX(deadline, c->last_frame + FALLBACK_PERIOD_NS);
		if (mapped) {
			/* GDK normally supplies the frame. Keep a watchdog for an occluded
			 * or stalled compositor rather than hanging the owning Looper. */
			if (deadline < INT64_MAX - STALLED_CLOCK_NS) deadline += STALLED_CLOCK_NS;
		} else if (deadline < INT64_MAX - FALLBACK_PERIOD_NS) {
			deadline = (deadline / FALLBACK_PERIOD_NS + 1) * FALLBACK_PERIOD_NS;
		}
	}
	struct itimerspec spec = {0};
	spec.it_value.tv_sec = deadline / INT64_C(1000000000);
	spec.it_value.tv_nsec = deadline % INT64_C(1000000000);
	if (timerfd_settime(c->fd, TFD_TIMER_ABSTIME, &spec, NULL) != 0) {
		perror("ATL Choreographer timerfd_settime");
		abort(); // void API: never silently drop accepted work
	}
}

static struct callback *take_due_locked(AChoreographer *c, int64_t frame)
{
	if (frame <= c->last_frame) return NULL;
	struct callback *batch = c->pending, **end = &batch;
	while (*end && (*end)->due <= frame) {
		(*end)->frame = frame;
		end = &(*end)->next;
	}
	if (end == &batch) return NULL;
	c->pending = *end;
	*end = NULL;
	c->last_frame = frame;
	return batch;
}

static void cleanup_batch(void *data) { free_callbacks(data); }

static int deliver(int fd, int events, void *data)
{
	(void)events;
	AChoreographer *c = data;
	uint64_t expirations;
	ssize_t result;
	do result = read(fd, &expirations, sizeof(expirations));
	while (result < 0 && errno == EINTR);
	/* A frame-clock update or cross-thread post can rearm the timer between
	 * epoll's readiness snapshot and this callback. Ignore stale readiness. */
	if (result != sizeof(expirations)) return 1;
	pthread_mutex_lock(&lock);
	struct callback *batch = c->ready;
	c->ready = NULL;
	if (!batch) batch = take_due_locked(c, now_ns());
	arm_locked(c);
	pthread_mutex_unlock(&lock);
	/* A callback can post another callback, or even exit its owner thread. */
	pthread_cleanup_push(cleanup_batch, &batch);
	while (batch) {
		struct callback item = *batch;
		struct callback *old = batch;
		batch = batch->next;
		g_free(old);
		if (item.modern) item.modern(item.frame, item.data);
		else item.legacy((long)item.frame, item.data);
	}
	pthread_cleanup_pop(0);
	return 1;
}

static int64_t earliest_locked(void)
{
	int64_t earliest = INT64_MAX;
	for (AChoreographer *c = instances; c; c = c->next)
		if (c->pending) earliest = MIN(earliest, c->pending->due);
	return earliest;
}

static gboolean frame_tick(GtkWidget *widget, GdkFrameClock *clock, gpointer unused)
{
	(void)widget; (void)unused;
	int64_t frame = gdk_frame_clock_get_frame_time(clock) * INT64_C(1000);
	pthread_mutex_lock(&lock);
	for (AChoreographer *c = instances; c; c = c->next) {
		/* A stalled owner consumes its previous frame before accepting another.
		 * This bounds ready-queue growth and preserves timestamps/order. */
		if (!c->ready) c->ready = take_due_locked(c, frame);
		arm_locked(c);
	}
	int64_t earliest = earliest_locked();
	gboolean keep = earliest != INT64_MAX && earliest <= now_ns() + FALLBACK_PERIOD_NS;
	if (!keep) {
		tick_id = 0;
		g_source_set_ready_time(broker, earliest == INT64_MAX ? -1 : earliest / 1000);
	}
	pthread_mutex_unlock(&lock);
	return keep;
}

static gboolean broker_dispatch(GSource *source, GSourceFunc unused, gpointer data)
{
	(void)unused; (void)data;
	GtkWidget *widget = g_weak_ref_get(&frame_widget);
	pthread_mutex_lock(&lock);
	g_source_set_ready_time(source, -1);
	mapped = widget && gtk_widget_get_mapped(widget);
	int64_t earliest = earliest_locked();
	if (mapped && earliest != INT64_MAX) {
		if (earliest <= now_ns() + FALLBACK_PERIOD_NS) {
			if (!tick_id) tick_id = gtk_widget_add_tick_callback(widget, frame_tick, NULL, NULL);
		} else g_source_set_ready_time(source, earliest / 1000);
	}
	for (AChoreographer *c = instances; c; c = c->next) arm_locked(c);
	pthread_mutex_unlock(&lock);
	if (widget) g_object_unref(widget);
	return G_SOURCE_CONTINUE;
}

static void destroy_instance(void *data)
{
	AChoreographer *c = data;
	pthread_mutex_lock(&lock);
	AChoreographer **link = &instances;
	while (*link != c) link = &(*link)->next;
	*link = c->next;
	ALooper_removeFd(c->looper, c->fd);
	close(c->fd);
	free_callbacks(&c->pending);
	free_callbacks(&c->ready);
	g_source_set_ready_time(broker, 0);
	pthread_mutex_unlock(&lock);
	ALooper_release(c->looper);
	g_free(c);
}

static void initialize(void)
{
	if (pthread_key_create(&instance_key, destroy_instance) != 0) abort();
	g_weak_ref_init(&frame_widget, NULL);
	static GSourceFuncs funcs = {.dispatch = broker_dispatch};
	broker = g_source_new(&funcs, sizeof(GSource));
	g_source_set_name(broker, "ATL native frame requests");
	g_source_set_ready_time(broker, -1);
	g_source_attach(broker, g_main_context_default());
}

static void mapping_changed(GtkWidget *widget, gpointer unused)
{
	(void)unused;
	pthread_mutex_lock(&lock);
	mapped = gtk_widget_get_mapped(widget);
	if (!mapped && tick_id) {
		gtk_widget_remove_tick_callback(widget, tick_id);
		tick_id = 0;
	}
	for (AChoreographer *c = instances; c; c = c->next) arm_locked(c);
	g_source_set_ready_time(broker, 0);
	pthread_mutex_unlock(&lock);
}

void ATL_choreographer_set_widget(GtkWidget *widget)
{
	pthread_once(&once, initialize);
	/* The launcher sets this once, before launching application code. */
	g_weak_ref_set(&frame_widget, widget);
	g_signal_connect(widget, "map", G_CALLBACK(mapping_changed), NULL);
	g_signal_connect(widget, "unmap", G_CALLBACK(mapping_changed), NULL);
	mapping_changed(widget, NULL);
}

AChoreographer *AChoreographer_getInstance(void)
{
	pthread_once(&once, initialize);
	AChoreographer *c = pthread_getspecific(instance_key);
	if (c) return c;
	ALooper *looper = ALooper_forThread();
	if (!looper) return NULL;
	c = g_new0(AChoreographer, 1);
	c->fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
	if (c->fd < 0) { g_free(c); return NULL; }
	c->looper = looper;
	ALooper_acquire(looper);
	if (ALooper_addFd(looper, c->fd, -1, 1, deliver, c) != 1) {
		close(c->fd); ALooper_release(looper); g_free(c); return NULL;
	}
	if (pthread_setspecific(instance_key, c) != 0) abort();
	pthread_mutex_lock(&lock);
	c->next = instances;
	instances = c;
	pthread_mutex_unlock(&lock);
	return c;
}

static void post(AChoreographer *c, AChoreographer_frameCallback legacy,
                 AChoreographer_frameCallback64 modern, void *data, uint64_t delay_ms)
{
	if (!c || (!legacy && !modern)) return;
	struct callback *item = g_new0(struct callback, 1);
	int64_t now = now_ns();
	item->due = delay_ms > (uint64_t)(INT64_MAX - now) / 1000000
	    ? INT64_MAX : now + delay_ms * 1000000;
	item->legacy = legacy; item->modern = modern; item->data = data;
	pthread_mutex_lock(&lock);
	/* Even a callback posted reentrantly belongs to a later frame. */
	item->due = MAX(item->due, c->last_frame + 1);
	struct callback **link = &c->pending;
	while (*link && (*link)->due <= item->due) link = &(*link)->next;
	item->next = *link; *link = item;
	arm_locked(c);
	g_source_set_ready_time(broker, 0);
	pthread_mutex_unlock(&lock);
}
void AChoreographer_postFrameCallback(AChoreographer *c, AChoreographer_frameCallback fn, void *data)
{ post(c, fn, NULL, data, 0); }
void AChoreographer_postFrameCallbackDelayed(AChoreographer *c, AChoreographer_frameCallback fn, void *data, long delay)
{ post(c, fn, NULL, data, delay > 0 ? (uint64_t)delay : 0); }
void AChoreographer_postFrameCallback64(AChoreographer *c, AChoreographer_frameCallback64 fn, void *data)
{ post(c, NULL, fn, data, 0); }
void AChoreographer_postFrameCallbackDelayed64(AChoreographer *c, AChoreographer_frameCallback64 fn, void *data, uint32_t delay)
{ post(c, NULL, fn, data, delay); }
