/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.mediabrowse;
import android.service.media.MediaBrowserService;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.MediaDescription;
import android.media.session.MediaSession;
import android.os.*;
import java.util.*;
public class ProbeService extends MediaBrowserService {
    static ProbeService instance;
    static int loads;
    static Result<List<MediaItem>> held;
    static MediaItem item(String id) {
        Bundle b=new Bundle();b.putString("origin","fixture");
        return new MediaItem(new MediaDescription.Builder().setMediaId(id).setTitle("Title "+id)
            .setMediaUri(android.net.Uri.parse("file:///fixture/"+id)).setExtras(b).build(),MediaItem.FLAG_PLAYABLE);
    }
    public void onCreate(){super.onCreate();instance=this;new Handler().postDelayed(()->setSessionToken(new MediaSession(this,"probe").getSessionToken()),50);}
    public BrowserRoot onGetRoot(String pkg,int uid,Bundle hints){
        if(hints!=null&&hints.getBoolean("deny"))return null;
        return new BrowserRoot("root",null);
    }
    public void onLoadChildren(String id,Result<List<MediaItem>> result){
        loads++;
        if(id.equals("async")){result.detach();new Thread(()->result.sendResult(Collections.singletonList(item("async")))).start();return;}
        if(id.equals("error")){result.sendResult(null);return;}
        if(id.equals("hold")){held=result;result.detach();return;}
        ArrayList<MediaItem> items=new ArrayList<>();for(int i=0;i<5;i++)items.add(item(""+i));
        result.sendResult(items);
    }
    public void onLoadItem(String id,Result<MediaItem> result){result.sendResult(id.equals("missing")?null:item(id));}
}
