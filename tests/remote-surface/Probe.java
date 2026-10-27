// SPDX-License-Identifier: Apache-2.0
package org.atl.remotesurface;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.view.*;
public class Probe extends Activity {
    static { System.loadLibrary("remotesurfaceprobe"); }
    static native boolean paint(Surface surface);
    static native boolean green(Surface surface);
    static native boolean rejectBadFrame(Surface surface);
    final Handler handler = new Handler();
    SurfaceView view;
    boolean started;
    int invalidations;
    class Container extends android.widget.FrameLayout {
        Container() { super(Probe.this); }
        @Override public void onDescendantInvalidated(View child, View target) {
            invalidations++;
            super.onDescendantInvalidated(child,target);
        }
    }
    void fail(Throwable t) { t.printStackTrace(); System.out.println("REMOTE_SURFACE_FAIL"); System.exit(1); }
    public void onCreate(Bundle b) {
        super.onCreate(b);
        Parcel empty = Parcel.obtain();
        new Surface().writeToParcel(empty, 0); empty.setDataPosition(0);
        if (Surface.CREATOR.createFromParcel(empty).isValid()) throw new AssertionError("empty Surface valid");
        empty.recycle();
        view = new SurfaceView(this);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) {}
            public void surfaceDestroyed(SurfaceHolder h) {}
            public void surfaceChanged(int f, int w, int h) {}
            public void surfaceChanged(SurfaceHolder h, int f, int w, int height) {
                if (started || w <= 1 || height <= 1) return;
                started = true;
                bindService(new Intent(Probe.this, PaintService.class), connection, BIND_AUTO_CREATE);
            }
        });
        Container container=new Container(); container.addView(view); setContentView(container);
        handler.postDelayed(new Runnable(){public void run(){fail(new AssertionError("timeout"));}}, 20000);
    }
    final ServiceConnection connection = new ServiceConnection() {
        public void onServiceDisconnected(ComponentName n) {}
        public void onServiceConnected(ComponentName n, final IBinder service) {
            final int before=invalidations;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                if (!rejectBadFrame(view.getHolder().getSurface())) throw new AssertionError("unsealed frame accepted");
                Parcel local = Parcel.obtain();
                view.getHolder().getSurface().writeToParcel(local, 0); local.setDataPosition(0);
                Surface copy = Surface.CREATOR.createFromParcel(local); local.recycle();
                copy.writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                if (copy.isValid()) throw new AssertionError("return-value parcel did not release wrapper");
                service.transact(1, data, reply, 0);
                reply.readException();
                int pid = reply.readInt();
                if (pid == android.os.Process.myPid() || reply.readInt() != 1) throw new AssertionError("remote EGL producer failed");
                handler.postDelayed(new Runnable(){public void run(){try {
                    if (invalidations<=before) throw new AssertionError("native frame did not invalidate Android ancestor");
                    if (!green(view.getHolder().getSurface())) throw new AssertionError("remote frame did not reach GTK");
                    setContentView(new View(Probe.this));
                    handler.postDelayed(new Runnable(){public void run(){
                        Parcel request=Parcel.obtain(), response=Parcel.obtain();
                        try {
                            service.transact(2,request,response,0); response.readException();
                            if(response.readInt()!=0) throw new AssertionError("destroyed consumer still valid remotely");
                            unbindService(connection);
                            System.out.println("REMOTE_SURFACE_PASS"); System.exit(0);
                        }catch(Throwable t){fail(t);}finally{request.recycle();response.recycle();}
                    }},300);
                }catch(Throwable t){fail(t);}}},500);
            } catch(Throwable t) { fail(t); }
            finally { data.recycle(); reply.recycle(); }
        }
    };
}
