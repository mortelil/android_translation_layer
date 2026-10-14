// SPDX-License-Identifier: GPL-3.0-only
package org.atl.bitmapunlock;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
public class Probe extends android.app.Activity {
    private static native boolean fill(Bitmap bitmap, int red);
    private int checks;
    private void check(boolean v) { if(!v) throw new AssertionError("check "+checks); checks++; }
    public void onCreate(android.os.Bundle state) { super.onCreate(state); try {
        System.loadLibrary("bitmapunlock");
        Bitmap b=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);
        for(int red:new int[]{37,193,81}) {
            check(fill(b,red));
            check(!b.isRecycled());
            ByteBuffer bytes=ByteBuffer.allocate(b.getByteCount());
            b.copyPixelsToBuffer(bytes);
            for(int y=0;y<2;y++) for(int x=0;x<3;x++) {
                int i=y*b.getRowBytes()+4*x;
                check((bytes.get(i)&255)==red && bytes.get(i+1)==0
                    && bytes.get(i+2)==0 && (bytes.get(i+3)&255)==255);
            }
            check(b.getWidth()==3 && b.getHeight()==2);
        }
        b.recycle();check(b.isRecycled());b.recycle();check(b.isRecycled());
        getFilesDir().mkdirs();
        try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))) {
            f.write(("{\"status\":\"PASS\",\"checks\":"+checks+"}").getBytes("UTF-8"));
        }
        System.out.println("BITMAP_UNLOCK PASS checks="+checks);
    } catch(Throwable t) { t.printStackTrace();System.out.println("BITMAP_UNLOCK FAIL "+t); } }
}
