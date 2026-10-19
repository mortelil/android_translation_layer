// SPDX-License-Identifier: GPL-3.0-only
package org.atl.layerreplacement;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.graphics.drawable.*;
public class Probe extends Activity {
 int checks;
 void check(boolean yes,String what){if(!yes)throw new AssertionError(what);checks++;}
 static class Padded extends ColorDrawable {
  Padded(){super(Color.RED);}
  public boolean getPadding(Rect out){out.set(2,3,4,5);return true;}
 }
 public void onCreate(Bundle state){super.onCreate(state);int status=0;try{
  ColorDrawable old=new ColorDrawable(Color.RED), replacement=new ColorDrawable(Color.BLUE);
  LayerDrawable layer=new LayerDrawable(new Drawable[]{old});layer.setId(0,42);layer.setBounds(1,2,30,40);
  layer.setDrawable(0,replacement);
  check(layer.getDrawable(0)==replacement,"replacement identity");
  check(layer.getId(0)==42,"layer ID retained");
  check(replacement.getBounds().equals(new Rect(1,2,30,40)),"bounds copied");
  check(old.getCallback()==null,"old callback detached");
  check(replacement.getCallback()==layer,"new callback attached");
  layer.setDrawable(0,replacement);check(replacement.getCallback()==layer,"self replacement");
  check(!layer.setDrawableByLayerId(99,old),"unknown ID");
  check(layer.setDrawableByLayerId(42,new Padded()),"replace by ID");
  Rect pad=new Rect();layer.getPadding(pad);check(pad.equals(new Rect(2,3,4,5)),"new padding");
  layer.setDrawable(0,null);check(layer.getDrawable(0)==null,"null removal");
  layer.getPadding(pad);check(pad.equals(new Rect(2,3,4,5)),"cleared layer retains cached padding (AOSP)");
  layer.setBounds(0,0,8,8);layer.setVisible(false,false);layer.setState(new int[]{android.R.attr.state_pressed});
  check(!layer.isStateful(),"empty layer state");
  check(layer.getIntrinsicWidth()==-1 && layer.getIntrinsicHeight()==-1,"empty intrinsic size");
  Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
  layer.draw(canvas);check(bitmap.getPixel(4,4)==0,"removed layer not drawn");
  LayerDrawable clone=(LayerDrawable)layer.getConstantState().newDrawable();
  check(clone.getDrawable(0)==null,"null survives constant state");clone.mutate();
  layer.setDrawable(0,replacement);layer.setBounds(0,0,7,7);layer.draw(canvas);
  check(bitmap.getPixel(4,4)==Color.BLUE,"replacement draws blue");
  for(int index:new int[]{-1,1}){boolean threw=false;try{layer.setDrawable(index,old);}catch(IndexOutOfBoundsException e){threw=true;}check(threw,"invalid index "+index);}
  System.out.println("LAYER_REPLACEMENT_PASS checks="+checks);android.util.Log.i("LayerReplacement","LAYER_REPLACEMENT_PASS checks="+checks);
 }catch(Throwable t){status=1;t.printStackTrace();android.util.Log.e("LayerReplacement","FAIL "+t);}finish();System.exit(status);}
}
