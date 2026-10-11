// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.view.View;

public class TestImageView {
    public static void run(Context context) {
        ImageView view = new ImageView(context);
        Bitmap small = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
        Bitmap large = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888);
        BitmapDrawable previous = new BitmapDrawable(context.getResources(), small);
        view.setImageDrawable(previous);
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(spec, spec);
        view.setImageBitmap(large);
        if (!(view.getDrawable() instanceof BitmapDrawable) ||
            ((BitmapDrawable)view.getDrawable()).getBitmap() != large)
            throw new AssertionError("Bitmap setter left stale drawable state");
        if (previous.getCallback() != null || view.getDrawable().getCallback() != view)
            throw new AssertionError("Drawable callback ownership after replacement");
        if (!view.isLayoutRequested()) throw new AssertionError("Image replacement did not request measurement");
        view.measure(spec, spec);
        if (view.getMeasuredWidth() != 60 || view.getMeasuredHeight() != 30)
            throw new AssertionError("Image replacement kept old preferred size");
        view.setImageBitmap(null);
        if (view.getDrawable() != null) throw new AssertionError("Null bitmap must clear drawable");
        view.setImageBitmap(small);
        view.setImageResource(0);
        if (view.getDrawable() != null) throw new AssertionError("Resource zero must clear drawable");
        System.out.println("PASS: ImageView bitmap replacement, callbacks, measurement and clearing");
    }
}
