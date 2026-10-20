package android.text.style;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

public class ImageSpan extends DynamicDrawableSpan {

	public ImageSpan(Context context, int resId) {
		super();
		drawable = context.getDrawable(resId);
	}

	private Drawable drawable;

	public ImageSpan(Drawable d) {
		super();
		drawable = d;
	}

	public ImageSpan(Drawable d, String source) {
		super();
		drawable = d;
	}

	public ImageSpan(Drawable d, int verticalAlignment) {
		super(verticalAlignment);
		drawable = d;
	}

	public ImageSpan(Context context, Bitmap bitmap, int verticalAlignment) {
		super(verticalAlignment);
		drawable = new BitmapDrawable(context.getResources(), bitmap);
	}

	public Drawable getDrawable() {
		return drawable;
	}

	@Override
	public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
		return drawable.getIntrinsicWidth();
	}
}
