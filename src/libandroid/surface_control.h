// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include "hardware_buffer.h"
#include <gtk/gtk.h>
typedef struct ASurfaceControl ASurfaceControl;
typedef struct ASurfaceTransaction ASurfaceTransaction;
typedef struct ASurfaceTransactionStats ASurfaceTransactionStats;
typedef struct { int32_t left, top, right, bottom; } ARect;
typedef void (*ASurfaceTransaction_OnComplete)(void *, ASurfaceTransactionStats *);
struct ANativeWindow;
ASurfaceControl *ASurfaceControl_createFromWindow(struct ANativeWindow *, const char *);
ASurfaceControl *ASurfaceControl_create(ASurfaceControl *, const char *);
void ASurfaceControl_acquire(ASurfaceControl *);
void ASurfaceControl_release(ASurfaceControl *);
ASurfaceTransaction *ASurfaceTransaction_create(void);
void ASurfaceTransaction_delete(ASurfaceTransaction *);
void ASurfaceTransaction_setOnComplete(ASurfaceTransaction *, void *, ASurfaceTransaction_OnComplete);
void ASurfaceTransaction_reparent(ASurfaceTransaction *, ASurfaceControl *, ASurfaceControl *);
void ASurfaceTransaction_setGeometry(ASurfaceTransaction *, ASurfaceControl *, const ARect *, const ARect *, int32_t);
void ASurfaceTransaction_setBuffer(ASurfaceTransaction *, ASurfaceControl *, AHardwareBuffer *, int);
void ASurfaceTransaction_setBufferDataSpace(ASurfaceTransaction *, ASurfaceControl *, int32_t);
void ASurfaceTransaction_apply(ASurfaceTransaction *);
int ASurfaceTransactionStats_getPreviousReleaseFenceFd(ASurfaceTransactionStats *, ASurfaceControl *);
/* Called by SurfaceView's GTK snapshot, not by apply(). */
void atl_surface_control_snapshot(GtkWidget *, GtkSnapshot *);
