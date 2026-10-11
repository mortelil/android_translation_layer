// SPDX-License-Identifier: GPL-3.0-only
/* CPU RGBA SurfaceControl backend. API declarations follow the NDK header at
 * art_standalone 327b9c2e5ec49176c073df9f7995d7faf69153b5.
 * A completed GTK frame timing, not commit/apply/snapshot, gates OnComplete.
 * Buffer pixels are copied; AHardwareBuffer references are released before
 * completion, so a previous-buffer release fence of -1 is then truthful.
 * GPU/external buffers, acquire fences and non-sRGB dataspace are unsupported;
 * rejected transactions never emit a presentation-complete callback. */
#include "surface_control.h"
#include "native_window.h"
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>

extern GThread *main_thread_id;
static GRecMutex tree_lock;
static guint64 generation;
#define SCENE_KEY "atl-cpu-surface-control-scene"

typedef struct Scene Scene;
struct ASurfaceControl {
	gint refs;
	ASurfaceControl *parent; /* borrowed: parent owns children */
	Scene *scene;            /* only roots; scene owns roots */
	GPtrArray *children;
	GdkTexture *texture;
	ARect source, dest;
	gboolean geometry;
	int transform, dataspace;
};
struct Scene {
	GWeakRef widget;
	GPtrArray *roots;
	guint64 generation, snap_generation;
	gint64 snap_frame;
};
typedef enum { BUFFER, GEOMETRY, REPARENT, DATASPACE } Kind;
typedef struct {
	Kind kind;
	ASurfaceControl *sc, *parent;
	AHardwareBuffer *buffer;
	GdkTexture *texture;
	ARect source, dest;
	int transform, dataspace;
} Op;
typedef struct { void *context; ASurfaceTransaction_OnComplete function; } Completion;
struct ASurfaceTransaction { GPtrArray *ops; GArray *callbacks; gboolean invalid; };
struct ASurfaceTransactionStats { GPtrArray *surfaces; };
typedef struct { GWeakRef widget; guint64 generation; gint64 frame; GdkFrameClock *clock; } Watch;
typedef struct { ASurfaceTransaction *work; GPtrArray *watches; } Pending;

void ASurfaceControl_acquire(ASurfaceControl *sc) { if (sc) g_atomic_int_inc(&sc->refs); }
void ASurfaceControl_release(ASurfaceControl *sc)
{
	if (!sc || !g_atomic_int_dec_and_test(&sc->refs)) return;
	g_rec_mutex_lock(&tree_lock);
	for (guint i=0;i<sc->children->len;i++) ((ASurfaceControl*)g_ptr_array_index(sc->children,i))->parent=NULL;
	g_ptr_array_unref(sc->children);
	g_clear_object(&sc->texture);
	g_rec_mutex_unlock(&tree_lock);
	g_free(sc);
}
static ASurfaceControl *new_control(void)
{
	ASurfaceControl *sc=g_new0(ASurfaceControl,1); sc->refs=1;
	sc->children=g_ptr_array_new_with_free_func((GDestroyNotify)ASurfaceControl_release);
	return sc;
}
static void destroy_scene(gpointer data)
{
	Scene *s=data;g_rec_mutex_lock(&tree_lock);
	for(guint i=0;i<s->roots->len;i++) ((ASurfaceControl*)g_ptr_array_index(s->roots,i))->scene=NULL;
	g_ptr_array_unref(s->roots);g_weak_ref_clear(&s->widget);
	g_rec_mutex_unlock(&tree_lock);g_free(s);
}
ASurfaceControl *ASurfaceControl_createFromWindow(struct ANativeWindow *window,const char *name)
{
	/* GTK widget creation/lifetime must be observed on the GTK thread. */
	if (!window || !name || !window->surface_view_widget || window->surface_texture ||
	    g_thread_self()!=main_thread_id || getenv("ATL_DIRECT_EGL")) { errno=ENOTSUP;return NULL; }
	GtkWidget *widget=window->surface_view_widget;
	if (!g_type_is_a(G_OBJECT_TYPE(widget),g_type_from_name("SurfaceViewWidget"))) { errno=ENOTSUP;return NULL; }
	g_rec_mutex_lock(&tree_lock);
	Scene *s=g_object_get_data(G_OBJECT(widget),SCENE_KEY);
	if(!s) {
		s=g_new0(Scene,1);g_weak_ref_init(&s->widget,widget);
		s->roots=g_ptr_array_new_with_free_func((GDestroyNotify)ASurfaceControl_release);
		g_object_set_data_full(G_OBJECT(widget),SCENE_KEY,s,destroy_scene);
	}
	ASurfaceControl *sc=new_control();sc->scene=s;ASurfaceControl_acquire(sc);g_ptr_array_add(s->roots,sc);
	g_rec_mutex_unlock(&tree_lock);return sc;
}
ASurfaceControl *ASurfaceControl_create(ASurfaceControl *parent,const char *name)
{
	if(!parent || !name) { errno=EINVAL;return NULL; }
	g_rec_mutex_lock(&tree_lock);
	ASurfaceControl *sc=new_control();sc->parent=parent;ASurfaceControl_acquire(sc);g_ptr_array_add(parent->children,sc);
	g_rec_mutex_unlock(&tree_lock);return sc;
}
static void drop_op(gpointer data)
{
	Op *op=data;ASurfaceControl_release(op->sc);ASurfaceControl_release(op->parent);
	AHardwareBuffer_release(op->buffer);g_clear_object(&op->texture);g_free(op);
}
ASurfaceTransaction *ASurfaceTransaction_create(void)
{
	ASurfaceTransaction *t=g_new0(ASurfaceTransaction,1);
	t->ops=g_ptr_array_new_with_free_func(drop_op);t->callbacks=g_array_new(FALSE,FALSE,sizeof(Completion));return t;
}
void ASurfaceTransaction_delete(ASurfaceTransaction *t)
{ if(t) {g_ptr_array_unref(t->ops);g_array_unref(t->callbacks);g_free(t);} }
static Op *add_op(ASurfaceTransaction *t,ASurfaceControl *sc,Kind kind)
{
	if(!t || !sc) { if(t)t->invalid=TRUE;return NULL; }
	Op *op=g_new0(Op,1);op->sc=sc;op->kind=kind;ASurfaceControl_acquire(sc);g_ptr_array_add(t->ops,op);return op;
}
void ASurfaceTransaction_setOnComplete(ASurfaceTransaction *t,void *context,ASurfaceTransaction_OnComplete fn)
{ if(t&&fn) { Completion c={context,fn};g_array_append_val(t->callbacks,c); } }
void ASurfaceTransaction_reparent(ASurfaceTransaction *t,ASurfaceControl *sc,ASurfaceControl *parent)
{ Op *op=add_op(t,sc,REPARENT);if(op) {op->parent=parent;ASurfaceControl_acquire(parent);} }
void ASurfaceTransaction_setGeometry(ASurfaceTransaction *t,ASurfaceControl *sc,const ARect *source,const ARect *dest,int32_t transform)
{
	Op *op=add_op(t,sc,GEOMETRY);if(!op)return;
	if(!source||!dest||source->right<=source->left||source->bottom<=source->top||dest->right<=dest->left||dest->bottom<=dest->top||(transform&~7)) {t->invalid=TRUE;return;}
	op->source=*source;op->dest=*dest;op->transform=transform;
}
void ASurfaceTransaction_setBuffer(ASurfaceTransaction *t,ASurfaceControl *sc,AHardwareBuffer *b,int fence)
{
	Op *op=add_op(t,sc,BUFFER);
	if(fence>=0) {close(fence);if(t)t->invalid=TRUE;} // unsupported: no buffer read occurs
	if(op) {op->buffer=b;AHardwareBuffer_acquire(b);}
}
void ASurfaceTransaction_setBufferDataSpace(ASurfaceTransaction *t,ASurfaceControl *sc,int32_t space)
{ Op *op=add_op(t,sc,DATASPACE);if(op) {op->dataspace=space;if(space!=0&&space!=142671872)t->invalid=TRUE;} }

static Scene *scene_of(ASurfaceControl *sc)
{ while(sc->parent)sc=sc->parent;return sc->scene; }
static void paint_control(ASurfaceControl *sc,GtkSnapshot *snapshot)
{
	gtk_snapshot_save(snapshot);
	if(sc->geometry) {
		float sw=(float)((double)sc->source.right-sc->source.left),sh=(float)((double)sc->source.bottom-sc->source.top);
		float dw=(float)((double)sc->dest.right-sc->dest.left),dh=(float)((double)sc->dest.bottom-sc->dest.top);
		gtk_snapshot_translate(snapshot,&GRAPHENE_POINT_INIT(sc->dest.left,sc->dest.top));
		gtk_snapshot_scale(snapshot,dw/((sc->transform&4)?sh:sw),dh/((sc->transform&4)?sw:sh));
		if(sc->transform&4) {gtk_snapshot_translate(snapshot,&GRAPHENE_POINT_INIT(sh,0));gtk_snapshot_rotate(snapshot,90);}
		if(sc->transform&1) {gtk_snapshot_translate(snapshot,&GRAPHENE_POINT_INIT(sw,0));gtk_snapshot_scale(snapshot,-1,1);}
		if(sc->transform&2) {gtk_snapshot_translate(snapshot,&GRAPHENE_POINT_INIT(0,sh));gtk_snapshot_scale(snapshot,1,-1);}
		gtk_snapshot_push_clip(snapshot,&GRAPHENE_RECT_INIT(0,0,sw,sh));
		gtk_snapshot_translate(snapshot,&GRAPHENE_POINT_INIT(-(double)sc->source.left,-(double)sc->source.top));
	}
	if(sc->texture) gtk_snapshot_append_texture(snapshot,sc->texture,&GRAPHENE_RECT_INIT(0,0,gdk_texture_get_width(sc->texture),gdk_texture_get_height(sc->texture)));
	for(guint i=0;i<sc->children->len;i++)paint_control(g_ptr_array_index(sc->children,i),snapshot);
	if(sc->geometry)gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}
void atl_surface_control_snapshot(GtkWidget *widget,GtkSnapshot *snapshot)
{
	g_rec_mutex_lock(&tree_lock);Scene *s=g_object_get_data(G_OBJECT(widget),SCENE_KEY);
	if(s) {
		for(guint i=0;i<s->roots->len;i++)paint_control(g_ptr_array_index(s->roots,i),snapshot);
		GdkFrameClock *clock=gtk_widget_get_frame_clock(widget);
		if(clock) {s->snap_generation=s->generation;s->snap_frame=gdk_frame_clock_get_frame_counter(clock);}
	}
	g_rec_mutex_unlock(&tree_lock);
}
static void watch_free(gpointer ptr)
{ Watch *w=ptr;g_weak_ref_clear(&w->widget);g_clear_object(&w->clock);g_free(w); }
static void pending_free(gpointer data)
{ Pending *p=data;ASurfaceTransaction_delete(p->work);g_ptr_array_unref(p->watches);g_free(p); }
static gboolean check_presented(gpointer data)
{
	Pending *p=data;gboolean complete=TRUE;
	for(guint i=0;i<p->watches->len;i++) {
		Watch *w=g_ptr_array_index(p->watches,i);GtkWidget *widget=g_weak_ref_get(&w->widget);
		if(!widget)return G_SOURCE_REMOVE; // destroyed: never claim presentation
		Scene *s=g_object_get_data(G_OBJECT(widget),SCENE_KEY);
		if(!s) {g_object_unref(widget);return G_SOURCE_REMOVE;}
		if(w->frame<0 && s->snap_generation>=w->generation) w->frame=s->snap_frame;
		GdkFrameTimings *timings=w->frame>=0?gdk_frame_clock_get_timings(w->clock,w->frame):NULL;
		if(!timings||!gdk_frame_timings_get_complete(timings)||gdk_frame_timings_get_presentation_time(timings)<=0) complete=FALSE;
		g_object_unref(widget);
	}
	if(!complete)return G_SOURCE_CONTINUE;
	ASurfaceTransactionStats stats={g_ptr_array_new()};
	for(guint i=0;i<p->work->ops->len;i++) {Op *op=g_ptr_array_index(p->work->ops,i);g_ptr_array_add(stats.surfaces,op->sc);}
	for(guint i=0;i<p->work->callbacks->len;i++) {Completion c=g_array_index(p->work->callbacks,Completion,i);c.function(c.context,&stats);}
	g_ptr_array_unref(stats.surfaces);return G_SOURCE_REMOVE;
}
int ASurfaceTransactionStats_getPreviousReleaseFenceFd(ASurfaceTransactionStats *stats,ASurfaceControl *sc)
{
	g_return_val_if_fail(stats&&sc,-1);
	for(guint i=0;i<stats->surfaces->len;i++)if(g_ptr_array_index(stats->surfaces,i)==sc)return -1;
	g_warning("SurfaceControl stats queried for an unrelated surface");return -1;
}
static void watch_scene(Pending *p,Scene *s)
{
	if (!s) return;
	GtkWidget *widget=g_weak_ref_get(&s->widget);
	if (!widget) return;
	for(guint i=0;i<p->watches->len;i++) {
		Watch *w=g_ptr_array_index(p->watches,i);GtkWidget *other=g_weak_ref_get(&w->widget);
		gboolean same=other==widget;g_clear_object(&other);if(same) {g_object_unref(widget);return;}
	}
	GdkFrameClock *clock=gtk_widget_get_frame_clock(widget);
	if(clock) {
		Watch *w=g_new0(Watch,1);g_weak_ref_init(&w->widget,widget);w->clock=g_object_ref(clock);w->frame=-1;
		w->generation=s->generation=++generation;g_ptr_array_add(p->watches,w);gtk_widget_queue_draw(widget);
	}
	g_object_unref(widget);
}
static ASurfaceControl *planned_parent(GHashTable *parents,ASurfaceControl *sc)
{ return g_hash_table_contains(parents,sc)?g_hash_table_lookup(parents,sc):sc->parent; }
static gboolean commit_on_main(gpointer data)
{
	ASurfaceTransaction *t=data;Pending *p=g_new0(Pending,1);p->work=t;p->watches=g_ptr_array_new_with_free_func(watch_free);
	g_rec_mutex_lock(&tree_lock);
	GHashTable *parents=g_hash_table_new(g_direct_hash,g_direct_equal);
	for(guint i=0;i<t->ops->len;i++) {Op *op=g_ptr_array_index(t->ops,i);if(op->kind==REPARENT)g_hash_table_insert(parents,op->sc,op->parent);}
	GHashTableIter it;gpointer key;g_hash_table_iter_init(&it,parents);
	while(g_hash_table_iter_next(&it,&key,NULL)) {
		GHashTable *seen=g_hash_table_new(g_direct_hash,g_direct_equal);
		for(ASurfaceControl *sc=key;sc;sc=planned_parent(parents,sc)) {
			if(g_hash_table_contains(seen,sc)) {t->invalid=TRUE;break;}g_hash_table_add(seen,sc);
		}
		g_hash_table_unref(seen);
	}
	// Prepare all buffer copies before changing the tree: failure is atomic.
	for(guint i=0;i<t->ops->len && !t->invalid;i++) {
		Op *op=g_ptr_array_index(t->ops,i);
		if(op->kind==BUFFER && op->buffer) {
			unsigned char *bytes;int width,height;
			if(atl_hardware_buffer_copy_rgba(op->buffer,&bytes,&width,&height)) {t->invalid=TRUE;break;}
			GBytes *pixels=g_bytes_new_take(bytes,(size_t)width*height*4);
			op->texture=gdk_memory_texture_new(width,height,GDK_MEMORY_R8G8B8A8_PREMULTIPLIED,pixels,(size_t)width*4);g_bytes_unref(pixels);
		}
	}
	if(t->invalid) {
		g_warning("SurfaceControl transaction rejected (unsupported buffer/fence/dataspace, geometry or parent cycle); no presentation callback");
		g_hash_table_unref(parents);g_rec_mutex_unlock(&tree_lock);pending_free(p);return G_SOURCE_REMOVE;
	}
	for(guint i=0;i<t->ops->len;i++) {Op *op=g_ptr_array_index(t->ops,i);watch_scene(p,scene_of(op->sc));}
	// Detach changed nodes first; final-parent validation prevents queued cycles.
	g_hash_table_iter_init(&it,parents);
	while(g_hash_table_iter_next(&it,&key,NULL)) {
		ASurfaceControl *sc=key;
		if(sc->parent) {ASurfaceControl *old=sc->parent;sc->parent=NULL;g_ptr_array_remove(old->children,sc);}
		if(sc->scene) {Scene *old=sc->scene;sc->scene=NULL;g_ptr_array_remove(old->roots,sc);}
	}
	gpointer val;g_hash_table_iter_init(&it,parents);
	while(g_hash_table_iter_next(&it,&key,&val))if(val) {
		ASurfaceControl *sc=key,*parent=val;ASurfaceControl_acquire(sc);sc->parent=parent;g_ptr_array_add(parent->children,sc);
	}
	for(guint i=0;i<t->ops->len;i++) {
		Op *op=g_ptr_array_index(t->ops,i);ASurfaceControl *sc=op->sc;
		if(op->kind==BUFFER) {g_set_object(&sc->texture,op->texture);AHardwareBuffer_release(op->buffer);op->buffer=NULL;}
		else if(op->kind==GEOMETRY) {sc->source=op->source;sc->dest=op->dest;sc->transform=op->transform;sc->geometry=TRUE;}
		else if(op->kind==DATASPACE)sc->dataspace=op->dataspace;
		watch_scene(p,scene_of(sc));
	}
	g_hash_table_unref(parents);g_rec_mutex_unlock(&tree_lock);
	if(p->watches->len)g_timeout_add_full(G_PRIORITY_DEFAULT,8,check_presented,p,pending_free);
	else pending_free(p); // no target frame exists; do not invent a completion
	return G_SOURCE_REMOVE;
}
void ASurfaceTransaction_apply(ASurfaceTransaction *t)
{
	if(!t)return;
	// Move payload out before scheduling, so callbacks may delete/reuse t safely.
	ASurfaceTransaction *work=ASurfaceTransaction_create();
	GPtrArray *ops=work->ops;work->ops=t->ops;t->ops=ops;
	GArray *callbacks=work->callbacks;work->callbacks=t->callbacks;t->callbacks=callbacks;
	work->invalid=t->invalid;t->invalid=FALSE;
	g_idle_add_full(G_PRIORITY_DEFAULT,commit_on_main,work,NULL);
}
