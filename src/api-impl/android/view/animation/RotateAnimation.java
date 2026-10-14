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

/*
 * An animation that controls the rotation of an object in the X-Y plane.
 *
 * Adapted from AOSP platform/frameworks/base
 *   tag     android-17.0.0_r1
 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
 *   file    core/java/android/view/animation/RotateAnimation.java
 *   methods constructors (float,float), (float,float,float,float) and
 *           (float,float,int,float,int,float), initializePivotPoint(),
 *           applyTransformation(), initialize()
 * AOSP is licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0); see this repository's LICENSE.txt.
 *
 * The Context/AttributeSet constructor of the AOSP original reads its
 * android:fromDegrees/toDegrees/pivotX/pivotY attributes through the hidden
 * android.util.Description helper class; this port has no XML animation
 * inflater (AnimationUtils.loadAnimation() returns a plain Animation), so only
 * the code-facing constructors are provided.
 */
package android.view.animation;

public class RotateAnimation extends Animation {

	private float mFromDegrees;
	private float mToDegrees;

	private int mPivotXType = ABSOLUTE;
	private int mPivotYType = ABSOLUTE;
	private float mPivotXValue = 0.0f;
	private float mPivotYValue = 0.0f;

	private float mPivotX;
	private float mPivotY;

	/**
	 * Constructor to use when building a RotateAnimation from code.
	 * Default pivotX/pivotY point is (0,0).
	 *
	 * @param fromDegrees Rotation offset to apply at the start of the
	 *        animation.
	 * @param toDegrees Rotation offset to apply at the end of the animation.
	 */
	public RotateAnimation(float fromDegrees, float toDegrees) {
		mFromDegrees = fromDegrees;
		mToDegrees = toDegrees;
		mPivotX = 0.0f;
		mPivotY = 0.0f;
	}

	/**
	 * Constructor to use when building a RotateAnimation from code.
	 *
	 * @param fromDegrees Rotation offset to apply at the start of the
	 *        animation.
	 * @param toDegrees Rotation offset to apply at the end of the animation.
	 * @param pivotX The X coordinate of the point about which the object is
	 *        being rotated, specified as an absolute number where 0 is the
	 *        left edge.
	 * @param pivotY The Y coordinate of the point about which the object is
	 *        being rotated, specified as an absolute number where 0 is the
	 *        top edge.
	 */
	public RotateAnimation(float fromDegrees, float toDegrees, float pivotX, float pivotY) {
		mFromDegrees = fromDegrees;
		mToDegrees = toDegrees;

		mPivotXType = ABSOLUTE;
		mPivotYType = ABSOLUTE;
		mPivotXValue = pivotX;
		mPivotYValue = pivotY;
		initializePivotPoint();
	}

	/**
	 * Constructor to use when building a RotateAnimation from code.
	 *
	 * @param fromDegrees Rotation offset to apply at the start of the
	 *        animation.
	 * @param toDegrees Rotation offset to apply at the end of the animation.
	 * @param pivotXType Specifies how pivotXValue should be interpreted. One
	 *        of Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
	 *        Animation.RELATIVE_TO_PARENT.
	 * @param pivotXValue The X coordinate of the point about which the object
	 *        is being rotated, specified as an absolute number where 0 is the
	 *        left edge. This value can either be an absolute number if
	 *        pivotXType is ABSOLUTE, or a percentage (where 1.0 is 100%)
	 *        otherwise.
	 * @param pivotYType Specifies how pivotYValue should be interpreted. One
	 *        of Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
	 *        Animation.RELATIVE_TO_PARENT.
	 * @param pivotYValue The Y coordinate of the point about which the object
	 *        is being rotated, specified as an absolute number where 0 is the
	 *        top edge. This value can either be an absolute number if
	 *        pivotYType is ABSOLUTE, or a percentage (where 1.0 is 100%)
	 *        otherwise.
	 */
	public RotateAnimation(float fromDegrees, float toDegrees, int pivotXType, float pivotXValue,
	                       int pivotYType, float pivotYValue) {
		mFromDegrees = fromDegrees;
		mToDegrees = toDegrees;

		mPivotXValue = pivotXValue;
		mPivotXType = pivotXType;
		mPivotYValue = pivotYValue;
		mPivotYType = pivotYType;
		initializePivotPoint();
	}

	/**
	 * Called at the end of constructor methods to initialize, if possible,
	 * values for the pivot point. This is only possible for ABSOLUTE pivot
	 * values.
	 */
	private void initializePivotPoint() {
		if (mPivotXType == ABSOLUTE) {
			mPivotX = mPivotXValue;
		}
		if (mPivotYType == ABSOLUTE) {
			mPivotY = mPivotYValue;
		}
	}

	@Override
	public void applyTransformation(float interpolatedTime, Transformation t) {
		float degrees = mFromDegrees + ((mToDegrees - mFromDegrees) * interpolatedTime);
		float scale = getScaleFactor();

		if (mPivotX == 0.0f && mPivotY == 0.0f) {
			t.getMatrix().setRotate(degrees);
		} else {
			t.getMatrix().setRotate(degrees, mPivotX * scale, mPivotY * scale);
		}
	}

	@Override
	public void initialize(int width, int height, int parentWidth, int parentHeight) {
		super.initialize(width, height, parentWidth, parentHeight);
		mPivotX = resolveSize(mPivotXType, mPivotXValue, width, parentWidth);
		mPivotY = resolveSize(mPivotYType, mPivotYValue, height, parentHeight);
	}
}
