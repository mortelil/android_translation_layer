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
 * Defines the transformation to be applied at one point in time of an
 * Animation.
 *
 * Adapted from AOSP platform/frameworks/base
 *   tag     android-17.0.0_r1
 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
 *   file    core/java/android/view/animation/Transformation.java
 *   members mMatrix / getMatrix()
 * AOSP is licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0); see this repository's LICENSE.txt.
 *
 * AOSP's Transformation owns the matrix an animation's applyTransformation()
 * fills in: getMatrix() hands back mMatrix itself, so the transform survives
 * the call. This port originally returned a fresh Matrix on every call, which
 * discarded whatever an animation had built.
 */
package android.view.animation;

import android.graphics.Matrix;

public class Transformation {

	private final Matrix mMatrix = new Matrix();

	public Matrix getMatrix() {
		return mMatrix;
	}
}
