/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.touchrouting;
import android.view.*;
import android.widget.*;
public class Visual extends android.app.Activity {
    private int nativeClicks, coveredClicks;
    private TextView status;
    private long gestureStart;
    private void update() {
        status.setText("Native="+nativeClicks+" Covered="+coveredClicks);
        System.out.println("TOUCH_VISUAL native="+nativeClicks+" covered="+coveredClicks);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        System.out.println("TOUCH_WINDOW action="+event.getActionMasked());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) gestureStart = event.getDownTime();
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (event.getDownTime() != gestureStart || event.getEventTime() < gestureStart)
                throw new AssertionError("Native gesture start time changed");
            System.out.println("TOUCH_TIME PASS elapsed="+(event.getEventTime()-gestureStart));
        }
        return super.dispatchTouchEvent(event);
    }
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        FrameLayout root=new FrameLayout(this);
        Button button=new Button(this);button.setText("Native GTK button");
        button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){nativeClicks++;update();}});
        root.addView(button,new FrameLayout.LayoutParams(300,180));
        View underlay=new View(this);underlay.setBackgroundColor(0xff3060ff);
        underlay.setOnClickListener(new View.OnClickListener(){public void onClick(View v){coveredClicks++;update();}});
        FrameLayout.LayoutParams right=new FrameLayout.LayoutParams(300,180);right.leftMargin=350;
        root.addView(underlay,right);
        // A native surface is picked by GTK, but Android can fall through the
        // unhandled DOWN to the accepting sibling beneath it.
        SurfaceView surface=new SurfaceView(this);
        FrameLayout.LayoutParams over=new FrameLayout.LayoutParams(300,180);over.leftMargin=350;
        root.addView(surface,over);
        // Like a collapsed bottom sheet: GTK sees a touch controller on this
        // transparent top sibling, but Android must try the sibling below.
        FrameLayout overlay=new FrameLayout(this) {
            @Override public boolean onTouchEvent(MotionEvent event) { return false; }
        };
        FrameLayout.LayoutParams overlayParams=new FrameLayout.LayoutParams(300,180);
        overlayParams.leftMargin=350;
        root.addView(overlay,overlayParams);
        EditText input=new EditText(this);
        input.setHint("Native text entry");
        FrameLayout.LayoutParams inputParams=new FrameLayout.LayoutParams(500,80);
        inputParams.topMargin=350;
        root.addView(input,inputParams);
        View reject=new View(this) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                System.out.println("TOUCH_REJECT action="+event.getActionMasked());
                return false;
            }
        };
        FrameLayout.LayoutParams rejectParams=new FrameLayout.LayoutParams(80,180);
        rejectParams.leftMargin=660;
        root.addView(reject,rejectParams);
        status=new TextView(this);status.setText("Native=0 Covered=0");
        FrameLayout.LayoutParams below=new FrameLayout.LayoutParams(700,100);below.topMargin=220;
        root.addView(status,below);
        setContentView(root);
    }
}
