// SPDX-License-Identifier: GPL-3.0-only
package org.atl.resolution;
import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.*;

public class Probe extends Activity {
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static Intent query(String action,String uri,String type){
  return new Intent(action).setDataAndType(uri==null?null:Uri.parse(uri),type);
 }
 static void names(PackageManager pm,Intent i,int flags,String... expected){
  List<ResolveInfo> rows=pm.queryIntentContentProviders(i,flags);
  check(rows!=null,"non-null list");
  List<String> got=new ArrayList<>();
  for(ResolveInfo r:rows)got.add(r.providerInfo.name.substring(r.providerInfo.name.lastIndexOf('$')+1));
  check(Arrays.asList(expected).equals(got),"check "+checks+" providers expected "+Arrays.toString(expected)+" got "+got);
 }
 public void onCreate(Bundle state){
  super.onCreate(state);
  try {
   PackageManager pm=getPackageManager();
   String pkg=getPackageName();
   Intent i=query("org.atl.RESOLVE","https://example.test:8443/ok/one","image/png").setPackage(pkg);
   names(pm,i,0,"High","Low");
   List<ResolveInfo> infos=pm.queryIntentContentProviders(i,PackageManager.GET_RESOLVED_FILTER);
   check(infos.get(0).priority==20,"priority");
   check(infos.get(0).filter!=null && infos.get(0).filter.getPriority()==20,"parsed filter priority");
   check(infos.get(0).filter.countDataAuthorities()==1,"parsed authority");
   check(infos.get(0).filter.countDataPaths()==1,"parsed path");
   check(infos.get(0).match==0x608000,"match score");
   check(pm.queryIntentContentProviders(i,0).get(0).filter==null,"optional filter omitted");
   names(pm,i,PackageManager.MATCH_DEFAULT_ONLY,"High");
   names(pm,i,PackageManager.MATCH_DISABLED_COMPONENTS,"Disabled","High","Low");
   names(pm,query("org.atl.RESOLVE","https://wrong.test:8443/ok/one","image/png").setPackage(pkg),0);
   names(pm,query("org.atl.RESOLVE","https://example.test/ok/one","image/png").setPackage(pkg),0);
   names(pm,query("org.atl.RESOLVE","https://example.test:8443/no","image/png").setPackage(pkg),0);
   names(pm,query("org.atl.RESOLVE","https://example.test:8443/ok/one","text/plain").setPackage(pkg),0);
   names(pm,new Intent(i).addCategory("undeclared"),0);
   names(pm,new Intent(i).setPackage("org.atl.missing"),0);
   names(pm,new Intent("wrong").setComponent(new ComponentName(pkg,Probe.High.class.getName())),0,"High");
   check(pm.queryIntentContentProviders(new Intent().setComponent(new ComponentName(pkg,Probe.High.class.getName())),0).get(0).priority==0,"explicit priority");
   names(pm,new Intent().setComponent(new ComponentName(pkg,Probe.Disabled.class.getName())),0);
   names(pm,new Intent().setComponent(new ComponentName(pkg,Probe.Disabled.class.getName())),PackageManager.MATCH_DISABLED_COMPONENTS,"Disabled");
   Intent select=new Intent("wrong");select.setSelector(i);
   names(pm,select,0); // AOSP resolves MIME before substituting the selector.
   select.setType("image/png");
   names(pm,select,0,"High","Low");
   names(pm,query("org.atl.SSP","mailto:user@example.test",null).setPackage(pkg),0,"Ssp");
   names(pm,query("org.atl.SSP","mailto:other@example.test",null).setPackage(pkg),0);
   names(pm,query("org.atl.GLOB","https://example.test/music/song.mp3",null).setPackage(pkg),0,"Glob");
   names(pm,query("org.atl.GLOB","https://example.test/images/photo",null).setPackage(pkg),0);
   names(pm,query("org.atl.EMPTY","https://example.test",null).setPackage(pkg),0,"Empty");
   names(pm,query("org.atl.EMPTY","https://example.test/a",null).setPackage(pkg),0);
   names(pm,new Intent("org.atl.REMOTE").setPackage("org.atl.resolutionowner"),0,"Remote");
   names(pm,new Intent("org.atl.REMOTE"),0,"Remote");
   names(pm,query("org.atl.TYPE","content://org.atl.resolutionprobe.High/item",null).setPackage(pkg),0,"Mime");
   Intent duplicate=new Intent("org.atl.DUP").setPackage(pkg);
   names(pm,duplicate,0,"Duplicate");
   check(pm.queryIntentContentProviders(duplicate,0).get(0).priority==3,"first matching filter retained");
   String result="RESOLUTION_PASS checks="+checks;
   System.out.println(result);android.util.Log.i("ResolutionProbe",result);finish();System.exit(0);
  }catch(Throwable e){ e.printStackTrace();android.util.Log.e("ResolutionProbe","RESOLUTION_FAIL "+e);finish();System.exit(1);}
 }
 public static class Base extends ContentProvider {
  public boolean onCreate(){return true;}
  public android.os.ParcelFileDescriptor openFile(Uri u,String m) throws java.io.FileNotFoundException {throw new java.io.FileNotFoundException();}
  public String getType(Uri u){return null;}
  public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
  public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
  public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
  public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 }
 public static class High extends Base { public String getType(Uri uri){return "image/png";} }
 public static class Mime extends Base {}
 public static class Low extends Base {}
 public static class Disabled extends Base {}
 public static class Ssp extends Base {}
 public static class Glob extends Base {}
 public static class Empty extends Base {}
 public static class Remote extends Base {}
 public static class Duplicate extends Base {}
}
