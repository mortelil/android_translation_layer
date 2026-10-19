// SPDX-License-Identifier: GPL-3.0-only
package org.atl.visiblerect;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Rect;
import android.graphics.Point;
import android.view.View;
import android.widget.FrameLayout;
public class Probe extends Activity {
 int checks;
 void check(boolean ok,String label) {if(!ok)throw new AssertionError(label); checks++;}
 void rect(View v,int l,int t,int r,int b,String label) {
  Rect out=new Rect();check(v.getLocalVisibleRect(out),label+" visible");
  check(out.equals(new Rect(l,t,r,b)),label+" "+out);
 }
 public void onCreate(Bundle state) {super.onCreate(state);try {
  FrameLayout root=new FrameLayout(this), box=new FrameLayout(this);
  View child=new View(this);
  root.addView(box);box.addView(child);
  root.layout(0,0,200,200);box.layout(20,30,120,130);child.layout(-10,5,70,75);
  check(root.getClipChildren() && root.getClipToPadding(),"default clipping");
  rect(child,10,0,80,70,"left clip");
  Rect global=new Rect(); Point offset=new Point();
  check(child.getGlobalVisibleRect(global,offset),"global visible");
  check(global.equals(new Rect(20,35,90,105)),"global "+global);
  check(offset.equals(new Point(10,35)),"unclipped origin "+offset);
  box.scrollTo(15,10);rect(child,25,5,80,70,"parent scroll");
  child.scrollTo(3,4);rect(child,28,9,83,74,"own scroll");child.scrollTo(0,0);box.scrollTo(0,0);
  box.setPadding(8,9,10,11);rect(child,18,4,80,70,"padding clip");
  box.setClipToPadding(false);rect(child,10,0,80,70,"padding disabled");
  root.setClipChildren(false);rect(child,0,0,80,70,"ancestor clipping disabled");
  child.layout(250,0,300,50);check(!child.getLocalVisibleRect(new Rect()),"fully outside root");
  child.layout(0,0,0,40);check(!child.getLocalVisibleRect(new Rect()),"zero width");
  View detached=new View(this);detached.layout(40,50,60,80);rect(detached,0,0,20,30,"detached root");
  final FrameLayout attached=new FrameLayout(this);
  final View mounted=new View(this);
  attached.addView(mounted,new FrameLayout.LayoutParams(80,60));
  attached.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){
   public void onGlobalLayout(){
    attached.getViewTreeObserver().removeOnGlobalLayoutListener(this);
    try {
     check(attached.isAttachedToWindow(),"attached root");
     rect(mounted,0,0,80,60,"attached child");
     android.util.Log.i("VisibleRect","VISIBLE_RECT_PASS checks="+checks);System.out.println("VISIBLE_RECT_PASS checks="+checks);
     finish();System.exit(0);
    }catch(Throwable t){t.printStackTrace();android.util.Log.e("VisibleRect","FAIL "+t);finish();System.exit(1);}
   }
  });
  setContentView(attached);
 }catch(Throwable t){t.printStackTrace();android.util.Log.e("VisibleRect","FAIL "+t);finish();System.exit(1);}}
}
