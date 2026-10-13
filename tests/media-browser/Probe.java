/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.mediabrowse;
import android.app.Activity;
import android.content.ComponentName;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.MediaDescription;
import android.os.*;
import java.util.*;
public class Probe extends Activity {
    final Handler h=new Handler();MediaBrowser browser;boolean done,errorSeen,missingSeen,asyncSeen;int checks,children;
    void recordPass(){try{getFilesDir().mkdirs();java.io.FileWriter out=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"));out.write("{\"status\":\"PASS\"}");out.close();}catch(java.io.IOException e){throw new RuntimeException(e);}}
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    void later(Runnable r){h.postDelayed(()->run(r),150);}
    boolean failed;
    void run(Runnable r){if(failed)return;try{r.run();}catch(Throwable t){failed=true;done=true;t.printStackTrace();System.out.println("MEDIA_BROWSER FAIL "+t);}}
    void parcel(){
        Binder first=new Binder(),second=new Binder();
        check(!first.equals(second),"Binder identity");
        IBinder.DeathRecipient recipient=()->{throw new AssertionError("local Binder death callback");};
        first.linkToDeath(recipient,0);check(first.unlinkToDeath(recipient,0),"local unlink");
        check(first.isBinderAlive()&&first.pingBinder(),"local Binder alive");

        try {
            Class<?> f=android.media.AudioFormat.class;
            check(f.getField("CHANNEL_OUT_7POINT1_SURROUND").getInt(null)==0x18fc,"7.1 mask");
            check(f.getField("CHANNEL_IN_MONO").getInt(null)==0x10,"input mono");
            check(f.getField("ENCODING_PCM_32BIT").getInt(null)==22,"PCM32 encoding");
            check(f.getField("ENCODING_PCM_24BIT_PACKED").getInt(null)==21,"PCM24 encoding");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }

        android.graphics.Bitmap icon=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);
        icon.eraseColor(0xff12ab34);
        MediaDescription d=new MediaDescription.Builder().setMediaId("42").setTitle("title")
            .setIconBitmap(icon).setIconUri(android.net.Uri.parse("content://test/icon%20name?q=1#x"))
            .setMediaUri(ProbeService.item("42").getDescription().getMediaUri())
            .setExtras(ProbeService.item("42").getDescription().getExtras()).build();Parcel p=Parcel.obtain();
        d.writeToParcel(p,0);p.setDataPosition(0);MediaDescription copy=MediaDescription.CREATOR.createFromParcel(p);p.recycle();
        check("42".equals(copy.getMediaId()),"id parcel");check(copy.getIconBitmap().getPixel(1,1)==0xff12ab34,"bitmap pixels parcel");check(d.getIconUri().equals(copy.getIconUri()),"icon uri parcel");check(d.getMediaUri().equals(copy.getMediaUri()),"uri parcel");
        check("fixture".equals(copy.getExtras().getString("origin")),"extras parcel");
        android.widget.PopupMenu popup=new android.widget.PopupMenu(this,new android.view.View(this));
        android.view.MenuItem menu=popup.getMenu().add(0,1,0,"fixture");check(!menu.isActionViewExpanded(),"initial action state");check(!menu.collapseActionView(),"nothing to collapse");
    }
    public void onCreate(Bundle state){super.onCreate(state);run(()->{
        parcel();
        android.media.MediaRouter.SimpleCallback routeCallback = new android.media.MediaRouter.SimpleCallback();
        routeCallback.onRouteAdded(null,null); routeCallback.onRouteRemoved(null,null);
        routeCallback.onRouteSelected(null,0,null); routeCallback.onRouteUnselected(null,0,null);
        if ("org.atl.TEST".equals(getIntent().getAction())) {
            check("video/mp4".equals(getIntent().getType()),"launcher MIME");
            check("file:///fixture/video.mp4".equals(getIntent().getData().toString()),"launcher URI");
            System.out.println("TYPED_LAUNCH PASS");
        }
        h.postDelayed(()->{if(!done)run(()->{throw new AssertionError("timeout");});},12000);
        browser=new MediaBrowser(this,new ComponentName(this,ProbeService.class),new MediaBrowser.ConnectionCallback(){
            public void onConnected(){run(()->connected());}
            public void onConnectionFailed(){run(()->{throw new AssertionError("connection failed");});}
        },null);browser.connect();
    });}
    void connected(){
        check(browser.isConnected(),"connected");check("root".equals(browser.getRoot()),"root");
        Bundle options=new Bundle();options.putInt(MediaBrowser.EXTRA_PAGE,1);options.putInt(MediaBrowser.EXTRA_PAGE_SIZE,2);
        browser.subscribe("root",options,new MediaBrowser.SubscriptionCallback(){
            public void onChildrenLoaded(String id,List<MediaItem> items,Bundle opts){run(()->{
                check(items.size()==2&&items.get(0).getMediaId().equals("2"),"paging");children++;
            });}
        });
        browser.subscribe("async",new MediaBrowser.SubscriptionCallback(){public void onChildrenLoaded(String id,List<MediaItem> items){run(()->{check(Looper.myLooper()==Looper.getMainLooper(),"worker result on browser thread");check(items.size()==1,"async result");asyncSeen=true;});}});
        if ("1".equals(getIntent().getStringExtra("overflow"))) {
        Bundle huge=new Bundle();huge.putInt(MediaBrowser.EXTRA_PAGE,Integer.MAX_VALUE);huge.putInt(MediaBrowser.EXTRA_PAGE_SIZE,Integer.MAX_VALUE);
        browser.subscribe("root",huge,new MediaBrowser.SubscriptionCallback(){public void onChildrenLoaded(String id,List<MediaItem> items,Bundle options){run(()->check(items.isEmpty(),"overflow page empty"));}});
        }
        browser.subscribe("error",new MediaBrowser.SubscriptionCallback(){public void onError(String id){errorSeen=true;}});
        browser.getItem("missing",new MediaBrowser.ItemCallback(){public void onItemLoaded(MediaItem item){run(()->{check(item==null,"null item is successful result");missingSeen=true;});}});
        later(()->{check(children==1,"subscription callback once");check(errorSeen,"null children reports error");check(missingSeen,"null item callback delivered");check(asyncSeen,"worker result delivered");ProbeService.instance.notifyChildrenChanged("root");later(()->{
            check(children==2,"notify reload");browser.unsubscribe("root");ProbeService.instance.notifyChildrenChanged("root");later(()->{
                check(children==2,"unsubscribe suppresses reload");browser.subscribe("hold",new MediaBrowser.SubscriptionCallback(){public void onChildrenLoaded(String id,List<MediaItem> items){run(()->{throw new AssertionError("late disconnected callback");});}});
                later(()->{check(ProbeService.held!=null,"detach");browser.disconnect();later(()->{
                    ProbeService.held.sendResult(Collections.emptyList());
                    boolean duplicate=false;try{ProbeService.held.sendResult(Collections.emptyList());}catch(IllegalStateException expected){duplicate=true;}check(duplicate,"duplicate result rejected");
                    later(()->{
                        check(!browser.isConnected(),"disconnect");Bundle deny=new Bundle();deny.putBoolean("deny",true);
                        MediaBrowser rejected=new MediaBrowser(Probe.this,new ComponentName(Probe.this,ProbeService.class),new MediaBrowser.ConnectionCallback(){
                            public void onConnected(){run(()->{throw new AssertionError("rejected client connected");});}
                            public void onConnectionFailed(){run(()->{recordPass();done=true;System.out.println("MEDIA_BROWSER PASS checks="+checks);});}
                        },deny);rejected.connect();
                    });
                });});
            });
        });});
    }
}
