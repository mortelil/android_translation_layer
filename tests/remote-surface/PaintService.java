// SPDX-License-Identifier: Apache-2.0
package org.atl.remotesurface;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.view.Surface;
public class PaintService extends Service {
    Surface retained;
    public void onDestroy() { if(retained!=null) retained.release(); super.onDestroy(); }
    public IBinder onBind(Intent intent) { return new Binder() {
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == 2) {
                boolean valid = retained.isValid(); retained.release(); retained=null;
                reply.writeNoException(); reply.writeInt(valid?1:0); return true;
            }
            if (code != 1) return false;
            Surface surface = Surface.CREATOR.createFromParcel(data);
            boolean result;
            retained = surface;
            result = surface.isValid() && Probe.paint(surface);
            reply.writeNoException(); reply.writeInt(android.os.Process.myPid()); reply.writeInt(result ? 1 : 0);
            return true;
        }
    }; }
}
