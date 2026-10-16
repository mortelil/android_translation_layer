package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import com.android.internal.R;

public class RippleDrawable extends LayerDrawable {

	public RippleDrawable(ColorStateList colorStateList, Drawable drawable, Drawable drawable2) {
		super(drawable == null ? new Drawable[] {} : new Drawable[] {drawable});
	}

	RippleDrawable() {}

	public void setColor(ColorStateList colorStateList) {}

	public void setRadius(int radius) {}

	public int getRadius() {
		return 0;
	}

	private RippleDrawable(LayerState state, android.content.res.Resources resources) {
		super(state, resources);
	}

	@Override LayerState createConstantState(LayerState state, android.content.res.Resources resources) {
		return new RippleDrawableState(state, this, resources);
	}

	private static final class RippleDrawableState extends LayerState {
		RippleDrawableState(LayerState state, RippleDrawable owner, android.content.res.Resources resources) {
			super(state, owner, resources);
		}
		@Override public Drawable newDrawable() { return new RippleDrawable(this, null); }
		@Override public Drawable newDrawable(android.content.res.Resources resources) {
			return new RippleDrawable(this, resources);
		}
	}

	@Override
	public void draw(Canvas canvas) {
		final ChildDrawable[] array = mLayerState.mChildren;
		final int N = mLayerState.mNum;
		for (int i = 0; i < N; i++) {
			if (array[i].mId != R.id.mask)
				array[i].mDrawable.draw(canvas);
		}
	}
}
