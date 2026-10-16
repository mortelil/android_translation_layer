// SPDX-License-Identifier: GPL-3.0-only
// Contract reference: AOSP android-17.0.0_r1 Bitmap/BitmapFactory/BitmapDrawable.
import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Parcel;
import android.widget.ImageView;
import android.view.View;
import java.io.InputStream;

public class TestBitmapDensity {
    static void eq(int want, int got, String what) {
        if (want != got) throw new AssertionError(what + ": expected " + want + " got " + got);
    }
    public static void run(Context context) throws Exception {
        Resources res = context.getResources();
        int dpi = res.getDisplayMetrics().densityDpi;
        int icon = res.getIdentifier("density_icon", "drawable", context.getPackageName());
        int raw = res.getIdentifier("density_unscaled", "drawable", context.getPackageName());
        if (icon == 0 || raw == 0) throw new AssertionError("density fixtures missing");
        Bitmap b = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888);
        b.setDensity(160);
        eq(48, b.getScaledWidth(320), "bitmap width at xhdpi");
        eq(18, b.getScaledHeight(240), "bitmap height at hdpi");
        eq(24, b.getScaledWidth(0), "zero target");
        eq(160, b.copy(Bitmap.Config.ARGB_8888, true).getDensity(), "copy density");
        eq(160, Bitmap.createScaledBitmap(b, 12, 6, true).getDensity(), "scaled copy density");
        BitmapDrawable d = new BitmapDrawable(res, b);
        d.setTargetDensity(320);
        eq(48, d.getIntrinsicWidth(), "drawable target width");
        ImageView view = new ImageView(context);
        view.setImageDrawable(d);
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        view.measure(spec, spec);
        eq(48, view.getMeasuredWidth(), "native paintable agrees with drawable width");
        b.setDensity(0);
        eq(24, b.getScaledWidth(320), "nodpi bitmap");
        Bitmap decoded = BitmapFactory.decodeResource(res, icon);
        eq(Bitmap.scaleFromDensity(24, 160, dpi), decoded.getWidth(), "resource bitmap width");
        eq(dpi, decoded.getDensity(), "resource bitmap density");
        if (dpi == 160) eq(0xffff0000, decoded.getPixel(0, 0), "mdpi asset selected");
        if (dpi == 320) eq(0xff0000ff, decoded.getPixel(0, 0), "xhdpi asset selected");
        Parcel parcel = Parcel.obtain();
        decoded.writeToParcel(parcel, 0); parcel.setDataPosition(0);
        Bitmap restored = Bitmap.CREATOR.createFromParcel(parcel); parcel.recycle();
        eq(dpi, restored.getDensity(), "parcel density");
        eq(decoded.getWidth(), restored.getWidth(), "parcel width");
        Drawable drawable = res.getDrawable(icon);
        eq(decoded.getWidth(), drawable.getIntrinsicWidth(), "resource drawable width");
        // Draw into explicit bounds and inspect actual pixels, not only metadata.
        Bitmap target = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888);
        drawable.setBounds(4, 4, 52, 52);
        drawable.draw(new Canvas(target));
        eq(255, target.getPixel(40, 40) >>> 24, "drawn icon fills bounds");
        eq(0, target.getPixel(55, 55) >>> 24, "drawing stays inside bounds");
        eq(24, BitmapFactory.decodeResource(res, raw).getWidth(), "nodpi decode width");
        eq(24, res.getDrawable(raw).getIntrinsicWidth(), "nodpi drawable width");
        // Explicit source/target densities exercise scaling regardless of process density.
        for (boolean scaled : new boolean[] {false, true}) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inDensity = 160; opts.inTargetDensity = 320; opts.inScaled = scaled;
            try (InputStream stream = res.openRawResource(raw)) {
                Bitmap out = BitmapFactory.decodeStream(stream, null, opts);
                eq(scaled ? 48 : 24, out.getWidth(), "inScaled dimensions");
                eq(scaled ? 320 : 160, out.getDensity(), "inScaled density");
            }
        }
        BitmapFactory.Options screen = new BitmapFactory.Options();
        screen.inDensity = 160; screen.inTargetDensity = 320; screen.inScreenDensity = 160;
        try (InputStream stream = res.openRawResource(raw)) {
            Bitmap out = BitmapFactory.decodeStream(stream, null, screen);
            eq(24, out.getWidth(), "screen-density exemption width");
            eq(160, out.getDensity(), "screen-density exemption density");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inDensity = 160; bounds.inTargetDensity = 320; bounds.inJustDecodeBounds = true;
        try (InputStream stream = res.openRawResource(raw)) {
            if (BitmapFactory.decodeStream(stream, null, bounds) != null)
                throw new AssertionError("bounds-only decode returned pixels");
            eq(24, bounds.outWidth, "bounds-only source width");
        }
        System.out.println("PASS: bitmap resource density, native measurement and drawn pixels at " + dpi);
    }
}
