/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.drawable;

import android.graphics.Canvas;
import android.graphics.Paint;

public class ColorDrawable extends Drawable {

	private int color;
	private int baseColor;
	private Paint paint;

	public ColorDrawable() {
		this.paint = new Paint();
		this.paint.setColor(0);
	}

	public ColorDrawable(int color) {
		this.baseColor = this.color = color;
		this.paint = new Paint();
		this.paint.setColor(color);
	}

	public int getColor() {
		return color;
	}

	@Override
	public int getOpacity() {
		int alpha = android.graphics.Color.alpha(color);
		if (alpha == 255) return android.graphics.PixelFormat.OPAQUE;
		if (alpha == 0) return android.graphics.PixelFormat.TRANSPARENT;
		return android.graphics.PixelFormat.TRANSLUCENT;
	}

	@Override
	public void draw(Canvas canvas) {
		canvas.drawRect(getBounds(), paint);
	}

	// Alpha calculation adapted from AOSP android-17.0.0_r1 ColorDrawable.java.
	@Override
	public void setAlpha(int alpha) {
		alpha += alpha >> 7;
		int useAlpha = (baseColor >>> 24) * alpha >> 8;
		int useColor = (baseColor & 0x00ffffff) | (useAlpha << 24);
		if (color != useColor) {
			color = useColor;
			paint.setColor(color);
			invalidateSelf();
		}
	}

	public int getAlpha() { return color >>> 24; }

	public void setColor(int color) {
		this.baseColor = this.color = color;
		paint.setColor(color);
		invalidateSelf();
	}
}
