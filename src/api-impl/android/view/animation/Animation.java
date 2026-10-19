/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view.animation;

import android.os.Handler;

public class Animation {

	public static final int ABSOLUTE = 0;
	public static final int RELATIVE_TO_SELF = 1;
	public static final int RELATIVE_TO_PARENT = 2;

	/*
	 * Pivot/dimension resolution and the scale factor applied to interpolated
	 * values, as used by transform-based animations.
	 *
	 * Adapted from AOSP platform/frameworks/base
	 *   tag     android-17.0.0_r1
	 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
	 *   file    core/java/android/view/animation/Animation.java
	 *   methods ABSOLUTE/RELATIVE_TO_SELF/RELATIVE_TO_PARENT constants,
	 *           getScaleFactor(), resolveSize()
	 * AOSP is licensed under the Apache License, Version 2.0
	 * (http://www.apache.org/licenses/LICENSE-2.0); see this repository's LICENSE.txt.
	 *
	 * AOSP updates mScaleFactor from getTransformation(currentTime,
	 * outTransformation, scale) when an animation runs inside an AnimationSet;
	 * this port's start()/applyTransformation() path applies a transformation
	 * once, so the factor keeps its AOSP default of 1.
	 */
	private float mScaleFactor = 1f;

	protected float getScaleFactor() {
		return mScaleFactor;
	}

	protected float resolveSize(int type, float value, int size, int parentSize) {
		switch (type) {
			case ABSOLUTE:
				return value;
			case RELATIVE_TO_SELF:
				return size * value;
			case RELATIVE_TO_PARENT:
				return parentSize * value;
			default:
				return value;
		}
	}

	public interface AnimationListener {
		public void onAnimationEnd(Animation animation);
		public void onAnimationRepeat(Animation animation);
		public void onAnimationStart(Animation animation);
	}

	private AnimationListener listener;
	private boolean started;
	private boolean ended;
	private boolean dispatchingCancel;
	private long generation;

	public void setDuration(long durationMillis) {}

	public void setInterpolator(Interpolator i) {}

	public void cancel() {
		if (dispatchingCancel) return;
		long cancelledGeneration = ++generation;
		if (started && !ended) {
			dispatchingCancel = true;
			try {
				if (listener != null) listener.onAnimationEnd(this);
			} finally {
				dispatchingCancel = false;
				if (generation == cancelledGeneration) ended = true;
			}
		}
	}

	public void setFillBefore(boolean dummy) {}
	public void setFillAfter(boolean dummy) {}

	public void setStartOffset(long offset) {}

	public void setAnimationListener(AnimationListener l) {
		this.listener = l;
	}

	public void setRepeatCount(int count) {}

	public void setRepeatMode(int mode) {}

	public void initialize(int width, int height, int parentWidth, int parentHeight) {}

	public void applyTransformation(float interpolatedTime, Transformation t) {}

	public void reset() {}

	public void start() {
		setStartTime(-1);
		final long runGeneration = generation;
		new Handler().post(new Runnable() {
			@Override
			public void run() {
				if (generation != runGeneration) return;
				initialize(0, 0, 0, 0);
				if (generation != runGeneration) return;
				started = true;
				if (listener != null)
					listener.onAnimationStart(Animation.this);
				if (generation != runGeneration) return;
				applyTransformation(1, new Transformation());
				if (generation != runGeneration) return;
				ended = true;
				if (listener != null)
					listener.onAnimationEnd(Animation.this);
			}
		});
	}

	public boolean hasStarted() { return started; }
	public boolean hasEnded() { return ended; }

	public void setStartTime(long startTime) {
		// AOSP resets these flags on setStartTime, not reset(). ATL still uses
		// the existing single posted transformation, rather than a frame clock.
		generation++;
		started = ended = false;
	}
}
