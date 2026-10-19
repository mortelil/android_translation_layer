// SPDX-License-Identifier: GPL-3.0-only
package org.atl.components;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import java.util.*;

public class Probe extends Activity {
 static int checks, started, shown;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static Intent request(){return new Intent("org.atl.MATCH").setDataAndType(Uri.parse("https://example.test/ok/item"),"image/png").setPackage("org.atl.componentprobe");}
 static List<ResolveInfo> query(PackageManager pm,Intent i,int flags,boolean services){
  return services?pm.queryIntentServices(i,flags):pm.queryIntentActivities(i,flags);
 }
 static void names(PackageManager pm,Intent i,int flags,boolean services,String... want){
  List<String> got=new ArrayList<>();
  for(ResolveInfo r:query(pm,i,flags,services)){
   String n=services?r.serviceInfo.name:r.activityInfo.name;
   got.add(n.substring(n.lastIndexOf('$')+1));
  }
  check(got.equals(Arrays.asList(want)),"check "+checks+" service="+services+" expected "+Arrays.toString(want)+" got "+got);
 }
 void contracts(boolean services){
  PackageManager pm=getPackageManager();
  String a=services?"SA":"A", b=services?"SB":"B", disabled=services?"SD":"D";
  names(pm,new Intent().setPackage(getPackageName()),0,services);
  names(pm,request(),0,services,a,b);
  names(pm,request(),PackageManager.MATCH_DEFAULT_ONLY,services,a);
  names(pm,request(),PackageManager.MATCH_DISABLED_COMPONENTS,services,disabled,a,b);
  names(pm,request().setDataAndType(Uri.parse("https://wrong.test/ok/item"),"image/png"),0,services);
  names(pm,request().setDataAndType(Uri.parse("https://example.test/wrong"),"image/png"),0,services);
  names(pm,request().setDataAndType(Uri.parse("https://example.test/ok/item"),"text/plain"),0,services);
  names(pm,request().addCategory("wrong.category"),0,services);
  names(pm,request().setAction("wrong.action"),0,services);
  names(pm,request().setPackage("org.atl.no.such.package"),0,services);
  Intent explicit=new Intent("wrong").setComponent(new ComponentName(getPackageName(),"org.atl.components.Probe$"+a));
  names(pm,explicit,0,services,a);
  ResolveInfo ri=query(pm,explicit,0,services).get(0);
  check(ri.priority==0,"explicit priority");
  ri=query(pm,request(),PackageManager.GET_RESOLVED_FILTER,services).get(0);
  check(ri.filter!=null && ri.match==0x608000,"matched filter and score");
  check(ri.priority==-2,"priority");
  names(pm,new Intent().setComponent(new ComponentName(getPackageName(),"org.atl.components.Probe$"+disabled)),0,services);
  Intent selector=new Intent("wrong");selector.setSelector(request());
  names(pm,selector,0,services);
  selector.setType("image/png");names(pm,selector,0,services,a,b);
  Intent remote=new Intent("org.atl.REMOTE").setPackage("org.atl.componentowner");
  names(pm,remote,0,services,services?"RemoteService":"RemoteActivity");
  names(pm,new Intent("org.atl.REMOTE"),0,services,services?"RemoteService":"RemoteActivity");
  names(pm,new Intent().setPackage("org.atl.componentowner"),0,services,services?"RemoteService":"RemoteActivity");
  names(pm,new Intent(),0,services);
  if(services)check(pm.resolveService(request(),0).serviceInfo.name.endsWith("$SA"),"resolveService top");
  else {
   check(pm.resolveActivity(request(),0).activityInfo.name.endsWith("$A"),"resolveActivity top");
   check(request().resolveActivity(pm).getClassName().endsWith("$A"),"Intent.resolveActivity");
   check(new Intent("missing").setPackage(getPackageName()).resolveActivity(pm)==null,"Intent.resolveActivity missing");
  }
 }
 public void onCreate(Bundle state){
  super.onCreate(state);
  try{
   contracts(false);contracts(true);
   Intent bad=request().setDataAndType(Uri.parse("https://wrong.test/ok/item"),"image/png");
   check(startService(bad)==null,"wrong-data service must not start");
   check(startService(new Intent().setComponent(new ComponentName(getPackageName(),SD.class.getName())))==null,"disabled service must not start");
   ComponentName service=startService(request());
   check(service!=null && service.getClassName().endsWith("$SA"),"startService same result as query");
   startActivity(request());
   new Handler().postDelayed(new Runnable(){public void run(){
    try{
     check(started==1,"one matching service callback");
     check(shown==1,"one matching local activity, not desktop URI fallback");
     stopService(new Intent(Probe.this,SA.class));
     String result="COMPONENT_MATCH_PASS checks="+checks;
     System.out.println(result);android.util.Log.i("ComponentProbe",result);
     finish();System.exit(0);
    }catch(Throwable t){fail(t);}
   }},1500);
  }catch(Throwable t){fail(t);}
 }
 void fail(Throwable t){t.printStackTrace();android.util.Log.e("ComponentProbe","COMPONENT_MATCH_FAIL "+t);finish();System.exit(1);}
 public static class A extends Activity{
  public void onCreate(Bundle b){super.onCreate(b);shown++;finish();}
 }
 public static class B extends A {}
 public static class D extends A {}
 public static class RemoteActivity extends A {}
 public static class SA extends Service{
  public IBinder onBind(Intent i){return null;}
  public int onStartCommand(Intent i,int flags,int id){started++;return START_NOT_STICKY;}
 }
 public static class SB extends SA {}
 public static class SD extends SA {}
 public static class RemoteService extends SA {}
}
