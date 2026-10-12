// SPDX-License-Identifier: GPL-3.0-only
package org.atl.mobile.smoke;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;
public class ExecutorProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    public static class ProbeService extends Service {
        public IBinder onBind(Intent intent) { return new Binder(); }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        main.postDelayed(new Runnable() { public void run() { System.err.println("FAIL: executor callback timeout"); System.exit(1); } }, 10000);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    System.err.println("FAIL: callback ignored supplied executor"); System.exit(1);
                }
                final ServiceConnection self = this;
                main.post(new Runnable() { public void run() {
                    unbindService(self);
                    executor.shutdown();
                    System.out.println("PASS: supplied executor and original-connection unbind");
                    System.exit(0);
                } });
            }
        };
        if (!bindService(new Intent(this, ProbeService.class), Context.BIND_AUTO_CREATE, executor, connection)) {
            System.err.println("FAIL: bind rejected"); System.exit(1);
        }
    }
}
