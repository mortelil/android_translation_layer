/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.runtimestats;
public class Probe extends android.app.Activity {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            java.util.Map<String,String> stats=android.os.Debug.getRuntimeStats();
            check(!stats.isEmpty(),"runtime statistics exist");
            check(Long.parseLong(stats.get("art.gc.bytes-allocated"))>0,"real allocation counter");
            long gc=Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.gc-count"));
            check(gc>=0,"nonnegative GC count");System.gc();
            check(Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.gc-count"))>=gc,"monotonic GC count");
            check(android.os.Debug.getRuntimeStat("atl.unknown.statistic")==null,"unknown statistic");
            try {android.os.Debug.getRuntimeStat(null);throw new AssertionError("null accepted");}catch(NullPointerException expected){}
            stats.clear();check(!android.os.Debug.getRuntimeStats().isEmpty(),"independent result map");
            getFilesDir().mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))){out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));}
            System.out.println("RUNTIME_STATS PASS");
        }catch(Throwable t){System.out.println("RUNTIME_STATS FAIL "+t);t.printStackTrace();}
    }
}
