/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.windowparams;
import android.app.Activity;
import android.os.Binder;
import android.os.Bundle;
import android.view.WindowManager.LayoutParams;
public class Probe extends Activity {
 private static void check(boolean ok,String s) { if(!ok) throw new AssertionError(s); }
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  try {
   LayoutParams defaults=new LayoutParams();
   check(defaults.width==-1&&defaults.height==-1&&defaults.type==2&&defaults.format==-1&&defaults.alpha==1f,"default fields");
   LayoutParams a=new LayoutParams(100,200,7,8,-3);
   check(a.width==100&&a.height==200&&a.type==7&&a.flags==8&&a.format==-3,"constructor arguments");
   LayoutParams b=new LayoutParams();
   b.copyFrom(a);check(b.copyFrom(a)==0,"idempotent copy");
   a.x=9;a.y=10;a.gravity=17;a.layoutInDisplayCutoutMode=1;
   check(b.copyFrom(a)==1&&b.x==9&&b.y==10&&b.gravity==17&&b.layoutInDisplayCutoutMode==1,"geometry flags");
   a.preferredDisplayModeId=5;
   check(b.copyFrom(a)==(1<<23)&&b.preferredDisplayModeId==5,"display mode copied");
   a.flags|=0x04000000;
   check(b.copyFrom(a)==((1<<2)|(1<<19)),"translucent flags");
   a.alpha=.5f;a.screenBrightness=.7f;a.rotationAnimation=2;a.softInputMode=4;a.windowAnimations=13;
   check(b.copyFrom(a)==((1<<7)|(1<<11)|(1<<12)|(1<<9)|(1<<4)),"remaining value flags");
   Binder original=new Binder();b.token=original;b.packageName="original";a.token=new Binder();a.packageName="source";
   check(b.copyFrom(a)==0&&b.token==original&&b.packageName.equals("original"),"existing identity retained");
   LayoutParams empty=new LayoutParams();empty.copyFrom(a);
   check(empty.token==a.token&&empty.packageName.equals("source"),"missing identity filled");
   StringBuilder title=new StringBuilder("first");a.setTitle(title);title.append("mutated");
   check(a.getTitle().toString().equals("first"),"title snapshots mutable text");
   check(b.copyFrom(a)==(1<<6)&&b.getTitle().toString().equals("first"),"title copied");
   a.setTitle(null);check(a.getTitle().length()==0&&b.copyFrom(a)==(1<<6)&&b.getTitle().length()==0,"explicit title clearing");
   check(b.copyFrom(b)==0,"self copy");
   getFilesDir().mkdirs();
   try (java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))) {
    out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));
   }
   System.out.println("WINDOW_PARAMS PASS");
  }catch(Throwable t){System.out.println("WINDOW_PARAMS FAIL "+t);t.printStackTrace();}
  // Keep the process alive for the probe runner to collect its result.
 }
}
