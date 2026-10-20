// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;

public final class TestImageSpanContract {
	public static void run(Context context) {
		Bitmap bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888);
		ImageSpan span = new ImageSpan(context, bitmap, DynamicDrawableSpan.ALIGN_CENTER);
		if (!(span.getDrawable() instanceof BitmapDrawable)
		    || ((BitmapDrawable)span.getDrawable()).getBitmap() != bitmap
		    || span.getVerticalAlignment() != DynamicDrawableSpan.ALIGN_CENTER)
			throw new AssertionError("ImageSpan(Context, Bitmap, alignment) must retain bitmap and alignment");
		System.out.println("PASS: ImageSpan bitmap construction and vertical alignment");
	}
}
