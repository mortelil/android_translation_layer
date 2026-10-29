// SPDX-License-Identifier: GPL-3.0-only
package org.atl.textselection;
import android.app.Activity;
import android.os.Bundle;
import android.text.*;
import android.graphics.*;
public class Probe extends Activity {
 int checks;
 void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
 Layout layout(String text){TextPaint p=new TextPaint();p.setTextSize(24);return new StaticLayout(text,p,300,Layout.Alignment.ALIGN_NORMAL,1,0,false);}
 RectF bounds(Path p){RectF r=new RectF();p.computeBounds(r,true);return r;}
 public void onCreate(Bundle state){super.onCreate(state);try{
  Layout l=layout("example.com");Path p=new Path();l.getSelectionPath(0,11,p);
  RectF full=bounds(p);check(full.width()>100&&full.height()>10,"visible full selection");
  l.getSelectionPath(11,0,p);RectF reversed=bounds(p);check(full.left==reversed.left&&full.right==reversed.right,"reversed selection");
  l.getSelectionPath(2,2,p);check(p.isEmpty(),"collapsed selection clears reused path");
  l.getSelectionPath(0,1,p);check(bounds(p).width()<full.width(),"partial selection replaces old path");
  Layout unicode=layout("A\ud83d\ude00B");unicode.getSelectionPath(3,4,p);
  check(bounds(p).left>20&&bounds(p).width()>0,"UTF16 offsets after supplementary character");
  Layout bidi=layout("abc \u05d0\u05d1\u05d2 xyz");bidi.getSelectionPath(4,7,p);check(bounds(p).width()>10,"RTL visual range");
  Layout multi=layout("abc\ndef\nghi");multi.getSelectionPath(1,10,p);
  RectF m=bounds(p);check(m.right==300&&m.top==0&&m.bottom==multi.getLineBottom(2),"multiline extent");
  // Record draw order and canvas translation without depending on text rasterization.
  final StringBuilder calls=new StringBuilder();
  Layout recorder=new StaticLayout("x",new TextPaint(),100,Layout.Alignment.ALIGN_NORMAL,1,0,false){
   public void draw(Canvas c){calls.append("text;");}
  };
  Canvas c=new Canvas(Bitmap.createBitmap(300,100,Bitmap.Config.ARGB_8888)){public void drawPath(Path path,Paint paint){calls.append("highlight;");}};
  Paint color=new Paint();color.setColor(Color.GREEN);
  recorder.getSelectionPath(0,1,p);int saved=c.getSaveCount();recorder.draw(c,p,color,7);
  check(calls.toString().equals("highlight;text;"),"highlight before text");
  check(c.getSaveCount()==saved,"canvas restored");
  calls.setLength(0);recorder.draw(c,null,color,7);check(calls.toString().equals("text;"),"null highlight");
  Bitmap bitmap=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);Canvas pixels=new Canvas(bitmap);
  Path square=new Path();square.addRect(0,0,10,10,Path.Direction.CW);
  recorder.draw(pixels,square,color,7);
  check(bitmap.getPixel(3,8)==Color.GREEN&&bitmap.getPixel(3,3)==0,"vertical highlight offset");
  pixels.drawRect(20,0,25,5,color);check(bitmap.getPixel(22,2)==Color.GREEN,"translation restored");
  System.out.println("TEXT_SELECTION PASS checks="+checks);finish();System.exit(0);
 }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
