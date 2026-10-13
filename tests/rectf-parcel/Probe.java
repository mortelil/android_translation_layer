// SPDX-License-Identifier: Apache-2.0
package org.atl.rectfparcel;
import android.graphics.RectF;
import android.os.*;
public class Probe extends android.app.Activity {
    int checks;
    void check(boolean value) { if (!value) throw new AssertionError("check " + checks); checks++; }
    void eq(float a,float b) { check(Float.floatToIntBits(a)==Float.floatToIntBits(b)); }
    void rect(RectF a,RectF b) { eq(a.left,b.left);eq(a.top,b.top);eq(a.right,b.right);eq(a.bottom,b.bottom); }
    public void onCreate(Bundle state) { super.onCreate(state); try {
        RectF r=new RectF(-12.5f,23.25f,17.75f,-8.125f);
        check(r.describeContents()==0);
        Parcel p=Parcel.obtain();
        r.writeToParcel(p,0);p.writeInt(0x12345678);p.setDataPosition(0);
        eq(p.readFloat(),r.left);eq(p.readFloat(),r.top);eq(p.readFloat(),r.right);eq(p.readFloat(),r.bottom);
        check(p.readInt()==0x12345678);
        p.setDataPosition(0);RectF copy=RectF.CREATOR.createFromParcel(p);rect(copy,r);check(copy!=r);check(p.readInt()==0x12345678);
        p.setDataPosition(0);RectF target=new RectF(1,2,3,4);target.readFromParcel(p);rect(target,r);p.recycle();
        RectF special=new RectF(-0.0f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY);
        p=Parcel.obtain();special.writeToParcel(p,0);p.setDataPosition(0);rect(RectF.CREATOR.createFromParcel(p),special);p.recycle();
        check(RectF.CREATOR.newArray(0).length==0);RectF[] empty=RectF.CREATOR.newArray(3);check(empty.length==3 && empty[0]==null && empty[2]==null);
        p=Parcel.obtain();p.writeTypedArray(new RectF[]{r,null,special},0);p.setDataPosition(0);RectF[] array=p.createTypedArray(RectF.CREATOR);check(array.length==3 && array[1]==null);rect(array[0],r);rect(array[2],special);p.recycle();
        p=Parcel.obtain();p.writeTypedArray(new RectF[]{null,r},0);p.setDataPosition(0);RectF[] destination={special,special};p.readTypedArray(destination,RectF.CREATOR);check(destination[0]==null);rect(destination[1],r);p.recycle();
        p=Parcel.obtain();p.writeTypedArray(null,0);p.setDataPosition(0);check(p.createTypedArray(RectF.CREATOR)==null);p.recycle();
        Bundle b=new Bundle();b.putParcelable("rect",r);p=Parcel.obtain();b.writeToParcel(p,0);p.setDataPosition(0);Bundle restored=Bundle.CREATOR.createFromParcel(p);rect((RectF)restored.getParcelable("rect"),r);p.recycle();
        getFilesDir().mkdirs();try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))){f.write(("{\"status\":\"PASS\",\"checks\":"+checks+"}").getBytes("UTF-8"));}
        System.out.println("RECTF_PARCEL PASS checks="+checks);
    } catch(Throwable t) { t.printStackTrace();System.out.println("RECTF_PARCEL FAIL "+t); } }
}
