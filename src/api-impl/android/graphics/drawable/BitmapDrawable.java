package android.graphics.drawable;

import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import com.android.internal.R;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class BitmapDrawable extends Drawable {

	private Bitmap bitmap;
	private int targetDensity = android.util.DisplayMetrics.DENSITY_DEFAULT;
	private final Paint paint = new Paint();

	public BitmapDrawable() {
	}

	public BitmapDrawable(Resources res, Bitmap bitmap) {
		this.bitmap = bitmap;
		if (res != null) targetDensity = res.getDisplayMetrics().densityDpi;
	}

	public BitmapDrawable(Bitmap bitmap) {
		this.bitmap = bitmap;
	}

	public Bitmap getBitmap() {
		return bitmap;
	}

	public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
	    throws XmlPullParserException, IOException {
		final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.BitmapDrawable);
		if (a.hasValue(R.styleable.BitmapDrawable_src)) {
			BitmapDrawable dr = null;
			try {
				// TODO: getDrawable() might pick up another <bitmap> XML,
				// but we should reject anything other than image files.
				dr = (BitmapDrawable)a.getDrawable(R.styleable.BitmapDrawable_src);
			} catch (java.lang.Exception e) {
				e.printStackTrace();
			}
			if (dr != null)
				bitmap = dr.bitmap;
		}
		a.recycle();
		if (bitmap == null)
			throw new XmlPullParserException("<bitmap> needs a valid `src' attribute");
		targetDensity = r.getDisplayMetrics().densityDpi;
	}

	@Override
	public int getIntrinsicWidth() {
		if (bitmap == null)
			return 0;
		return bitmap.getScaledWidth(targetDensity);
	}

	@Override
	public int getIntrinsicHeight() {
		if (bitmap == null)
			return 0;
		return bitmap.getScaledHeight(targetDensity);
	}

	public void setTargetDensity(int density) {
		targetDensity = density == 0 ? android.util.DisplayMetrics.DENSITY_DEFAULT : density;
		invalidateSelf();
	}

	@Override
	public void draw(Canvas canvas) {
		if (bitmap != null && !getBounds().isEmpty())
			canvas.drawBitmap(bitmap, null, getBounds(), paint);
	}

	public void setTileModeX(Shader.TileMode mode) {}

	public Paint getPaint() {
		return paint;
	}

	public void setGravity(int g) {}
}
