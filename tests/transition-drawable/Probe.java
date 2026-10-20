// SPDX-License-Identifier: Apache-2.0
package org.atl.transitiondrawable;
import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
public class Probe extends Activity {
 void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 int pixel(TransitionDrawable d){Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);d.setBounds(0,0,8,8);d.draw(new Canvas(b));return b.getPixel(4,4);}
 public void onCreate(Bundle state){super.onCreate(state);try{
  int id=getResources().getIdentifier("fade","drawable",getPackageName());
  Drawable inflated=getResources().getDrawable(id);
  check(inflated instanceof TransitionDrawable,"XML preserves transition type");
  TransitionDrawable d=(TransitionDrawable)inflated;
  check(d.getNumberOfLayers()==2,"XML layers");
  check(pixel(d)==Color.RED,"first layer initially visible");
  check(!d.isCrossFadeEnabled(),"crossfade default");d.setCrossFadeEnabled(true);
  check(d.isCrossFadeEnabled(),"crossfade enabled");
  d.startTransition(10);pixel(d);SystemClock.sleep(30);
  check(pixel(d)==Color.BLUE,"transition finishes at second layer");
  d.reverseTransition(10);pixel(d);SystemClock.sleep(30);
  check(pixel(d)==Color.RED,"reverse finishes at first layer");
  d.startTransition(0);pixel(d);SystemClock.sleep(2);
  check(pixel(d)==Color.BLUE,"zero duration finishes");
  d.resetTransition();check(pixel(d)==Color.RED,"reset");
  Drawable copy=d.getConstantState().newDrawable(getResources());
  check(copy instanceof TransitionDrawable,"constant state preserves type");
  check(pixel((TransitionDrawable)copy)==Color.RED,"clone starts at first layer");
  android.widget.ImageView image=new android.widget.ImageView(this);
  StateListDrawable selector=new StateListDrawable();
  Drawable red=new ColorDrawable(Color.RED),blue=new ColorDrawable(Color.BLUE);
  selector.addState(new int[]{android.R.attr.state_enabled},red);
  selector.addState(new int[]{},blue);
  image.setImageDrawable(selector);check(selector.getCurrent()==red,"image initializes enabled selector");
  image.setEnabled(false);check(selector.getCurrent()==blue,"image updates disabled selector");
  image.setEnabled(true);check(selector.getCurrent()==red,"image restores enabled selector");
  System.out.println("TRANSITION_DRAWABLE PASS");finish();System.exit(0);
 }catch(Throwable t){t.printStackTrace();System.out.println("TRANSITION_DRAWABLE FAIL");System.exit(1);}}
}
