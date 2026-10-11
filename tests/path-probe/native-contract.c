// SPDX-License-Identifier: GPL-3.0-only
#include "path_ops.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static int inside(GskPath *p, float x, float y, int fill) {
	graphene_point_t q={x,y};
	return gsk_path_in_fill(p,&q,(fill&1)?GSK_FILL_RULE_EVEN_ODD:GSK_FILL_RULE_WINDING) ^ !!(fill&2);
}
static int truth(int a,int b,int op) {
	switch(op) {case 0:return a&&!b;case 1:return a&&b;case 2:return a||b;case 3:return a!=b;default:return b&&!a;}
}
static GskPath *rect(float x,float y,float w,float h) {
	GskPathBuilder *b=gsk_path_builder_new(); graphene_rect_t r=GRAPHENE_RECT_INIT(x,y,w,h);
	gsk_path_builder_add_rect(b,&r);return gsk_path_builder_free_to_path(b);
}
static gboolean curves(GskPathOperation op,const graphene_point_t *p,gsize n,float w,gpointer data) {
	if(op==GSK_PATH_CUBIC||op==GSK_PATH_QUAD||op==GSK_PATH_CONIC) (*(int*)data)++;
	return TRUE;
}
int main(void) {
	int checks=0;
	for(int trial=0;trial<30;trial++) {
		GskPath *a=rect(-0.25f,0.125f,1.0f+trial,2.0f+trial);
		GskPath *b=rect(trial%2?10000000.0f:0.25f,-0.375f,1.0f+trial,1.0f+trial);
		for(int fa=0;fa<4;fa++) for(int fb=0;fb<4;fb++) for(int op=0;op<5;op++) {
			int fill;GskPath *r=atl_path_op(a,b,op,fa,fb,&fill);
			if(!r||fill<0) return 1;
			for(int k=0;k<100;k++) {
				float x=(k%10)*0.357f-0.8f,y=(k/10)*0.439f-0.8f;
				if(inside(r,x,y,fill)!=truth(inside(a,x,y,fa),inside(b,x,y,fb),op)) {
					fprintf(stderr,"truth mismatch trial=%d fill=%d,%d op=%d point=%g,%g\n",trial,fa,fb,op,x,y); return 2;
				}
				checks++;
			}
			gsk_path_unref(r);
		}
		gsk_path_unref(a);gsk_path_unref(b);
	}
	GskPathBuilder *builder=gsk_path_builder_new();
	gsk_path_builder_move_to(builder,0,0);gsk_path_builder_cubic_to(builder,0,2,2,2,2,0);gsk_path_builder_close(builder);
	GskPath *a=gsk_path_builder_free_to_path(builder),*b=rect(100,100,1,1);int fill;
	GskPath *r=atl_path_op(a,b,2,0,0,&fill);int count=0;
	if(!r)return 3;
	gsk_path_foreach(r,GSK_PATH_FOREACH_ALLOW_QUAD|GSK_PATH_FOREACH_ALLOW_CUBIC|GSK_PATH_FOREACH_ALLOW_CONIC,curves,&count);
	if(!count) {fputs("curve flattened\n",stderr);return 4;}
	gsk_path_unref(r);
	if(atl_path_op(a,b,99,0,0,&fill)||fill!=-1)return 5;
	gsk_path_unref(a);gsk_path_unref(b);
	printf("PASS %d independent boolean membership checks, preserved curves, invalid operation\n",checks);
}
