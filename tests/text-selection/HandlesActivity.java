// SPDX-License-Identifier: GPL-3.0-only
package org.atl.textselection;
import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
public class HandlesActivity extends Activity {
 public void onCreate(Bundle b){super.onCreate(b);
  FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.WHITE);setContentView(root);
  new Handler().postDelayed(new Runnable(){public void run(){
   final View marker=new View(HandlesActivity.this);marker.setBackgroundColor(Color.MAGENTA);
   final WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
   final WindowManager.LayoutParams p=new WindowManager.LayoutParams();p.width=40;p.height=40;p.x=100;p.y=160;p.type=1002;
   wm.addView(marker,p);
   new Handler().postDelayed(new Runnable(){public void run(){p.x=200;wm.updateViewLayout(marker,p);}},1500);
   new Handler().postDelayed(new Runnable(){public void run(){wm.removeView(marker);System.out.println("HANDLES REMOVE PASS");finish();System.exit(0);}},30000);
  }},400);
 }
}
