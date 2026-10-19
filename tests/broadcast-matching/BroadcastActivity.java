package org.atl.tests.broadcast;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
public class BroadcastActivity extends Activity {
 static final Map<String,Integer> received=new HashMap<>();
 static void record(String who,Intent i){String key=who+":"+i.getStringExtra("case");Integer n=received.get(key);received.put(key,n==null?1:n+1);}
 public static class ManifestReceiver extends BroadcastReceiver { public void onReceive(Context c,Intent i){record("manifest",i);} }
 public static class DisabledReceiver extends BroadcastReceiver { public void onReceive(Context c,Intent i){throw new AssertionError("disabled receiver");} }
 public static class Types extends ContentProvider {
  public ParcelFileDescriptor openFile(Uri u,String mode) throws java.io.FileNotFoundException { throw new java.io.FileNotFoundException(); }
  public boolean onCreate(){return true;}public String getType(Uri u){return "image/png";}
  public android.database.Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
  public Uri insert(Uri u,ContentValues v){return null;}public int delete(Uri u,String s,String[] a){return 0;}public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
 }
 Intent msg(String action,String test){return new Intent(action).setPackage(getPackageName()).putExtra("case",test);}
 Intent image(String test){return msg("org.atl.BROADCAST",test).setDataAndType(Uri.parse("https://example.test/ok/item"),"image/png");}
 public void onCreate(Bundle b){super.onCreate(b);try{
  sendBroadcast(image("good"));
  sendBroadcast(image("wrongtype").setType("text/plain"));
  sendBroadcast(image("category").addCategory("missing"));
  sendBroadcast(image("foreign").setPackage("org.atl.unknown"));
  sendBroadcast(msg("wrong","explicit").setComponent(new ComponentName(this,ManifestReceiver.class)));
  sendBroadcast(msg("wrong","disabled").setComponent(new ComponentName(this,DisabledReceiver.class)));
  final BroadcastReceiver one=new BroadcastReceiver(){public void onReceive(Context c,Intent i){record("one",i);}};
  final BroadcastReceiver two=new BroadcastReceiver(){public void onReceive(Context c,Intent i){record("two",i);}};
  IntentFilter f=new IntentFilter("org.atl.DYNAMIC"); f.addDataType("image/*");
  registerReceiver(one,f);registerReceiver(two,f);registerReceiver(one,f);
  f.addAction("org.atl.MUTATED");
  sendBroadcast(msg("org.atl.DYNAMIC","dynamic").setType("image/png"));
  sendBroadcast(msg("org.atl.MUTATED","mutation").setType("image/png"));
  sendBroadcast(msg("org.atl.DYNAMIC","wrongdynamic").setType("text/plain"));
  sendBroadcast(msg("org.atl.DYNAMIC","content").setData(Uri.parse("content://org.atl.tests.broadcast.types/item")));
  sendBroadcast(msg("org.atl.DYNAMIC","explicitdynamic").setComponent(new ComponentName(this,ManifestReceiver.class)));
  final BroadcastReceiver self=new BroadcastReceiver(){public void onReceive(Context c,Intent i){record("self",i);c.unregisterReceiver(this);}};
  registerReceiver(self,new IntentFilter("org.atl.SELF"));sendBroadcast(msg("org.atl.SELF","self"));
  new Handler().postDelayed(new Runnable(){public void run(){try{
   Map<String,Integer> expected=new HashMap<>();
   for(String key:new String[]{"manifest:good","manifest:explicit","manifest:explicitdynamic","one:dynamic","two:dynamic","one:content","two:content","self:self"})expected.put(key,1);
   if(!received.equals(expected))throw new AssertionError("expected "+expected+" got "+received);
   unregisterReceiver(one);unregisterReceiver(two);
   android.util.Log.i("BroadcastProbe","BROADCAST_PASS deliveries=8");System.out.println("BROADCAST_PASS deliveries=8");finish();System.exit(0);
  }catch(Throwable t){fail(t);}}},2000);
 }catch(Throwable t){fail(t);}}
 void fail(Throwable t){t.printStackTrace();android.util.Log.e("BroadcastProbe","BROADCAST_FAIL "+t);finish();System.exit(1);}
}
