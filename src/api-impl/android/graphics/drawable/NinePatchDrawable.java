package android.graphics.drawable;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;

public class NinePatchDrawable extends Drawable {

	public NinePatchDrawable(Resources res, Bitmap bitmap, byte[] data, Rect padding, String name) {
		if (bitmap != null && data != null)
			setPaintable(nativeCreate(data, bitmap.getTexture()));
	}

	NinePatchDrawable(String path) {
		setPaintable(nativeCreate(path));
	}

	void setDensity(int source, int target) {
		if (source == 0) source = 160;
		if (source == android.util.TypedValue.DENSITY_NONE) source = target;
		if (paintable != 0) nativeSetDensity(paintable, source > 0 ? (float)target / source : 1);
	}

	private native void nativeSetDensity(long paintable, float scale);

	@Override
	public void setTint(int tint) {
		if (paintable != 0) nativeSetTint(paintable, tint);
	}

	private native long nativeCreate(byte[] data, long texture);
	private native long nativeCreate(String path);
	private native void nativeSetTint(long paintable, int tint);
}
