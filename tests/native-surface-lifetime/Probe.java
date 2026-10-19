package org.atl.nativesurface;
import android.app.Activity;
import android.os.*;
import android.graphics.SurfaceTexture;
import android.view.*;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
public class Probe extends Activity {
    static { System.loadLibrary("surfaceprobe"); }
    static native long take(Surface s);
    static native Surface wrap(long p);
    static native int refs(long p);
    static native void drop(long p, boolean worker);
    static native void threads(long p);
    static native boolean post(long p);
    static native int width(long p);
    Handler h = new Handler();
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    void fail(Throwable t){t.printStackTrace();System.out.println("NATIVE_SURFACE_FAIL "+t);System.exit(1);}
    long retained;
    WeakReference<Surface> forgotten;
    WeakReference<SurfaceTexture> forgottenConsumer;
    void textureChecks() {
        SurfaceTexture t=new SurfaceTexture(false);t.setDefaultBufferSize(8,8);
        Surface original=new Surface(t);
        long p=take(original);check(p!=0 && refs(p)==1,"native acquire");
        Surface s=wrap(p);check(refs(p)==2 && s.isValid(),"wrapper owns reference");
        long q=take(s);check(q==p && refs(p)==3,"round-trip same native handle");
        drop(q,false);original.release();check(take(original)==0,"released source");
        check(post(p),"producer accepts frame");
        threads(p);check(refs(p)==2,"concurrent balanced acquire/release");
        s.release();s.release();check(refs(p)==1 && !s.isValid(),"idempotent release");
        t.release();check(!post(p),"released consumer rejects frame");drop(p,true);
        for(int i=0;i<300;i++) {
            SurfaceTexture c=new SurfaceTexture(false);Surface a=new Surface(c);
            long n=take(a);Surface b=wrap(n);b.release();check(refs(n)==1,"loop reference balance");
            a.release();c.release();drop(n,true);
        }
        SurfaceTexture consumer=new SurfaceTexture(false);
        Surface source=new Surface(consumer);retained=take(source);source.release();
        Surface orphan=wrap(retained);forgotten=new WeakReference<>(orphan);
        // A listener can refer back to the Surface. No strong JNI root may retain this cycle.
        final Surface[] cycle={orphan};consumer.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener(){public void onFrameAvailable(SurfaceTexture x){cycle[0].isValid();}});
        forgottenConsumer=new WeakReference<>(consumer);
    }
    void awaitGC(int remaining) {
        System.gc();System.runFinalization();
        h.postDelayed(new Runnable(){public void run(){try {
            if(forgotten.get()!=null || forgottenConsumer.get()!=null || refs(retained)!=1) {
                check(remaining>0,"consumer/wrapper cycle retained or finalizer leaked");awaitGC(remaining-1);return;
            }
            check(!post(retained),"collected consumer rejected");drop(retained,true);
            widgetChecks();
        }catch(Throwable t){fail(t);}}},100);
    }
    void widgetChecks() {
        FrameLayout root=new FrameLayout(this);SurfaceView v=new SurfaceView(this);
        v.getHolder().addCallback(new SurfaceHolder.Callback(){
            long p;Surface wrapper;
            public void surfaceCreated(SurfaceHolder holder) {h.postDelayed(new Runnable(){public void run(){try {
                p=take(holder.getSurface());check(p!=0,"widget native window");
                wrapper=wrap(p);check(wrapper.isValid() && refs(p)==2,"widget wrapper valid");
                long q=take(wrapper);check(q==p,"widget roundtrip");drop(q,true);
                check(width(p)>0,"cached width");root.removeView(v);
                h.postDelayed(new Runnable(){public void run(){try {
                    check(!wrapper.isValid(),"unrealized consumer invalid");
                    check(width(p)>0,"retained native window readable after view removal");
                    wrapper.release();check(refs(p)==1,"widget wrapper released");drop(p,true);
                    h.postDelayed(new Runnable(){public void run(){System.out.println("NATIVE_SURFACE_PASS");finish();System.exit(0);}},100);
                }catch(Throwable t){fail(t);}}},100);
            }catch(Throwable t){fail(t);}}},150);}
            public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
            public void surfaceDestroyed(SurfaceHolder h){}
        });
        root.addView(v,new FrameLayout.LayoutParams(100,100));setContentView(root);
    }
    public void onCreate(Bundle b){super.onCreate(b);try{textureChecks();awaitGC(20);}catch(Throwable t){fail(t);}}
}
