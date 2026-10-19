// SPDX-License-Identifier: GPL-3.0-only
package org.atl.popupcontent;
import android.app.Activity;import android.os.Bundle;import android.widget.PopupWindow;import android.view.View;
public class Probe extends Activity {
 int checks;void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public void onCreate(Bundle state){super.onCreate(state);int result=0;try{
 View content=new View(this);PopupWindow popup=new PopupWindow(content);
 check(popup.getContentView()==content,"content retained");
 check(popup.getWidth()==0 && popup.getHeight()==0,"zero initial size");
 check(!popup.isShowing(),"construction does not show");
 popup.setWidth(120);popup.setHeight(80);
 check(popup.getWidth()==120 && popup.getHeight()==80,"explicit dimensions");
 popup.setWidth(-1);popup.setHeight(-2);check(popup.getWidth()==-1 && popup.getHeight()==-2,"layout sentinel metadata");
 PopupWindow empty=new PopupWindow((View)null);check(empty.getContentView()==null,"null content allowed");
 check(!empty.isShowing(),"empty popup hidden");
 System.out.println("POPUP_CONTENT_PASS checks="+checks);android.util.Log.i("PopupContentProbe","POPUP_CONTENT_PASS checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("PopupContentProbe","FAIL "+t);}finish();System.exit(result);}
}
