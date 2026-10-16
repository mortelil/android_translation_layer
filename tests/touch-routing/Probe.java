/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.touchrouting;
import android.view.*;
import android.widget.*;
import java.util.*;
public class Probe extends android.app.Activity {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    class Leaf extends View {
        final List<Integer> actions = new ArrayList<>();
        boolean accept = true; float x, y;
        Leaf() { super(Probe.this); }
        public boolean onTouchEvent(MotionEvent e) { actions.add(e.getActionMasked()); x=e.getX();y=e.getY();return accept; }
    }
    class Root extends FrameLayout {
        boolean intercept;
        List<Integer> self = new ArrayList<>();
        Root() { super(Probe.this); }
        public boolean onInterceptTouchEvent(MotionEvent e) { return intercept; }
        public boolean onTouchEvent(MotionEvent e) { self.add(e.getActionMasked());return true; }
    }
    void send(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(1, 1, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        try {
            Root root = new Root(); Leaf bottom = new Leaf(), top = new Leaf();
            root.addView(bottom, new FrameLayout.LayoutParams(100,100));
            root.addView(top, new FrameLayout.LayoutParams(100,100));
            root.layout(0,0,300,300); bottom.layout(20,30,120,130);top.layout(20,30,120,130);
            send(root,0,25,37);
            check(top.actions.equals(Arrays.asList(0)) && bottom.actions.isEmpty(), "topmost DOWN");
            check(top.x==5 && top.y==7, "child-local coordinates");
            send(root,2,240,245); send(root,1,240,245);
            check(top.actions.equals(Arrays.asList(0,2,1)), "target retained outside bounds");
            check(top.x==220 && top.y==215, "outside coordinates retained");
            top.actions.clear();top.accept=false;
            send(root,0,25,37);send(root,1,25,37);
            check(top.actions.equals(Arrays.asList(0)), "rejected child sees only DOWN");
            check(bottom.actions.equals(Arrays.asList(0,1)), "fall through to accepting child");
            top.accept=true;top.actions.clear();bottom.actions.clear();
            send(root,0,25,37);root.intercept=true;send(root,2,26,38);send(root,1,26,38);
            check(top.actions.equals(Arrays.asList(0,3)), "interception cancels original child once");
            check(root.self.contains(1), "parent receives subsequent UP");
            root.intercept=false;top.actions.clear();
            send(root,0,25,37);root.requestDisallowInterceptTouchEvent(true);root.intercept=true;
            send(root,2,27,39);send(root,3,27,39);
            check(top.actions.equals(Arrays.asList(0,2,3)), "disallow intercept and cancel");
            root.intercept=false;top.actions.clear();root.scrollTo(3,4);
            send(root,0,25,37);check(top.x==8&&top.y==11,"scroll-adjusted coordinates");send(root,1,25,37);
            root.scrollTo(0,0);top.actions.clear();bottom.actions.clear();
            bottom.setElevation(10);
            send(root,0,25,37);
            check(bottom.actions.equals(Arrays.asList(0)) && top.actions.isEmpty(), "higher elevation wins despite child order");
            top.setTranslationZ(20);
            send(root,1,25,37);
            check(bottom.actions.equals(Arrays.asList(0,1)) && top.actions.isEmpty(), "Z change retains active target");
            bottom.actions.clear();
            send(root,0,25,37);send(root,1,25,37);
            check(top.actions.equals(Arrays.asList(0,1)) && bottom.actions.isEmpty(), "translationZ changes next hit test");
            final int[] clicks={0}; View plain=new View(this) { public boolean post(Runnable action) { action.run(); return true; } };plain.layout(0,0,100,100);
            plain.setOnClickListener(new View.OnClickListener(){public void onClick(View v){clicks[0]++;}});
            send(plain,0,10,10);send(plain,1,10,10);check(clicks[0]==1,"click once");
            send(plain,0,10,10);send(plain,3,10,10);send(plain,1,10,10);check(clicks[0]==1,"cancel suppresses click");
            MotionEvent.PointerProperties[] ids={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};ids[0].id=2;ids[1].id=9;
            MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};coords[0].x=10;coords[0].y=20;coords[1].x=30;coords[1].y=40;
            MotionEvent multi=MotionEvent.obtain(1,2,2,2,ids,coords,0,0,1,1,0,0,0x1002,0);
            multi.offsetLocation(5,6);check(multi.getX(0)==15&&multi.getY(1)==46,"offset every pointer");multi.recycle();
            getFilesDir().mkdirs();
            java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"));out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));out.close();
            System.out.println("TOUCH_ROUTING PASS");
        } catch(Throwable t){t.printStackTrace();System.out.println("TOUCH_ROUTING FAIL "+t);}
    }
}
