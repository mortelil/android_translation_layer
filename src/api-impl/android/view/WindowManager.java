/*
 * Copyright (C) 2006 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * LayoutParams constructors/copy contract adapted from frameworks/base
 * android-15.0.0_r1 core/java/android/view/WindowManager.java.
 */
package android.view;

import android.os.IBinder;

public interface WindowManager {
	public android.view.Display getDefaultDisplay();

	public void addView(View view, ViewGroup.LayoutParams params);

	public void updateViewLayout(View view, ViewGroup.LayoutParams params);

	public void removeView(View view);

	public void removeViewImmediate(View view);

	public class LayoutParams extends ViewGroup.LayoutParams {
		public int preferredDisplayModeId;
		public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
		public static final int FLAG_DIM_BEHIND = 2;
		public static final int FLAG_NOT_FOCUSABLE = 8;
		public static final int FLAG_NOT_TOUCHABLE = 16;

		public float screenBrightness = -1;
		public int softInputMode;
		public int x;
		public int y;
		public int gravity;
		public int windowAnimations;
		public int flags;
		public float alpha = 1.0f;
		public int type;
		public IBinder token;
		public int format;
		public int layoutInDisplayCutoutMode;
		public String packageName;
		public int rotationAnimation;

		public LayoutParams(int w, int h, int type, int flags, int format) {
			super(w, h);
			this.type = type;
			this.flags = flags;
			this.format = format;
		}

		public LayoutParams(int type) {
			super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
			this.type = type;
			this.format = android.graphics.PixelFormat.OPAQUE;
		}

		public LayoutParams() { this(2); }

		private CharSequence mTitle;

		/* Change bits from AOSP android-15.0.0_r1 WindowManager.LayoutParams. */
		public static final int LAYOUT_CHANGED = 1 << 0;
		public static final int TYPE_CHANGED = 1 << 1;
		public static final int FLAGS_CHANGED = 1 << 2;
		public static final int FORMAT_CHANGED = 1 << 3;
		public static final int ANIMATION_CHANGED = 1 << 4;
		public static final int DIM_AMOUNT_CHANGED = 1 << 5;
		public static final int TITLE_CHANGED = 1 << 6;
		public static final int ALPHA_CHANGED = 1 << 7;
		public static final int SOFT_INPUT_MODE_CHANGED = 1 << 9;
		public static final int SCREEN_BRIGHTNESS_CHANGED = 1 << 11;
		public static final int ROTATION_ANIMATION_CHANGED = 1 << 12;
		public static final int PRIVATE_FLAGS_CHANGED = 1 << 17;
		public static final int PREFERRED_DISPLAY_MODE_ID = 1 << 23;
		public static final int TRANSLUCENT_FLAGS_CHANGED = 1 << 19;
		/* AOSP values, same file; used by the flags diff in copyFrom(). */
		public static final int FLAG_TRANSLUCENT_STATUS = 0x04000000;
		public static final int FLAG_TRANSLUCENT_NAVIGATION = 0x08000000;

		/**
		 * AOSP WindowManager.LayoutParams.copyFrom(LayoutParams):
		 * copy another window's parameters into this one and return the set of
		 * change bits. Clients (the app's own dialog helper among them) compare
		 * those bits to decide what has to be re-applied to the window manager.
		 * This copies all fields currently modeled by ATL; it does not implement
		 * the remaining Android window attributes or window-manager behavior.
		 */
		public int copyFrom(LayoutParams o) {
			int changes = 0;

			if (width != o.width) {
				width = o.width;
				changes |= LAYOUT_CHANGED;
			}
			if (height != o.height) {
				height = o.height;
				changes |= LAYOUT_CHANGED;
			}
			if (x != o.x) {
				x = o.x;
				changes |= LAYOUT_CHANGED;
			}
			if (y != o.y) {
				y = o.y;
				changes |= LAYOUT_CHANGED;
			}
			if (type != o.type) {
				type = o.type;
				changes |= TYPE_CHANGED;
			}
			if (flags != o.flags) {
				final int diff = flags ^ o.flags;
				if ((diff & (FLAG_TRANSLUCENT_STATUS | FLAG_TRANSLUCENT_NAVIGATION)) != 0)
					changes |= TRANSLUCENT_FLAGS_CHANGED;
				flags = o.flags;
				changes |= FLAGS_CHANGED;
			}
			if (softInputMode != o.softInputMode) {
				softInputMode = o.softInputMode;
				changes |= SOFT_INPUT_MODE_CHANGED;
			}
			if (layoutInDisplayCutoutMode != o.layoutInDisplayCutoutMode) {
				layoutInDisplayCutoutMode = o.layoutInDisplayCutoutMode;
				changes |= LAYOUT_CHANGED;
			}
			if (gravity != o.gravity) {
				gravity = o.gravity;
				changes |= LAYOUT_CHANGED;
			}
			if (format != o.format) {
				format = o.format;
				changes |= FORMAT_CHANGED;
			}
			if (windowAnimations != o.windowAnimations) {
				windowAnimations = o.windowAnimations;
				changes |= ANIMATION_CHANGED;
			}
			/* AOSP: the token, the package name and the title are only taken
			 * over when the recipient has none / the originator set one. */
			if (token == null)
				token = o.token;
			if (packageName == null)
				packageName = o.packageName;
			if ((mTitle == null ? o.mTitle != null : !mTitle.equals(o.mTitle)) && o.mTitle != null) {
				mTitle = o.mTitle;
				changes |= TITLE_CHANGED;
			}
			if (alpha != o.alpha) {
				alpha = o.alpha;
				changes |= ALPHA_CHANGED;
			}
			if (screenBrightness != o.screenBrightness) {
				screenBrightness = o.screenBrightness;
				changes |= SCREEN_BRIGHTNESS_CHANGED;
			}
			if (rotationAnimation != o.rotationAnimation) {
				rotationAnimation = o.rotationAnimation;
				changes |= ROTATION_ANIMATION_CHANGED;
			}
			if (preferredDisplayModeId != o.preferredDisplayModeId) {
				preferredDisplayModeId = o.preferredDisplayModeId;
				changes |= PREFERRED_DISPLAY_MODE_ID;
			}
			return changes;
		}

		public CharSequence getTitle() { return mTitle == null ? "" : mTitle; }

		/** AOSP stores the window title it was given (copyFrom() copies it over). */
		public void setTitle(CharSequence title) {
			mTitle = title == null ? "" : title instanceof android.text.Spanned
			    ? new android.text.SpannedString(title) : title.toString();
		}
	}
}
