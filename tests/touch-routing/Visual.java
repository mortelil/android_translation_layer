/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.touchrouting;
import android.view.*;
import android.widget.*;
public class Visual extends android.app.Activity {
    private int nativeClicks, coveredClicks;
    private TextView status;
    private void update() {
        status.setText("Native="+nativeClicks+" Covered="+coveredClicks);
        System.out.println("TOUCH_VISUAL native="+nativeClicks+" covered="+coveredClicks);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        System.out.println("TOUCH_WINDOW action="+event.getActionMasked());
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
        status=new TextView(this);status.setText("Native=0 Covered=0");
        FrameLayout.LayoutParams below=new FrameLayout.LayoutParams(700,100);below.topMargin=220;
        root.addView(status,below);
        setContentView(root);
    }
}
