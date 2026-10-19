package org.atl.tests.nsdregistration;
import android.app.Activity;
import android.os.*;
import android.net.nsd.*;
import java.util.*;

public class RegistrationActivity extends Activity {
 NsdManager manager; Handler handler=new Handler();
 String original="ATL-registration-"+System.currentTimeMillis();
 final String type="_atl-reg._tcp";
 Set<String> registered=new HashSet<>(), resolved=new HashSet<>(), lost=new HashSet<>();
 Set<String> resolving=new HashSet<>();
 int removed, cancelledCallbacks, checks;
 boolean withdrawing, stopping, done;
 Reg a=new Reg(12345), b=new Reg(12346), cancel=new Reg(12347);
 void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 void fail(Throwable t){if(done)return;done=true;t.printStackTrace();System.out.println("NSD_REG_FAIL "+t);finish();System.exit(1);}
 NsdServiceInfo info(int port){
  NsdServiceInfo i=new NsdServiceInfo();i.setServiceName(original);i.setServiceType(type);i.setPort(port);
  i.setAttribute("utf8","æøå");i.setAttribute("empty",new byte[0]);i.setAttribute("bare",(byte[])null);
  i.setAttribute("binary",new byte[]{0,(byte)255,42});return i;
 }
 class Reg implements NsdManager.RegistrationListener {
  int port;Reg(int p){port=p;}
  public void onRegistrationFailed(NsdServiceInfo i,int error){fail(new AssertionError("register failed "+error));}
  public void onUnregistrationFailed(NsdServiceInfo i,int error){fail(new AssertionError("unregister failed "+error));}
  public void onServiceRegistered(NsdServiceInfo i){try{
   check(Looper.myLooper()==Looper.getMainLooper(),"main callback");
   if(this==cancel){cancelledCallbacks++;return;}
   check(i.getPort()==port,"registration snapshot port");
   check(i.getServiceName().startsWith(original),"name snapshot");
   registered.add(i.getServiceName());
   System.out.println("NSD_REGISTERED "+i.getServiceName()+" "+i.getPort());
  }catch(Throwable t){fail(t);}}
  public void onServiceUnregistered(NsdServiceInfo i){if(this==cancel)cancelledCallbacks+=10;else removed++;}
 }
 NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener(){
  public void onStartDiscoveryFailed(String t,int error){fail(new AssertionError("discover failed "+error));}
  public void onStopDiscoveryFailed(String t,int error){fail(new AssertionError("stop failed "+error));}
  public void onDiscoveryStarted(String t){}
  public void onDiscoveryStopped(String t){try{
   check(registered.size()==2,"collision renamed distinct instances");
   check(resolved.size()==2,"both advertisements resolved");
   check(removed==2,"two unregister callbacks");
   check(cancelledCallbacks==10,"immediate cancel suppresses stale registered callback");
   check(lost.containsAll(registered),"withdrawal observed by browser");
   done=true;System.out.println("NSD_REG_PASS checks="+checks);finish();System.exit(0);
  }catch(Throwable e){fail(e);}}
  public void onServiceLost(NsdServiceInfo i){
   lost.add(i.getServiceName());
   if(withdrawing && !stopping && lost.containsAll(registered)) { stopping=true; handler.postDelayed(new Runnable(){public void run(){manager.stopServiceDiscovery(discovery);}},500); }
  }
  public void onServiceFound(final NsdServiceInfo i){
   if(!i.getServiceName().startsWith(original)||withdrawing||!resolving.add(i.getServiceName()))return;
   manager.resolveService(i,new NsdManager.ResolveListener(){
    public void onResolveFailed(NsdServiceInfo x,int e){fail(new AssertionError("resolve failed "+e));}
    public void onServiceResolved(NsdServiceInfo x){try{
     check(x.getPort()==12345||x.getPort()==12346,"actual port");
     Map<String,byte[]> attrs=x.getAttributes();
     check(Arrays.equals(attrs.get("binary"),new byte[]{0,(byte)255,42}),"binary TXT");
     check(Arrays.equals(attrs.get("utf8"),"æøå".getBytes("UTF-8")),"UTF-8 TXT");
     check(attrs.containsKey("empty")&&attrs.get("empty")!=null&&attrs.get("empty").length==0,"empty TXT preserved");
     check(attrs.containsKey("bare")&&attrs.get("bare")==null,"bare TXT preserved");
     resolved.add(x.getServiceName());
     if(resolved.size()==2){withdrawing=true;manager.unregisterService(a);manager.unregisterService(b);}
    }catch(Throwable t){fail(t);}}
   });
  }
 };
 public void onCreate(Bundle state){super.onCreate(state);try{
  manager=(NsdManager)getSystemService("servicediscovery");
  if ("1".equals(System.getenv("ATL_TEST_NSD_UNAVAILABLE"))) {
   manager.registerService(info(12345),1,new NsdManager.RegistrationListener(){
    public void onServiceRegistered(NsdServiceInfo i){fail(new AssertionError("invented registration"));}
    public void onServiceUnregistered(NsdServiceInfo i){fail(new AssertionError("unexpected unregister"));}
    public void onUnregistrationFailed(NsdServiceInfo i,int e){fail(new AssertionError("unexpected failure"));}
    public void onRegistrationFailed(NsdServiceInfo i,int e){
     System.out.println("NSD_UNAVAILABLE_PASS");finish();System.exit(0);
    }
   });
   handler.postDelayed(new Runnable(){public void run(){fail(new AssertionError("failure callback missing"));}},5000);
   return;
  }
  NsdServiceInfo invalid=info(65536);
  try{manager.registerService(invalid,1,a);throw new AssertionError("oversized port accepted");}catch(IllegalArgumentException expected){checks++;}
  manager.discoverServices(type,1,discovery);
  NsdServiceInfo input=info(12345);manager.registerService(input,1,a);input.setPort(9);input.setServiceName("mutated");
  try{manager.registerService(info(12345),1,a);throw new AssertionError("duplicate listener accepted");}catch(IllegalArgumentException expected){checks++;}
  manager.registerService(info(12346),1,b);
  manager.registerService(info(12347),1,cancel);manager.unregisterService(cancel);
  handler.postDelayed(new Runnable(){public void run(){if(!done)fail(new AssertionError("timeout: registered="+registered+" resolved="+resolved+" lost="+lost+" removed="+removed));}},30000);
 }catch(Throwable t){fail(t);}}
}
