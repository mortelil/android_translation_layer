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
                int largeHeight = view.getLineHeight();
                near(view.getPaint().getFontMetricsInt(null), largeHeight, "default line height");
                view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                near(24, view.getTextSize(), "DP conversion");
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20.5f);
                near(20.5f, view.getTextSize(), "fractional pixels");
                if (view.getLineHeight() >= largeHeight || view.getLineHeight() < 20)
                    throw new AssertionError("Line height must follow font size");
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
        TextView measured = new TextView(context);
        if (measured.getLayout() != null || measured.getLineCount() != 0)
            throw new AssertionError("Unmeasured text must not expose a fabricated layout");
        measured.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20);
        measured.setPadding(8, 0, 12, 0);
        measured.setText("Short");
        measured.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (measured.getWidth() != 0) throw new AssertionError("Test must precede layout");
        if (measured.getLayout().getWidth() != 180 || measured.getLineCount() != 1)
            throw new AssertionError("Text layout must use measured content width before layout");
        measured.setText("Several words that require wrapping into multiple lines in a narrow field");
        measured.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (measured.getLayout().getWidth() != 80 || measured.getLineCount() < 2 ||
            measured.getLineCount() != measured.getLayout().getLineCount())
            throw new AssertionError("Line count must describe measured wrapped text");
        measured.setText("Short");
        measured.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (measured.getLineCount() != 1) throw new AssertionError("Remeasure must refresh line count");
        // Reserve trailing/footer space after the first measurement, as a
        // parent layout does when deciding whether a footer fits beside text.
        TextView padded = new TextView(context);
        padded.setText("Example");
        padded.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20);
        int free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        padded.measure(free, free);
        int originalWidth = padded.getMeasuredWidth(), originalHeight = padded.getMeasuredHeight();
        padded.setPadding(0, 0, 60, 24);
        if (!padded.isLayoutRequested()) throw new AssertionError("Padding did not request remeasurement");
        padded.measure(free, free);
        if (padded.getMeasuredWidth() < originalWidth + 60 || padded.getMeasuredHeight() < originalHeight + 24)
            throw new AssertionError("Footer reservation missing from measured size");
        padded.setPadding(0, 0, 0, 0);
        padded.measure(free, free);
        if (padded.getMeasuredWidth() != originalWidth || padded.getMeasuredHeight() != originalHeight)
            throw new AssertionError("Removing padding must restore measured size");
        padded.setPadding(0, 0, 0, 0);
        if (padded.isLayoutRequested()) throw new AssertionError("Unchanged padding requested another layout");
        System.out.println("PASS: padding remeasurement reserves and releases footer space");
        System.out.println("PASS: TextView pre-layout content width, wrapping and remeasurement");
        System.out.println("PASS: TextView/EditText size units, paint synchronization and GTK pixel measurement");
    }
}
