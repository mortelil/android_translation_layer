// SPDX-License-Identifier: Apache-2.0
package org.atl.bundlevalues;
import android.os.*;
public class Probe extends android.app.Activity {
    int checks;
    void eq(double actual,double expected){ if(Double.doubleToLongBits(actual)!=Double.doubleToLongBits(expected))throw new AssertionError(actual+" != "+expected);checks++; }
    void exercise(BaseBundle b){
        eq(b.getDouble("absent"),0);eq(b.getDouble("absent",42.5),42.5);
        b.putDouble("x",63.4305);eq(b.getDouble("x"),63.4305);
        b.putDouble("x",-0.0);eq(b.getDouble("x"),-0.0);
        b.putDouble("x",Double.NaN);eq(b.getDouble("x"),Double.NaN);
        b.putDouble(null,10.3951);eq(b.getDouble(null),10.3951);
        b.putString("null",null);eq(b.getDouble("null",7.5),7.5);
        b.putInt("wrong",3);eq(b.getDouble("wrong",9.5),9.5);
        b.remove("x");eq(b.getDouble("x",6),6);
    }
    public void onCreate(Bundle state){super.onCreate(state);try{
        Bundle b=new Bundle();exercise(b);exercise(new PersistableBundle());
        BaseBundle base=b;base.putDouble("latitude",63.4305);base.putDouble("longitude",10.3951);
        Parcel parcel=Parcel.obtain();b.writeToParcel(parcel,0);parcel.setDataPosition(0);
        BaseBundle copy=Bundle.CREATOR.createFromParcel(parcel);parcel.recycle();
        eq(copy.getDouble("latitude"),63.4305);eq(copy.getDouble("longitude"),10.3951);
        getFilesDir().mkdirs();try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))){f.write(("{\"status\":\"PASS\",\"checks\":"+checks+"}").getBytes("UTF-8"));}
        System.out.println("BUNDLE_VALUES PASS checks="+checks);
    }catch(Throwable t){t.printStackTrace();System.out.println("BUNDLE_VALUES FAIL "+t);}}
}
