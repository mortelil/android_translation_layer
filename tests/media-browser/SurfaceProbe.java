/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.mediabrowse;
import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.FrameLayout;
public class SurfaceProbe extends Activity {
    Handler handler=new Handler();int created,changed,destroyed,removedCalls,phase;boolean done;SurfaceView view;FrameLayout root;
    void recordPass(){try{getFilesDir().mkdirs();java.io.FileWriter out=new java.io.FileWriter(new java.io.File(getFilesDir(),"results.json"));out.write("{\"status\":\"PASS\"}");out.close();}catch(java.io.IOException e){throw new RuntimeException(e);}}
    void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    boolean failed;
    void run(Runnable r){if(failed)return;try{r.run();}catch(Throwable t){failed=true;done=true;t.printStackTrace();System.out.println("SURFACE_LIFECYCLE FAIL "+t);}}
    public void onCreate(Bundle b){super.onCreate(b);run(()->{
        view=new SurfaceView(this);root=new FrameLayout(this);
        check(!view.getHolder().getSurface().isValid(),"not valid before attached");
        SurfaceHolder.Callback removed=new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder h){removedCalls++;}
            public void surfaceChanged(SurfaceHolder h,int f,int w,int hgt){removedCalls++;}
            public void surfaceDestroyed(SurfaceHolder h){removedCalls++;}
        };
        view.getHolder().addCallback(removed);view.getHolder().removeCallback(removed);
        SurfaceHolder.Callback callback=new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder h){run(()->{created++;check(h.isCreating(),"creating flag");check(h.getSurface().isValid(),"created valid");});}
            public void surfaceChanged(SurfaceHolder h,int f,int w,int hgt){run(()->{
                changed++;check(w>0&&hgt>0,"positive dimensions");check(h.getSurfaceFrame().width()==w,"frame width");
                if(phase==0){phase=1;handler.post(()->run(()->{check(created==1,"one initial create");check(removedCalls==0,"removed listener silent");root.removeView(view);}));}
                else if(phase==2){phase=3;handler.post(()->run(()->{check(created==2&&destroyed==1,"recreation order");check(removedCalls==0,"removed listener remains silent");recordPass();done=true;System.out.println("SURFACE_LIFECYCLE PASS");}));}
            });}
            public void surfaceDestroyed(SurfaceHolder h){run(()->{
                destroyed++;
                if(phase==1)handler.post(()->run(()->{check(destroyed==1,"one destroy");check(!h.getSurface().isValid(),"destroyed invalid");phase=2;root.addView(view,new FrameLayout.LayoutParams(240,120));}));
            });}
        };
        view.getHolder().addCallback(callback);view.getHolder().addCallback(callback);
        root.addView(view,new FrameLayout.LayoutParams(200,100));setContentView(root);
        handler.postDelayed(()->{if(!done)run(()->{throw new AssertionError("timeout: "+created+"/"+changed+"/"+destroyed);});},8000);
    });}
}
