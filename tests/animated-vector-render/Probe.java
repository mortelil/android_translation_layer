// SPDX-License-Identifier: Apache-2.0
package org.atl.animatedvectorrender;
import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
public class Probe extends Activity {
 int started,ended;AnimatedVectorDrawable d; boolean finalFrameDrawn;
 void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 int pixel(Drawable d,int x){Bitmap b=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888);d.setBounds(0,0,20,10);d.draw(new Canvas(b));return b.getPixel(x,5);}
 void fail(Throwable t){t.printStackTrace();System.out.println("ANIMATED_VECTOR FAIL");System.exit(1);}
 public void onCreate(Bundle state){super.onCreate(state);try{
  d=(AnimatedVectorDrawable)getResources().getDrawable(getResources().getIdentifier("animated","drawable",getPackageName()));
  check(pixel(d,3)==Color.BLUE&&pixel(d,16)==0,"initial vector renders");
  final AnimatedVectorDrawable copy=(AnimatedVectorDrawable)d.getConstantState().newDrawable(getResources());
  d.registerAnimationCallback(new Animatable2.AnimationCallback(){public void onAnimationStart(Drawable v){started++;} public void onAnimationEnd(Drawable v){ended++;}});
  final android.widget.ImageView image = new android.widget.ImageView(this);
  Drawable wrapped = new Drawable() {
   public void draw(Canvas c) { d.setBounds(getBounds()); d.draw(c); if (ended > 0) finalFrameDrawn = true; }
   public int getIntrinsicWidth(){return 20;} public int getIntrinsicHeight(){return 10;}
  };
  d.setCallback(new Drawable.Callback() {
   public void invalidateDrawable(Drawable who){wrapped.invalidateSelf();}
   public void scheduleDrawable(Drawable who,Runnable task,long when){wrapped.scheduleSelf(task,when);}
   public void unscheduleDrawable(Drawable who,Runnable task){wrapped.unscheduleSelf(task);}
  });
  image.setImageDrawable(wrapped);
  android.widget.FrameLayout parent = new android.widget.FrameLayout(this) {
   public boolean drawChild(Canvas canvas,android.view.View child,long time){return super.drawChild(canvas,child,time);}
  };
  parent.addView(image,new android.widget.FrameLayout.LayoutParams(-1,-1)); setContentView(parent);
  android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback(){public void doFrame(long t){
  d.start();d.start();
  new Handler().postDelayed(new Runnable(){public void run(){try{
   check(started==1&&ended==1,"one callback pair per run "+started+","+ended);
   check(!d.isRunning(),"finished animation stops");
   check(finalFrameDrawn,"custom parent presents the final animated frame without resize");
   check(pixel(d,16)==Color.BLUE&&pixel(d,3)==0,"path morph reaches target");
   check(pixel(copy,3)==Color.BLUE,"constant state clone independent");
   copy.start();copy.stop();check(pixel(copy,16)==Color.BLUE,"clone evaluator retained and stop reaches end");
   copy.reverse();copy.stop();check(pixel(copy,3)==Color.BLUE,"reverse reaches start through same animator set");
   d.clearAnimationCallbacks();d.start();d.stop();check(started==1&&ended==1,"callbacks removed");
   System.out.println("ANIMATED_VECTOR PASS");finish();System.exit(0);
  }catch(Throwable t){fail(t);}}},1000);
  }});
 }catch(Throwable t){fail(t);}}
}
