// SPDX-License-Identifier: Apache-2.0
package org.atl.measurecontract;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
public class Probe extends Activity {
    private static int spec(int size,int mode){return View.MeasureSpec.makeMeasureSpec(size,mode);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try {
            TextView text=new TextView(this);text.setText("Download 226.10 MB");text.setTextSize(16);
            int free=spec(0,View.MeasureSpec.UNSPECIFIED);
            text.measure(free,free);int natural=text.getMeasuredWidth();
            check(natural>0,"non-empty text has a natural width");
            for(int height:new int[]{0,20,42,100}){
                text.measure(free,spec(height,View.MeasureSpec.EXACTLY));
                System.out.println("MEASURE height="+height+" width="+text.getMeasuredWidth()+" natural="+natural);
                check(text.getMeasuredWidth()==natural,"fixed height must not force wrap-content text into a narrow column");
                check(text.getMeasuredHeight()==height,"exact height");
            }
            int limit=Math.max(1,natural/2);
            text.measure(spec(limit,View.MeasureSpec.AT_MOST),spec(42,View.MeasureSpec.EXACTLY));
            check(text.getMeasuredWidth()==limit,"AT_MOST caps the natural width");
            text.measure(spec(limit,View.MeasureSpec.EXACTLY),free);
            check(text.getMeasuredWidth()==limit,"exact width");
            int wrappedHeight=text.getMeasuredHeight();
            text.measure(spec(natural+20,View.MeasureSpec.EXACTLY),free);
            check(wrappedHeight>=text.getMeasuredHeight(),"narrower width must not reduce wrapped height");
            final android.widget.FrameLayout root=new android.widget.FrameLayout(this);
            final android.widget.FrameLayout button=new android.widget.FrameLayout(this);
            button.setBackgroundColor(0xff2277ff);
            button.setOnClickListener(new View.OnClickListener(){public void onClick(View view){}});
            final TextView growing=new TextView(this);growing.setText("Download");
            button.addView(growing,new android.widget.FrameLayout.LayoutParams(-2,42,android.view.Gravity.CENTER));
            root.addView(button,new android.widget.FrameLayout.LayoutParams(-1,42));
            setContentView(root);
            final android.os.Handler handler=new android.os.Handler();
            handler.postDelayed(new Runnable(){public void run(){
                final int oldWidth=growing.getWidth();
                growing.setText("Download 226.10 MB");
                handler.postDelayed(new Runnable(){public void run(){try {
                    System.out.println("MEASURE_GROW old="+oldWidth+" measured="+growing.getMeasuredWidth()+" laidOut="+growing.getWidth());
                    check(growing.getWidth()>oldWidth,"changed wrap-content text must grow without resizing its parent");
                    check(growing.getWidth()==growing.getMeasuredWidth(),"layout must use the new measured width");
                    System.out.println("MEASURE_CONTRACT_PASS");finish();System.exit(0);
                }catch(Throwable error){error.printStackTrace();System.out.println("MEASURE_CONTRACT_FAIL");System.exit(1);}}},300);
            }},300);
        }catch(Throwable error){error.printStackTrace();System.out.println("MEASURE_CONTRACT_FAIL");System.exit(1);}
    }
}
