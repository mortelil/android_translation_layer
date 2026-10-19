/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view.accessibility;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import java.util.Locale;

public class CaptioningManager {

	public boolean isEnabled() {
		return false;
	}

	/**
	 * No caption-language preference is configured in ATL. Android returns null
	 * in that case, allowing the player to choose; the device locale is not an
	 * explicit caption preference. See AOSP android-14.0.0_r1 CaptioningManager.
	 */
	public final Locale getLocale() {
		return null;
	}

	public float getFontScale() {
		return 1.0f;
	}

	/**
	 * Returns the user's preferred caption style. AOSP reads the preset from the secure setting
	 * {@code ACCESSIBILITY_CAPTIONING_PRESET} and returns a custom style only when the setting
	 * holds {@link CaptionStyle#PRESET_CUSTOM}; with the default setting the preset is
	 * white-on-black, i.e. {@link CaptionStyle#DEFAULT}.
	 */
	public CaptionStyle getUserStyle() {
		return CaptionStyle.DEFAULT;
	}

	public static final class CaptionStyle {
		/**
		 * Packed value for a color of 'none' and a cached opacity of 100%.
		 *
		 * @hide
		 */
		private static final int COLOR_NONE_OPAQUE = 0x000000FF;

		/**
		 * Packed value for a color of 'default' and opacity of 100%.
		 *
		 * @hide
		 */
		public static final int COLOR_UNSPECIFIED = 0x00FFFFFF;

		private static final CaptionStyle WHITE_ON_BLACK;
		private static final CaptionStyle BLACK_ON_WHITE;
		private static final CaptionStyle YELLOW_ON_BLACK;
		private static final CaptionStyle YELLOW_ON_BLUE;
		private static final CaptionStyle DEFAULT_CUSTOM;
		private static final CaptionStyle UNSPECIFIED;

		/** The default caption style used to fill in unspecified values. @hide */
		public static final CaptionStyle DEFAULT;

		/** @hide */
		public static final CaptionStyle[] PRESETS;

		/** @hide */
		public static final int PRESET_CUSTOM = -1;

		/** Unspecified edge type value. */
		public static final int EDGE_TYPE_UNSPECIFIED = -1;

		/** Edge type value specifying no character edges. */
		public static final int EDGE_TYPE_NONE = 0;

		/** Edge type value specifying uniformly outlined character edges. */
		public static final int EDGE_TYPE_OUTLINE = 1;

		/** Edge type value specifying drop-shadowed character edges. */
		public static final int EDGE_TYPE_DROP_SHADOW = 2;

		/** Edge type value specifying raised bevel character edges. */
		public static final int EDGE_TYPE_RAISED = 3;

		/** Edge type value specifying depressed bevel character edges. */
		public static final int EDGE_TYPE_DEPRESSED = 4;

		/** The preferred foreground color for video captions. */
		public final int foregroundColor;

		/** The preferred background color for video captions. */
		public final int backgroundColor;

		/** The preferred edge type for video captions. */
		public final int edgeType;

		/** The preferred edge color for video captions. */
		public final int edgeColor;

		/** The preferred window color for video captions. */
		public final int windowColor;

		/**
		 * @hide
		 */
		public final String mRawTypeface;

		private final boolean mHasForegroundColor;
		private final boolean mHasBackgroundColor;
		private final boolean mHasEdgeType;
		private final boolean mHasEdgeColor;
		private final boolean mHasWindowColor;

		/** Lazily-created typeface based on the raw typeface string. */
		private Typeface mParsedTypeface;

		private CaptionStyle(int foregroundColor, int backgroundColor, int edgeType, int edgeColor,
				int windowColor, String rawTypeface) {
			mHasForegroundColor = hasColor(foregroundColor);
			mHasBackgroundColor = hasColor(backgroundColor);
			mHasEdgeType = edgeType != EDGE_TYPE_UNSPECIFIED;
			mHasEdgeColor = hasColor(edgeColor);
			mHasWindowColor = hasColor(windowColor);

			// Always use valid colors, even when no override is specified, to
			// ensure backwards compatibility with apps targeting KitKat MR2.
			this.foregroundColor = mHasForegroundColor ? foregroundColor : Color.WHITE;
			this.backgroundColor = mHasBackgroundColor ? backgroundColor : Color.BLACK;
			this.edgeType = mHasEdgeType ? edgeType : EDGE_TYPE_NONE;
			this.edgeColor = mHasEdgeColor ? edgeColor : Color.BLACK;
			this.windowColor = mHasWindowColor ? windowColor : COLOR_NONE_OPAQUE;

			mRawTypeface = rawTypeface;
		}

		/**
		 * Returns whether a packed color indicates a non-default value.
		 *
		 * @param packedColor the packed color value
		 * @return {@code true} if a non-default value is specified
		 * @hide
		 */
		public static boolean hasColor(int packedColor) {
			// Matches the color packing code from Settings. "Default" packed
			// colors are indicated by zero alpha and non-zero red/blue. The
			// cached alpha value used by Settings is stored in green.
			return (packedColor >>> 24) != 0 || (packedColor & 0xFFFF00) == 0;
		}

		/**
		 * Applies a caption style, overriding any properties that are specified
		 * in the overlay caption.
		 *
		 * @param overlay The style to apply
		 * @return A caption style with the overlay style applied
		 * @hide
		 */
		public CaptionStyle applyStyle(CaptionStyle overlay) {
			final int newForegroundColor = overlay.hasForegroundColor() ?
					overlay.foregroundColor : foregroundColor;
			final int newBackgroundColor = overlay.hasBackgroundColor() ?
					overlay.backgroundColor : backgroundColor;
			final int newEdgeType = overlay.hasEdgeType() ?
					overlay.edgeType : edgeType;
			final int newEdgeColor = overlay.hasEdgeColor() ?
					overlay.edgeColor : edgeColor;
			final int newWindowColor = overlay.hasWindowColor() ?
					overlay.windowColor : windowColor;
			final String newRawTypeface = overlay.mRawTypeface != null ?
					overlay.mRawTypeface : mRawTypeface;
			return new CaptionStyle(newForegroundColor, newBackgroundColor, newEdgeType,
					newEdgeColor, newWindowColor, newRawTypeface);
		}

		/**
		 * @return {@code true} if the user has specified a background color
		 *         that should override the application default, {@code false}
		 *         otherwise
		 */
		public boolean hasBackgroundColor() {
			return mHasBackgroundColor;
		}

		/**
		 * @return {@code true} if the user has specified a foreground color
		 *         that should override the application default, {@code false}
		 *         otherwise
		 */
		public boolean hasForegroundColor() {
			return mHasForegroundColor;
		}

		/**
		 * @return {@code true} if the user has specified an edge type that
		 *         should override the application default, {@code false}
		 *         otherwise
		 */
		public boolean hasEdgeType() {
			return mHasEdgeType;
		}

		/**
		 * @return {@code true} if the user has specified an edge color that
		 *         should override the application default, {@code false}
		 *         otherwise
		 */
		public boolean hasEdgeColor() {
			return mHasEdgeColor;
		}

		/**
		 * @return {@code true} if the user has specified a window color that
		 *         should override the application default, {@code false}
		 *         otherwise
		 */
		public boolean hasWindowColor() {
			return mHasWindowColor;
		}

		/**
		 * @return the preferred {@link Typeface} for video captions, or null if
		 *         not specified
		 */
		public Typeface getTypeface() {
			if (mParsedTypeface == null && !TextUtils.isEmpty(mRawTypeface)) {
				mParsedTypeface = Typeface.create(mRawTypeface, Typeface.NORMAL);
			}
			return mParsedTypeface;
		}

		static {
			WHITE_ON_BLACK = new CaptionStyle(Color.WHITE, Color.BLACK, EDGE_TYPE_NONE,
					Color.BLACK, COLOR_NONE_OPAQUE, null);
			BLACK_ON_WHITE = new CaptionStyle(Color.BLACK, Color.WHITE, EDGE_TYPE_NONE,
					Color.BLACK, COLOR_NONE_OPAQUE, null);
			YELLOW_ON_BLACK = new CaptionStyle(Color.YELLOW, Color.BLACK, EDGE_TYPE_NONE,
					Color.BLACK, COLOR_NONE_OPAQUE, null);
			YELLOW_ON_BLUE = new CaptionStyle(Color.YELLOW, Color.BLUE, EDGE_TYPE_NONE,
					Color.BLACK, COLOR_NONE_OPAQUE, null);
			UNSPECIFIED = new CaptionStyle(COLOR_UNSPECIFIED, COLOR_UNSPECIFIED,
					EDGE_TYPE_UNSPECIFIED, COLOR_UNSPECIFIED, COLOR_UNSPECIFIED, null);

			// The ordering of these cannot change since we store the index
			// directly in preferences.
			PRESETS = new CaptionStyle[] {
					WHITE_ON_BLACK, BLACK_ON_WHITE, YELLOW_ON_BLACK, YELLOW_ON_BLUE, UNSPECIFIED
			};

			DEFAULT_CUSTOM = WHITE_ON_BLACK;
			DEFAULT = WHITE_ON_BLACK;
		}
	}

	public static abstract class CaptioningChangeListener {
		public void onEnabledChanged(boolean enabled) {}
		public void onUserStyleChanged(CaptionStyle userStyle) {}
		public void onLocaleChanged(Locale locale) {}
		public void onFontScaleChanged(float fontScale) {}
		public void onSystemAudioCaptioningChanged(boolean enabled) {}
		public void onSystemAudioCaptioningUiChanged(boolean enabled) {}
	}

	// ATL currently exposes fixed caption preferences. Retain registrations without
	// synthesizing change events; removing a listener must release that reference.
	private final java.util.ArrayList<CaptioningChangeListener> listeners = new java.util.ArrayList<>();
	public void addCaptioningChangeListener(CaptioningChangeListener listener) {
		synchronized (listeners) { listeners.add(listener); }
	}
	public void removeCaptioningChangeListener(CaptioningChangeListener listener) {
		synchronized (listeners) { listeners.remove(listener); }
	}
}
