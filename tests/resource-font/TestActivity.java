// SPDX-License-Identifier: GPL-3.0-only
package org.atl.resourcefont;
import android.app.Activity;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.os.Bundle;
public class TestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Resources r=getResources();
        int raw=r.getIdentifier("regular","font",getPackageName());
        int family=r.getIdentifier("family","font",getPackageName());
        TypedArray a=r.obtainTypedArray(r.getIdentifier("fonts","array",getPackageName()));
        Typeface font=a.getFont(0);
        if(font==null || font!=r.getFont(raw) || a.getFont(1)==null || r.getFont(family)==null)
            throw new AssertionError("Bundled font loading/cache failed");
        if(a.getFont(2)!=null)throw new AssertionError("Absent font must be null");
        try {a.getFont(3);throw new AssertionError("Integer accepted as font");}
        catch(Resources.NotFoundException expected) {}
        try {a.getFont(4);throw new AssertionError("Unresolved attribute accepted");}
        catch(UnsupportedOperationException expected) {}
        if(a.getFont(5)!=null)throw new AssertionError("Malformed font accepted");
        try {r.getFont(r.getIdentifier("broken","font",getPackageName()));throw new AssertionError("Malformed public font accepted");}
        catch(Resources.NotFoundException expected) {}
        a.recycle();
        try {a.getFont(0);throw new AssertionError("Recycled array accepted");}
        catch(RuntimeException expected) {}
        System.out.println("RESOURCE_FONT_PASS raw XML absent unresolved wrong-type malformed recycled cache");
        finish();
    }
}
