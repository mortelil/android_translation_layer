/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.eglcheck;
public class Probe extends android.app.Activity {
    private static native boolean check();
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            System.loadLibrary("eglcheck");
            if (!check()) throw new AssertionError("EGL adapter lookup");
            getFilesDir().mkdirs();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))) {
                out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));
            }
            System.out.println("EGL_NATIVE_DISPATCH PASS");
        }catch(Throwable t){System.out.println("EGL_NATIVE_DISPATCH FAIL "+t);t.printStackTrace();}
    }
}
