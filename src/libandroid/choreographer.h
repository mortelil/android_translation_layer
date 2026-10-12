/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ATL_CHOREOGRAPHER_H
#define ATL_CHOREOGRAPHER_H
#include <stdint.h>
#include <gtk/gtk.h>
typedef struct AChoreographer AChoreographer;
typedef void (*AChoreographer_frameCallback)(long, void *);
typedef void (*AChoreographer_frameCallback64)(int64_t, void *);
AChoreographer *AChoreographer_getInstance(void);
void AChoreographer_postFrameCallback(AChoreographer *, AChoreographer_frameCallback, void *);
void AChoreographer_postFrameCallbackDelayed(AChoreographer *, AChoreographer_frameCallback, void *, long);
void AChoreographer_postFrameCallback64(AChoreographer *, AChoreographer_frameCallback64, void *);
void AChoreographer_postFrameCallbackDelayed64(AChoreographer *, AChoreographer_frameCallback64, void *, uint32_t);
/* Internal: called on the GTK thread; widget is weakly held. */
void ATL_choreographer_set_widget(GtkWidget *widget);
#endif
