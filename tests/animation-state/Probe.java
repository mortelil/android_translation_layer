// SPDX-License-Identifier: GPL-3.0-only
package org.atl.animationstate;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.Animation;
import android.view.animation.Transformation;
public class Probe extends Activity {
 int checks,starts,ends,applies;
 void check(boolean value,String msg){if(!value)throw new AssertionError(msg);checks++;}
 void fail(Throwable t){t.printStackTrace();android.util.Log.e("AnimationStateProbe","FAIL "+t);finish();System.exit(1);}
 public void onCreate(Bundle state){super.onCreate(state);try{
  final Animation a=new Animation(){public void applyTransformation(float t,Transformation out){check(t==1,"existing final transformation");applies++;}};
  check(!a.hasStarted()&&!a.hasEnded(),"initial state");
  a.setAnimationListener(new Animation.AnimationListener(){
   public void onAnimationStart(Animation x){check(x.hasStarted()&&!x.hasEnded(),"start callback state");starts++;}
   public void onAnimationEnd(Animation x){check(x.hasStarted()&&x.hasEnded(),"normal end callback state");ends++;}
   public void onAnimationRepeat(Animation x){throw new AssertionError("unexpected repeat");}
  });
  a.start();a.cancel();
  a.start();a.start(); // Supersede the previously queued start.
  new Handler().post(new Runnable(){public void run(){try{
   check(starts==1&&ends==1&&applies==1,"one live dispatch");
   check(a.hasStarted()&&a.hasEnded(),"completed state");a.reset();check(a.hasEnded(),"reset preserves end flag");
   a.setStartTime(10);check(!a.hasStarted()&&!a.hasEnded(),"start time clears flags");
   final int[] cancelEnds={0};
   final Animation b=new Animation(){public void applyTransformation(float t,Transformation out){throw new AssertionError("cancelled transformation");}};
   b.setAnimationListener(new Animation.AnimationListener(){
    public void onAnimationStart(Animation x){x.cancel();x.cancel();}
    public void onAnimationEnd(Animation x){check(x.hasStarted()&&!x.hasEnded(),"cancel callback precedes end flag");cancelEnds[0]++;x.cancel();}
    public void onAnimationRepeat(Animation x){}
   });
   b.start();new Handler().post(new Runnable(){public void run(){try{
    check(b.hasEnded()&&cancelEnds[0]==1,"cancel once");
    android.util.Log.i("AnimationStateProbe","ANIMATION_STATE_PASS checks="+checks);System.out.println("ANIMATION_STATE_PASS checks="+checks);finish();System.exit(0);
   }catch(Throwable t){fail(t);}}});
  }catch(Throwable t){fail(t);}}});
 }catch(Throwable t){fail(t);}}
}
