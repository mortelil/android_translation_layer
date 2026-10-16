package android.graphics.drawable;

import android.graphics.Canvas;

public class TransitionDrawable extends LayerDrawable {

	public TransitionDrawable(Drawable[] layers) {
		super(layers);
	}

	public void setCrossFadeEnabled(boolean enabled) {}

	public void startTransition(int duration) {}

	private TransitionDrawable(LayerState state, android.content.res.Resources resources) {
		super(state, resources);
	}

	@Override LayerState createConstantState(LayerState state, android.content.res.Resources resources) {
		return new TransitionDrawableState(state, this, resources);
	}

	private static final class TransitionDrawableState extends LayerState {
		TransitionDrawableState(LayerState state, TransitionDrawable owner, android.content.res.Resources resources) {
			super(state, owner, resources);
		}
		@Override public Drawable newDrawable() { return new TransitionDrawable(this, null); }
		@Override public Drawable newDrawable(android.content.res.Resources resources) {
			return new TransitionDrawable(this, resources);
		}
	}

	@Override
	public void draw(Canvas canvas) {
		// always draw the target drawable
		mLayerState.mChildren[1].mDrawable.draw(canvas);
	}
}
