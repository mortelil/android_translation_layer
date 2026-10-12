package android.graphics.drawable;

import android.content.Context;
import android.graphics.Bitmap;

public class Icon {

	public static Icon createWithResource(String packageName, int resourceId) {
		return null;
	}

	public static Icon createWithResource(Context context, int resId) {
		return createWithResource(context.getPackageName(), resId);
	}

	public static Icon createWithBitmap(Bitmap bitmap) {
		return null;
	}
}
