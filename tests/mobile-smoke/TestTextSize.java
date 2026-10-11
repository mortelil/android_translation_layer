// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;

public class TestTextSize {
    private static void near(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > 0.01f) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    public static void run(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float density = metrics.density, scaled = metrics.scaledDensity;
        try {
            metrics.density = 2;
            metrics.scaledDensity = 3;
            for (TextView view : new TextView[] {new TextView(context), new EditText(context)}) {
                view.setTextSize(12);
                near(36, view.getTextSize(), "SP conversion");
                near(36, view.getPaint().getTextSize(), "measurement paint");
                view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                near(24, view.getTextSize(), "DP conversion");
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20.5f);
                near(20.5f, view.getTextSize(), "fractional pixels");
                view.setText("MMMMMMMMMM");
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40);
                near(40, view.getPaint().getTextSize(), "updated measurement paint");
            }
        } finally {
            metrics.density = density;
            metrics.scaledDensity = scaled;
        }
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30);
        label.setText("MMMMMMMMMM"); // Text changes must preserve the base size.
        label.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float expected = label.getPaint().measureText("MMMMMMMMMM");
        if (Math.abs(label.getMeasuredWidth() - expected) > expected * 0.15f)
            throw new AssertionError("GTK width disagrees with Android paint: " + label.getMeasuredWidth() + " vs " + expected);
        System.out.println("PASS: TextView/EditText size units, paint synchronization and GTK pixel measurement");
    }
}
