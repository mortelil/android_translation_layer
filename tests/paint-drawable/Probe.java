// SPDX-License-Identifier: GPL-3.0-only
package org.atl.paintdrawable;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.*;
public class Probe extends Activity {
 int checks;
 void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
 Bitmap draw(PaintDrawable d){Bitmap b=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);d.setBounds(0,0,40,40);d.draw(new Canvas(b));return b;}
 public void onCreate(Bundle state){super.onCreate(state);try{
  android.widget.EditText edit=new android.widget.EditText(this);
  check(edit.getPrivateImeOptions()==null,"default private IME options");
  edit.setPrivateImeOptions("example.test=opaque");check("example.test=opaque".equals(edit.getPrivateImeOptions()),"opaque IME options roundtrip");
  edit.setPrivateImeOptions(null);check(edit.getPrivateImeOptions()==null,"clear private IME options");
  PaintDrawable d=new PaintDrawable(Color.RED);check(d.getPaint().getColor()==Color.RED,"color ctor");
  Bitmap b=draw(d);check(b.getPixel(20,20)==Color.RED,"rectangle fill");check(b.getPixel(0,0)==Color.RED,"square corner");b.recycle();
  d.setCornerRadius(15);b=draw(d);check(Color.alpha(b.getPixel(0,0))==0,"rounded corner");check(b.getPixel(20,20)==Color.RED,"rounded center");b.recycle();
  d.setCornerRadius(0);b=draw(d);check(b.getPixel(0,0)==Color.RED,"reset radius");b.recycle();
  d.setCornerRadii(new float[]{15,15,0,0,0,0,0,0});b=draw(d);check(Color.alpha(b.getPixel(0,0))==0,"individual corner");check(b.getPixel(39,0)==Color.RED,"other corner square");b.recycle();
  d.setCornerRadii(null);check(d.getShape()==null,"null clears shape");
  boolean rejected=false;try{d.setCornerRadii(new float[]{1});}catch(ArrayIndexOutOfBoundsException expected){rejected=true;}check(rejected,"short radii rejected");
  d.setAlpha(128);b=draw(d);check(Math.abs(Color.alpha(b.getPixel(20,20))-128)<=1,"alpha");b.recycle();
  System.out.println("PAINT_DRAWABLE_PASS checks="+checks);android.util.Log.i("PaintProbe","PAINT_DRAWABLE_PASS checks="+checks);finish();System.exit(0);
 }catch(Throwable t){t.printStackTrace();android.util.Log.e("PaintProbe","FAIL "+t);finish();System.exit(1);}}
}
