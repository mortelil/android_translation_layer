// SPDX-License-Identifier: GPL-3.0-only
package org.atl.sharedmemoryprobe;
import android.app.Activity;
import android.os.Bundle;
public class Probe extends Activity {
    static { System.loadLibrary("sharedmemoryprobe"); }
    static native int check();
    static native boolean isMemfd();
    public void onCreate(Bundle b) {
        super.onCreate(b);
        int result = check();
        android.util.Log.i("SharedMemoryProbe", "SHARED_MEMORY_RESULT=" + result + " backend=" + (isMemfd() ? "memfd" : "ashmem"));
        System.out.println("SHARED_MEMORY_RESULT=" + result + " backend=" + (isMemfd() ? "memfd" : "ashmem"));
        finish();
        System.exit(result);
    }
}
