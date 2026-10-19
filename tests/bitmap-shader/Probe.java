// SPDX-License-Identifier: GPL-3.0-only
package org.atl.bitmapshader;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.View;

public class Probe extends Activity {
 int checks;
 void check(boolean ok, String label) { if(!ok) throw new AssertionError(label); checks++; }
 Bitmap source;
 Paint paint;
 BitmapShader shader;
 Bitmap draw(int shape) {
  Bitmap out=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
  Canvas c=new Canvas(out);
  if(shape==0) c.drawRect(0,0,32,32,paint);
  if(shape==1) c.drawRoundRect(0,0,32,32,8,8,paint);
  if(shape==2) c.drawCircle(16,16,16,paint);
  if(shape==3) { Path p=new Path();p.addCircle(16,16,16,Path.Direction.CW);c.drawPath(p,paint); }
  return out;
 }
 void mode(Shader.TileMode mode) { shader=new BitmapShader(source,mode,mode);paint.setShader(shader); }
 public void onCreate(Bundle state) { super.onCreate(state); try {
  source=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
  int[] pixels=new int[16];for(int y=0;y<4;y++)for(int x=0;x<4;x++)pixels[y*4+x]=y<2?(x<2?Color.RED:Color.GREEN):(x<2?Color.BLUE:Color.YELLOW);
  source.setPixels(pixels,0,4,0,0,4,4);paint=new Paint(0);
  mode(Shader.TileMode.CLAMP);Matrix m=new Matrix();m.setScale(8,8);shader.setLocalMatrix(m);m.reset();
  for(int shape=0;shape<4;shape++) {
   Bitmap b=draw(shape);check(b.getPixel(10,10)==Color.RED,"red shape "+shape);check(b.getPixel(22,10)==Color.GREEN,"green shape "+shape);check(b.getPixel(10,22)==Color.BLUE,"blue shape "+shape);check(b.getPixel(22,22)==Color.YELLOW,"yellow shape "+shape);
   if(shape>0)check(Color.alpha(b.getPixel(0,0))==0,"clipped corner "+shape);b.recycle();
  }
  shader.setLocalMatrix(null);Bitmap b=draw(0);check(b.getPixel(25,25)==Color.YELLOW,"clamped edge and reset");b.recycle();
  mode(Shader.TileMode.REPEAT);b=draw(0);check(b.getPixel(4,4)==Color.RED,"repeat origin");check(b.getPixel(7,7)==Color.YELLOW,"repeat end");b.recycle();
  mode(Shader.TileMode.MIRROR);b=draw(0);check(b.getPixel(4,4)==Color.YELLOW,"mirror origin");check(b.getPixel(7,7)==Color.RED,"mirror end");b.recycle();
  mode(Shader.TileMode.CLAMP);m.setScale(8,8);shader.setLocalMatrix(m);paint.setAlpha(128);b=draw(2);check(Math.abs(Color.alpha(b.getPixel(10,10))-128)<=1,"paint alpha");b.recycle();paint.setAlpha(255);
  m.setTranslate(10,10);shader.setLocalMatrix(m);b=draw(0);check(b.getPixel(0,0)==Color.RED,"negative clamp");check(b.getPixel(13,13)==Color.YELLOW,"translation");b.recycle();
  m.setScale(8,8);shader.setLocalMatrix(m);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);b=draw(2);check(Color.alpha(b.getPixel(16,16))==0,"stroke hollow");check(Color.alpha(b.getPixel(16,1))>0,"stroke visible");b.recycle();paint.setStyle(Paint.Style.FILL);
  paint.setFilterBitmap(true);check(paint.isFilterBitmap(),"filter on");b=draw(0);int blended=b.getPixel(15,10);check(Color.red(blended)>0 && Color.green(blended)>0,"bilinear samples");b.recycle();paint.setFilterBitmap(false);check(!paint.isFilterBitmap(),"filter off");
  source.eraseColor(Color.CYAN);b=draw(0);check(b.getPixel(10,10)==Color.CYAN,"mutable bitmap");b.recycle();source.setPixels(pixels,0,4,0,0,4,4);
  check(source.getPixel(3,3)==Color.YELLOW,"restored bitmap source");
  b=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);Canvas scaled=new Canvas(b);scaled.scale(5,5);scaled.drawRoundRect(0,0,32,32,8,8,paint);check(b.getPixel(110,110)==Color.YELLOW,"nested canvas scale");b.recycle();
  System.out.println("BITMAP_SHADER_PASS checks="+checks);android.util.Log.i("BitmapProbe","BITMAP_SHADER_PASS checks="+checks);
  setContentView(new View(this) { public void onDraw(Canvas c) { c.drawColor(Color.WHITE);c.save();c.translate(30,30);c.scale(5,5);c.drawRoundRect(0,0,32,32,8,8,paint);c.translate(40,0);c.drawCircle(16,16,16,paint);c.restore(); } });
 } catch(Throwable t) {t.printStackTrace();android.util.Log.e("BitmapProbe","FAIL "+t);finish();} }
}
