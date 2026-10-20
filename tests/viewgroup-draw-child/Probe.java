// SPDX-License-Identifier: GPL-3.0-only
package org.atl.drawchild;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import java.util.ArrayList;
public class Probe extends Activity {
 int checks;
 void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 class Group extends ViewGroup {
  ArrayList<Integer> seen=new ArrayList<>();
  boolean reverse;
  Group(){super(Probe.this);}
  void reverseOrder(boolean value){reverse=value;setChildrenDrawingOrderEnabled(value);}
  protected int getChildDrawingOrder(int n,int i){return reverse?n-1-i:i;}
  protected void onLayout(boolean changed,int l,int t,int r,int b){}
  public boolean drawChild(Canvas c,View v,long time){
   seen.add(v.getId());
   // An override must be able to replace the child's rendering entirely.
   Paint p=new Paint();p.setColor(v.getId()==1?Color.RED:Color.BLUE);
   c.drawRect(0,0,16,16,p);return false;
  }
  Bitmap render(){seen.clear();Bitmap b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);dispatchDraw(new Canvas(b));return b;}
 }
 public void onCreate(Bundle state){super.onCreate(state);try{
  Group g=new Group();g.layout(0,0,16,16);
  View a=new View(this),b=new View(this);a.setId(1);b.setId(2);
  g.addView(a,new ViewGroup.LayoutParams(16,16));g.addView(b,new ViewGroup.LayoutParams(16,16));
  Bitmap image=g.render();check(g.seen.toString().equals("[1, 2]"),"normal order and virtual callback");check(image.getPixel(8,8)==Color.BLUE,"override pixels");
  b.setVisibility(View.INVISIBLE);g.render();check(g.seen.toString().equals("[1]"),"invisible omitted");
  b.setVisibility(View.VISIBLE);a.setElevation(5);image=g.render();check(g.seen.toString().equals("[2, 1]"),"Z order");check(image.getPixel(8,8)==Color.RED,"Z pixels");
  a.setElevation(0);g.reverseOrder(true);image=g.render();check(g.seen.toString().equals("[2, 1]"),"custom drawing order");check(image.getPixel(8,8)==Color.RED,"custom order pixels");
  g.reverseOrder(false);g.bringChildToFront(a);g.render();check(g.seen.toString().equals("[2, 1]"),"bring to front");
  try(java.io.FileWriter w=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"))){w.write("{\"passed\":"+checks+",\"failed\":0}");}
  System.out.println("DRAW_CHILD_PASS checks="+checks);android.util.Log.i("DrawChildProbe","DRAW_CHILD_PASS checks="+checks);
  // A descendant's bounds can change without resizing the host window.
  // Observe actual native drawing, not a manually invoked Canvas draw.
  final boolean[] presented = {false, false};
  final View child = new View(this) {
   public void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    presented[0] = true;
    if (getHeight() == 160) presented[1] = true;
   }
  };
  child.setWillNotDraw(false);
  android.widget.FrameLayout host = new android.widget.FrameLayout(this) {
   public boolean drawChild(Canvas canvas,View view,long time) {
    return super.drawChild(canvas,view,time);
   }
  };
  host.addView(child,new android.widget.FrameLayout.LayoutParams(160,40));
  setContentView(host);
  new android.os.Handler().postDelayed(new Runnable(){public void run(){
   try {
    check(presented[0],"initial native frame");
    child.layout(0,0,160,160);
    new android.os.Handler().postDelayed(new Runnable(){public void run(){try{
     check(presented[1],"changed child bounds redraw custom ancestor without window resize");
     try(java.io.FileWriter w=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"))){w.write("{\"passed\":"+checks+",\"failed\":0}");}
     System.out.println("DRAW_CHILD_NATIVE_PASS checks="+checks);
     System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}},500);
   }catch(Throwable t){t.printStackTrace();System.exit(1);}
  }},500);
 }catch(Throwable t){t.printStackTrace();android.util.Log.e("DrawChildProbe","FAIL "+t);finish();}}
}
