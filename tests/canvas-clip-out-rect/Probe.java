// SPDX-License-Identifier: GPL-3.0-only
package org.atl.clipoutrect;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.View;
public class Probe extends Activity {
 int checks;
 void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 void cut(Canvas c,int kind){
  if(kind==0)c.clipOutRect(8,8,24,24);
  if(kind==1)c.clipOutRect(8f,8f,24f,24f);
  if(kind==2)c.clipOutRect(new Rect(8,8,24,24));
  if(kind==3)c.clipOutRect(new RectF(8,8,24,24));
 }
 public void onCreate(Bundle state){super.onCreate(state);try{
  Paint p=new Paint();p.setColor(Color.RED);
  for(int kind=0;kind<4;kind++){
   Bitmap b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);
   c.save();cut(c,kind);c.drawRect(0,0,32,32,p);c.restore();
   check(b.getPixel(2,2)==Color.RED,"outside "+kind);
   check(b.getPixel(16,16)==Color.TRANSPARENT,"hole "+kind);
   c.drawRect(12,12,20,20,p);check(b.getPixel(16,16)==Color.RED,"restore "+kind);
  }
  Bitmap b=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);
  c.save();c.translate(8,8);c.scale(2,2);cut(c,0);c.drawRect(0,0,24,24,p);c.restore();
  check(b.getPixel(16,16)==Color.RED,"transformed outside");
  check(b.getPixel(32,32)==Color.TRANSPARENT,"transformed hole");
  b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);c=new Canvas(b);
  c.save();c.clipRect(0,0,16,32);c.clipOutRect(8,8,24,24);c.drawRect(0,0,32,32,p);c.restore();
  check(b.getPixel(4,16)==Color.RED,"intersect retained");
  check(b.getPixel(12,16)==Color.TRANSPARENT,"intersect hole");
  check(b.getPixel(20,2)==Color.TRANSPARENT,"intersect outside");
  b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);c=new Canvas(b);
  c.save();c.clipOutRect(0,0,32,32);c.drawRect(0,0,32,32,p);c.restore();
  check(b.getPixel(16,16)==Color.TRANSPARENT,"full subtraction pixels");
  for(int k=0;k<2;k++){
   b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);c=new Canvas(b);
   c.save();c.clipOutRect(24,8,k==0?24:8,24);c.drawRect(0,0,32,32,p);c.restore();
   check(b.getPixel(16,16)==(k==0?Color.RED:Color.TRANSPARENT),"degenerate/reversed rectangle "+k);
  }
  try(java.io.FileWriter w=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"))){w.write("{\"passed\":"+checks+",\"failed\":0}");}
  System.out.println("CLIP_OUT_RECT_PASS checks="+checks);android.util.Log.i("ClipOutRectProbe","CLIP_OUT_RECT_PASS checks="+checks);
  setContentView(new View(this){public void onDraw(Canvas c){
   Paint red=new Paint();red.setColor(Color.RED);c.drawColor(Color.WHITE);
   c.save();c.translate(20,20);c.scale(4,4);cut(c,0);c.drawRect(0,0,32,32,red);c.restore();
  }});
 }catch(Throwable t){t.printStackTrace();android.util.Log.e("ClipOutRectProbe","FAIL "+t);finish();}}
}
