package org.atl.pendingowner;
import android.app.*;
import android.content.*;
import android.os.*;
public class Owner extends Service {
    String received = "";
    PendingIntent pending;
    public void onCreate() {
        super.onCreate();
        registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                received = c.getPackageName() + ":" + i.getStringExtra("value");
            }
        }, new IntentFilter("org.atl.pendingowner.EVENT"));
    }
    public IBinder onBind(Intent ignored) {
        return new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == 1) {
                    pending = PendingIntent.getBroadcast(Owner.this, 9,
                                                         new Intent("org.atl.pendingowner.EVENT")
                                                             .setPackage(getPackageName())
                                                             .putExtra("value", "creator"),
                                                         PendingIntent.FLAG_IMMUTABLE |
                                                             PendingIntent.FLAG_UPDATE_CURRENT);
                    pending.writeToParcel(reply, 0);
                    return true;
                }
                if (code == 2) {
                    reply.writeString(received);
                    reply.writeInt(android.os.Process.myPid());
                    return true;
                }
                if (code == 3) {
                    pending.cancel();
                    return true;
                }
                if (code == 4) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() { System.exit(0); }
                    }, 200);
                    return true;
                }
                return false;
            }
        };
    }
}
