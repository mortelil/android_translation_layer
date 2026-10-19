package org.atl.tests.attachment;
import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.drawable.ColorDrawable;
import java.util.*;
public class AttachmentActivity extends Activity {
 int checks; boolean ready; Handler handler=new Handler(); Dialog dialog; Parent parent; Child child;
 List<String> events=new ArrayList<>();
 void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 void fail(Throwable t){t.printStackTrace();android.util.Log.e("AttachmentProbe","ATTACH_FAIL "+t);finish();System.exit(1);}
 class Parent extends LinearLayout {
  Parent(){super(AttachmentActivity.this);}
  protected void onAttachedToWindow(){super.onAttachedToWindow();check(ready,"attach after show returns");events.add("parent+");}
  protected void onDetachedFromWindow(){check(isAttachedToWindow(),"parent attached during detach");events.add("parent-");super.onDetachedFromWindow();}
 }
 class Child extends View {
  Child(){super(AttachmentActivity.this);}
  protected void onAttachedToWindow(){super.onAttachedToWindow();check(AttachmentActivity.this.parent.isAttachedToWindow(),"parent before child");events.add("child+");}
  protected void onDetachedFromWindow(){check(isAttachedToWindow(),"child attached during detach");events.add("child-");super.onDetachedFromWindow();}
  protected void onMeasure(int w,int h){check(isAttachedToWindow(),"attach before measurement");setMeasuredDimension(40,20);}
 }
 static class Sized extends ColorDrawable { Sized(){super(0xff123456);} public int getIntrinsicWidth(){return 50;}public int getIntrinsicHeight(){return 20;} }
 void measureImages(){
  long digest=1;int rows=0;
  int[] modes={View.MeasureSpec.EXACTLY,View.MeasureSpec.AT_MOST,View.MeasureSpec.UNSPECIFIED};
  for(boolean adjust:new boolean[]{false,true})for(int w:modes)for(int h:modes)for(int max:new int[]{29,100,Integer.MAX_VALUE}){
   ImageView image=new ImageView(this);image.setImageDrawable(new Sized());image.setPadding(3,5,7,11);image.setMaxWidth(max);image.setMaxHeight(max);image.setAdjustViewBounds(adjust);
   image.measure(View.MeasureSpec.makeMeasureSpec(100,w),View.MeasureSpec.makeMeasureSpec(80,h));
   digest=digest*31+image.getMeasuredWidth();digest=digest*31+image.getMeasuredHeight();rows++;
  }
  String result="IMAGE_MEASURE rows="+rows+" digest="+digest;System.out.println(result);android.util.Log.i("AttachmentProbe",result);
 }
 public void onCreate(Bundle b){super.onCreate(b);try{
  measureImages();dialog=new Dialog(this);parent=new Parent();child=new Child();parent.addView(child);dialog.setContentView(parent);
  check(!child.isAttachedToWindow(),"content construction is not attachment");
  dialog.show();check(events.isEmpty(),"show defers attachment");ready=true;
  handler.postDelayed(new Runnable(){public void run(){try{
   check(events.equals(Arrays.asList("parent+","child+")),"initial attach order "+events);
   dialog.hide();check(child.isAttachedToWindow(),"hide retains attachment");dialog.show();
   handler.postDelayed(new Runnable(){public void run(){try{
    check(events.size()==2,"hide/show does not reattach");
    parent.removeView(child);check(!child.isAttachedToWindow(),"removed child detached");parent.addView(child);
    check(events.equals(Arrays.asList("parent+","child+","child-","child+")),"child reattach order "+events);
    dialog.dismiss();
    handler.postDelayed(new Runnable(){public void run(){try{
     check(!child.isAttachedToWindow()&&!parent.isAttachedToWindow(),"dismiss detaches tree");
     check(events.equals(Arrays.asList("parent+","child+","child-","child+","child-","parent-")),"children detach first "+events);
     dialog.show();dialog.dismiss();dialog.show();
     handler.postDelayed(new Runnable(){public void run(){try{
      check(dialog.isShowing()&&child.isAttachedToWindow(),"show-dismiss-show survives stale work");
      String result="ATTACH_PASS checks="+checks;System.out.println(result);android.util.Log.i("AttachmentProbe",result);dialog.dismiss();finish();System.exit(0);
     }catch(Throwable t){fail(t);}}},100);
    }catch(Throwable t){fail(t);}}},100);
   }catch(Throwable t){fail(t);}}},100);
  }catch(Throwable t){fail(t);}}},300);
 }catch(Throwable t){fail(t);}}
}
