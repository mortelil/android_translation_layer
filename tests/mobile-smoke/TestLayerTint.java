// SPDX-License-Identifier: GPL-3.0-only
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
public class TestLayerTint {
    private static int pixel(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 10, 10);
        drawable.draw(new Canvas(bitmap));
        int[] pixels = new int[100]; bitmap.getPixels(pixels, 0, 10, 0, 0, 10, 10);
        return pixels[55];
    }
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("Layer tint expected " + Integer.toHexString(expected) + " got " + Integer.toHexString(actual));
    }
    public static void run() {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(0xff0000ff);
        LayerDrawable inner = new LayerDrawable(new Drawable[]{shape});
        LayerDrawable outer = new LayerDrawable(new Drawable[]{inner});
        ColorStateList colors = new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}}, new int[]{0xffff0000, 0xff00ff00});
        outer.setTintList(colors);
        equal(0xff00ff00, pixel(outer));
        Drawable copy = outer.getConstantState().newDrawable().mutate();
        equal(0xff00ff00, pixel(copy));
        copy.setTint(0xff00ffff); equal(0xff00ffff, pixel(copy));
        equal(0xff00ff00, pixel(outer));
        outer.setState(new int[]{android.R.attr.state_pressed});
        equal(0xffff0000, pixel(outer));
        outer.setTintList(null); equal(0xff0000ff, pixel(outer));
        outer.setColorFilter(new PorterDuffColorFilter(0xffffff00, PorterDuff.Mode.SRC_IN));
        equal(0xffffff00, pixel(outer));
        outer.setColorFilter(null); equal(0xff0000ff, pixel(outer));
        outer.setTintMode(PorterDuff.Mode.SRC_IN);
        outer.setTint(0x00000000); equal(0, pixel(outer));
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0xff000000), shape, null);
        Drawable rippleCopy = ripple.getConstantState().newDrawable();
        if (!(rippleCopy instanceof RippleDrawable)) throw new AssertionError("Ripple clone lost its type");
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{shape, new GradientDrawable()});
        if (!(transition.getConstantState().newDrawable() instanceof TransitionDrawable))
            throw new AssertionError("Transition clone lost its type");
        System.out.println("PASS: nested layer tint/state, transparent tint, filter and clearing render correctly");
    }
}
