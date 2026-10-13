/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.textstyle;
import android.widget.*;
import android.util.TypedValue;
public class Probe extends android.app.Activity {
    void check(TextView v,float sp,String label){float expected=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,sp,getResources().getDisplayMetrics());if(!label.equals("explicit button size"))expected=Math.round(expected);if(Math.abs(v.getTextSize()-expected)>0.1f)throw new AssertionError(label+" "+v.getTextSize()+" != "+expected);}
    public void onCreate(android.os.Bundle b){
        super.onCreate(b);
        try{
            LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
            TextView text=new TextView(this);text.setText("Text style 24sp");check(text,24,"default text style");root.addView(text);
            Button button=new Button(this);button.setText("Button style 28sp");check(button,28,"default button style");root.addView(button);
            int fallback=getResources().getIdentifier("Fallback","style",getPackageName());
            TextView explicit=new TextView(this,null,0,fallback);explicit.setText("Fallback style 32sp");check(explicit,32,"four-argument style");root.addView(explicit);
            Button changed=new Button(this);changed.setTextSize(36);changed.setText("Explicit button 36sp");check(changed,36,"explicit button size");root.addView(changed);
            setContentView(root);
            getFilesDir().mkdirs();java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"));out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));out.close();System.out.println("TEXT_STYLE PASS");
        }catch(Throwable t){t.printStackTrace();System.out.println("TEXT_STYLE FAIL "+t);}
    }
}
