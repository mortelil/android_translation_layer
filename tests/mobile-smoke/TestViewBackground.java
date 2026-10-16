// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

public class TestViewBackground {
    private static int pixel(View view) {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int[] pixels = new int[1600];
        bitmap.getPixels(pixels, 0, 40, 0, 0, 40, 40);
        return pixels[20 * 40 + 20];
    }
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("View background: expected "
            + Integer.toHexString(expected) + " got " + Integer.toHexString(actual));
    }
    public static void run(Context context) {
        View view = new View(context) { @Override public void onDraw(Canvas c) {} };
        view.layout(0, 0, 40, 40);
        view.setBackgroundColor(0xff123456);
        equal(0xff123456, pixel(view));
        view.setBackground(new ColorDrawable(0x80306090));
        int actual = pixel(view);
        if ((actual >>> 24) != 128) throw new AssertionError("Background alpha lost");
        GradientDrawable gradient = new GradientDrawable(); gradient.setColor(0xffabcdef);
        view.setBackground(gradient);
        equal(0xffabcdef, pixel(view));
        view.setBackground(null);
        equal(0, pixel(view));
        System.out.println("PASS: custom View draws color/shape/alpha backgrounds and clears them");
    }
}
