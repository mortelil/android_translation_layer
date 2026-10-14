// SPDX-License-Identifier: Apache-2.0
package org.atl.predrawactivity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewTreeObserver;

/** Two real activities, each retaining its own pre-draw listener across navigation. */
public class Probe extends Activity {
    static Probe first;
    static int firstCalls, secondCalls, firstDraws, secondDraws;
    static boolean firstAllow, secondAllow;
    static String failure;
    final Handler handler = new Handler();
    View content;
    final ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() { public boolean onPreDraw() { firstCalls++; return firstAllow; } };

    static void check(boolean condition, String message) {
        if (!condition && failure == null) failure = message;
    }

    static View paint(Activity activity, final boolean second) {
        return new View(activity) {
            @Override public void onDraw(Canvas canvas) {
                if (second) secondDraws++; else firstDraws++;
                canvas.drawColor(second ? 0xff00ff00 : 0xffff00ff);
            }
        };
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        first = this;
        content = paint(this, false);
        setContentView(content);
        content.getViewTreeObserver().addOnPreDrawListener(listener);
        handler.postDelayed(new Runnable() { public void run() {
            check(firstCalls > 0 && firstDraws == 0, "initial cancellation");
            startActivity(new Intent(Probe.this, Next.class));
        } }, 1200);
        handler.postDelayed(new Runnable() { public void run() {
            check(secondCalls > 0 && secondDraws > 0, "second activity never drew");
            check(firstAllow && firstDraws > 0, "return to first activity never drew");
            System.out.println("PREDRAW_ACTIVITY " + (failure == null ? "PASS" : "FAIL " + failure)
                + " firstCalls=" + firstCalls + " secondCalls=" + secondCalls
                + " firstDraws=" + firstDraws + " secondDraws=" + secondDraws);
            finish();
        } }, 10000);
    }

    public static class Next extends Activity {
        final Handler handler = new Handler();
        View content;
        int stoppedFirstCalls;
        final ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() { public boolean onPreDraw() { secondCalls++; return secondAllow; } };
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            content = paint(this, true);
            setContentView(content);
            content.getViewTreeObserver().addOnPreDrawListener(listener);
        }
        @Override public void onResume() {
            super.onResume();
            stoppedFirstCalls = firstCalls;
            handler.postDelayed(new Runnable() { public void run() {
                check(firstCalls == stoppedFirstCalls, "hidden first observer dispatched");
                check(secondCalls > 0 && secondDraws == 0, "second cancellation");
                // A hidden hierarchy must not remove or replace the active registration.
                first.content.getViewTreeObserver().removeOnPreDrawListener(first.listener);
                first.content.getViewTreeObserver().addOnPreDrawListener(first.listener);
                secondAllow = true;
                content.invalidate();
            } }, 1200);
            handler.postDelayed(new Runnable() { public void run() {
                check(secondDraws > 0, "hidden registration blocked second drawing");
                check(firstCalls == stoppedFirstCalls, "hidden observer resumed early");
                firstAllow = true;
                finish();
            } }, 3500);
        }
    }
}
