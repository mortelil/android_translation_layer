// SPDX-License-Identifier: Apache-2.0
package org.atl.dimensions;
import android.util.TypedValue;
import android.util.DisplayMetrics;
public class Probe extends android.app.Activity {
    private int checks;
    private void check(boolean b, String message) { if(!b) throw new AssertionError(message); checks++; }
    private void near(float a,float b) { check(Math.abs(a-b)<0.0001f, a+" != "+b); }
    private void invalid(Runnable r) { try { r.run(); throw new AssertionError("accepted invalid value"); } catch(IllegalArgumentException expected) { checks++; } }
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        try {
            if("org.atl.dimensions.ENCODING".equals(getIntent().getAction())) {
            check(TypedValue.intToComplex(1)==0x100,"integer encoding");
            check(TypedValue.floatToComplex(0.5f)==0x40000030,"fraction encoding");
            check(TypedValue.floatToComplex(-0.5f)==0xc0000030,"negative fraction");
            check(TypedValue.floatToComplex(256.5f)==0x804010,"16p7 encoding");
            check(TypedValue.floatToComplex(123.5f)==0x3dc00020,"8p15 encoding");
            near(TypedValue.complexToFloat(TypedValue.intToComplex(-8388608)),-8388608);
            invalid(new Runnable(){ public void run(){ TypedValue.intToComplex(8388608); }});
            invalid(new Runnable(){ public void run(){ TypedValue.intToComplex(-8388609); }});
            invalid(new Runnable(){ public void run(){ TypedValue.floatToComplex(Float.POSITIVE_INFINITY); }});
            invalid(new Runnable(){ public void run(){ TypedValue.floatToComplex(Float.NEGATIVE_INFINITY); }});
            check(TypedValue.floatToComplex(Float.NaN)==0,"AOSP NaN encoding");
            }
            final DisplayMetrics m=new DisplayMetrics();m.density=2.625f;m.scaledDensity=3.15f;m.xdpi=420;
            for(int unit=0;unit<=5;unit++) {
                if("org.atl.dimensions.ENCODING".equals(getIntent().getAction())) {
                int packed=TypedValue.createComplexDimension(12.5f,unit);
                check((packed&15)==unit,"unit encoding");near(TypedValue.complexToFloat(packed),12.5f);
                check((TypedValue.createComplexDimension(12,unit)&15)==unit,"integer unit encoding");
                }
                for(float value:new float[]{-20.25f,0,12.5f,320}) {
                    float pixels=TypedValue.applyDimension(unit,value,m);
                    near(TypedValue.deriveDimension(unit,pixels,m),value);
                    near(TypedValue.convertPixelsToDimension(unit,pixels,m),value);
                    near(TypedValue.convertDimensionToPixels(unit,value,m),pixels);
                }
            }
            if("org.atl.dimensions.ENCODING".equals(getIntent().getAction())) {
            invalid(new Runnable(){ public void run(){ TypedValue.createComplexDimension(1,-1); }});
            invalid(new Runnable(){ public void run(){ TypedValue.createComplexDimension(1f,6); }});
            }
            invalid(new Runnable(){ public void run(){ TypedValue.deriveDimension(6,12,m); }});
            near(TypedValue.convertDimensionToPixels(6,12,m),0);
            m.density=0;m.scaledDensity=0;m.xdpi=0;
            for(int unit=1;unit<=5;unit++)near(TypedValue.deriveDimension(unit,12,m),0);
            near(TypedValue.deriveDimension(0,12,m),12);
            getFilesDir().mkdirs();
            try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))) {
                f.write(("{\"status\":\"PASS\",\"checks\":"+checks+"}").getBytes("UTF-8"));
            }
            System.out.println("DIMENSIONS PASS checks="+checks);
        } catch(Throwable t) { t.printStackTrace();System.out.println("DIMENSIONS FAIL "+t); }
    }
}
